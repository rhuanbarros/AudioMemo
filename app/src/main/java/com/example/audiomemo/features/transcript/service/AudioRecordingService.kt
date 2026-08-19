package com.example.audiomemo.features.transcript.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.preferences.AppPreferencesRepository
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker
import com.example.audiomemo.features.transcript.data.worker.UploadPreferences
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.features.summary.data.worker.SummaryGenerationWorker
import com.example.audiomemo.features.transcript.data.worker.ChunkFinalizationWorker
import com.example.audiomemo.features.transcript.data.worker.TranscriptRetryWorker
import com.example.audiomemo.features.transcript.data.worker.WhisperUploadWorker
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import com.example.audiomemo.features.transcript.manager.AudioInterruptionManager
import com.example.audiomemo.features.transcript.manager.AudioRecorderManager
import com.example.audiomemo.features.transcript.manager.BatteryGuard
import com.example.audiomemo.features.transcript.manager.LocationCaptureManager
import com.example.audiomemo.features.transcript.manager.MediaButtonHandler
import com.example.audiomemo.features.transcript.manager.SessionStateManager
import com.example.audiomemo.features.transcript.manager.SilenceDetector
import com.example.audiomemo.features.transcript.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class AudioRecordingService : Service() {

    /**
     * Outcome of [runHardwareRecoveryLoop] — what [handleHardwareError] branches on.
     *
     * Deliberately nested directly on the class, never inside `companion object` (mirrors
     * [AudioRecorderManager.ChunkAmplitudeOutcome]'s KDoc, same pitfall): a class/enum nested
     * inside a companion object is only reachable from other files as
     * `AudioRecordingService.Companion.HardwareRecoveryResult`, not the shorter
     * `AudioRecordingService.HardwareRecoveryResult` this type's test callers use — unlike plain
     * functions/properties, Kotlin does not promote nested *types* out of a companion object.
     */
    internal enum class HardwareRecoveryResult { RECOVERED, STORAGE_INSUFFICIENT, EXHAUSTED }

    companion object {
        const val ACTION_STOP = "com.example.audiomemo.action.STOP_RECORDING"
        const val ACTION_RESUME = "com.example.audiomemo.action.RESUME_RECORDING"
        private const val TAG = "AudioRecordingService"

        /**
         * How often [heartbeatJob] writes [AppPreferencesRepository.recordHeartbeat] while
         * recording (am3-2, FR2). Deliberately independent of the 100ms amplitude-sampling loop
         * already running in [AudioRecorderManager] — writing to DataStore at that frequency
         * would be expensive; this is a separate, much lighter ticker.
         */
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /**
         * Builds the exact [OneTimeWorkRequest] [enqueueSupabaseUpload] passes to `WorkManager`.
         * Extracted as a pure, `Context`-free `internal` function — building a `WorkRequest`
         * needs no `WorkManager`/`Context` at all (only the actual *enqueue* call does) — so
         * [com.example.audiomemo.features.transcript.service.AudioRecordingServiceConflictResolutionTest]
         * can assert the FR5 network constraint (`CONNECTED`, never Wi-Fi-only) and the input data
         * directly, without `WorkManagerTestInitHelper` (which needs a real or Robolectric
         * `Context` this project doesn't have — see that test file's docblock).
         */
        internal fun buildSupabaseUploadWorkRequest(chunkId: Long, sessionId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(
                    workDataOf(
                        SupabaseUploadWorker.KEY_CHUNK_ID to chunkId,
                        SupabaseUploadWorker.KEY_SESSION_ID to sessionId
                    )
                )
                .build()

        /** The unique WorkManager work name [enqueueSupabaseUpload] enqueues under, for a given chunk. */
        internal fun supabaseUploadWorkName(chunkId: Long): String =
            "${SupabaseUploadWorker.WORK_NAME_PREFIX}$chunkId"

        /**
         * Chunk file size, in KB, for the "Chunk finalized" log message (am4-1, FR7). Ceiling-
         * rounds (`+ 1023`) rather than floors — a genuinely tiny non-zero chunk (e.g. 500 bytes)
         * must never report the same `sizeKb=0` as a truly empty file, which is exactly the
         * failure case this field exists to make visible. Pure, `File`/`Context`-free `internal`
         * function taking the raw byte count (not a `File`) so it's unit-testable from plain-JVM
         * `src/test`, mirroring [SupabaseUploadWorker.computeUploadLatencyMs].
         */
        internal fun computeChunkSizeKb(fileSizeBytes: Long): Long =
            if (fileSizeBytes <= 0L) 0L else (fileSizeBytes + 1023) / 1024

        /**
         * Pure FR9/FR10 decision: whether a just-finished chunk's [AudioRecorderManager.
         * ChunkAmplitudeOutcome] should enqueue a Supabase upload at all. Extracted (code review,
         * am4-2, patch 2) — this is the actual "never upload a silent chunk" behavior this story
         * exists for, previously inline-only inside [onChunkCompleted]'s lambda with zero test
         * coverage (unlike every other decision in this story, e.g.
         * [AudioRecorderManager.evaluateChunkAmplitude]). `SILENT` is the only outcome that skips
         * upload — `UNMEASURED` uploads normally (the safe default per the story's I/O matrix),
         * same as `AUDIBLE`.
         */
        internal fun shouldEnqueueSupabaseUpload(
            outcome: AudioRecorderManager.ChunkAmplitudeOutcome
        ): Boolean = outcome != AudioRecorderManager.ChunkAmplitudeOutcome.SILENT

        /**
         * (am-hotfix, never-stop-recording) Bounded immediate-recovery attempts for a hardware
         * error before falling back to a real stop + alert notification. Chosen to match this
         * project's existing "3 attempts" convention
         * ([com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker]'s
         * `decideUploadOutcome`/`SessionRecoveryOutcome`) rather than inventing an unrelated
         * number — the story's Ask-First clause is about a decision with no obvious answer; this
         * one already has a project-wide precedent. Not blocking on the owner for this reason,
         * but flagged explicitly in the closing report so the owner can override if 3 attempts
         * ~1s apart don't hold up during real-device verification.
         */
        internal const val HARDWARE_RECOVERY_MAX_ATTEMPTS = 3

        /** Delay between immediate-recovery attempts — "seconds, not minutes" per the story. */
        internal const val HARDWARE_RECOVERY_RETRY_DELAY_MS = 1_000L

        /**
         * Pure sentinel predicate (am-hotfix, never-stop-recording, FR2/heartbeat-as-sentinel):
         * recording counts as unhealthy only when it's neither deliberately stopped nor
         * deliberately paused via the physical media button — those are legitimate reasons
         * [AudioRecorderManager.isRecording] is `false`. Extracted pure/`Context`-free so it's
         * unit-testable from plain-JVM `src/test`, mirroring every other pure decision in this
         * class (e.g. [shouldEnqueueSupabaseUpload], [computeChunkSizeKb]).
         */
        internal fun isRecordingUnhealthy(
            isStopped: Boolean,
            isMediaButtonPaused: Boolean,
            recorderIsRecording: Boolean
        ): Boolean = !isStopped && !isMediaButtonPaused && !recorderIsRecording

        // ── Stop-reason log messages (code review patch 2 — cross-file coupling) ────────────
        // Single source of truth for the exact INTERRUPTION-category log text each genuine stop
        // emits. `HomeViewModel.classifyEvent`'s health-strip classifier keys off these (a
        // "— recording stopped" suffix, see that file), and `HomeViewModelTest` derives its
        // expectations from these same constants/function instead of hand-typing copies — so a
        // future edit to this text can't silently desync the two files again.

        internal const val LOW_STORAGE_STOPPED_MESSAGE = "Low storage — recording stopped"
        internal const val PERMISSION_REVOKED_STOPPED_MESSAGE = "Permission revoked — recording stopped"

        /** Battery-low is no longer a stop (am-hotfix, never-stop-recording) — observability only,
         *  deliberately does NOT end in "— recording stopped" so it's never misclassified ERROR. */
        internal const val BATTERY_LOW_CONTINUES_MESSAGE =
            "Battery low — recording continues (no longer a stop trigger)"

        internal fun hardwareErrorStoppedMessage(maxAttempts: Int = HARDWARE_RECOVERY_MAX_ATTEMPTS): String =
            "Hardware error unrecoverable after $maxAttempts immediate attempts — recording stopped"

        /**
         * Generic, `Context`-free re-entrancy guard (code review patch 3/4): runs [action] only if
         * [guard] can be atomically flipped `false -> true` (rejects a concurrent/overlapping call
         * by returning `null` without running [action] at all), and — critically — only flips
         * [guard] back to `false` in a `finally` AFTER [action] has *fully* completed, including
         * whatever asynchronous-looking tail it runs synchronously before returning. This closes
         * the window the original implementation had: clearing the guard immediately after the
         * recovery attempts returned, but BEFORE the fallback "stop for real" branch had actually
         * run, let a fresh trigger landing in that gap start a second, overlapping recovery loop
         * while the first was still tearing the service down.
         *
         * Kept generic/`AtomicBoolean`-parameterized (not hardcoded to
         * [isRecoveringFromHardwareError]) so it's unit-testable from plain-JVM `src/test` with a
         * throwaway `AtomicBoolean`, without needing a real `AudioRecordingService`/`Context`.
         */
        internal suspend fun <T> runGuarded(guard: AtomicBoolean, action: suspend () -> T): T? {
            if (!guard.compareAndSet(false, true)) return null
            return try {
                action()
            } finally {
                guard.set(false)
            }
        }

        /**
         * Pure, `Context`-free retry-loop orchestration (code review patch 7/9/11) extracted out
         * of the suspend instance method so the attempt-counting / early-exit / no-delay-before-
         * first-attempt behavior is unit-testable from plain-JVM `src/test` with a fake [attempt]
         * lambda, without needing a real [AudioRecorderManager]/`Context` (this project has no
         * Robolectric/androidTest infra — see `AudioRecordingServiceConflictResolutionTest`'s
         * docblock for the established rationale).
         *
         * (Patch 7): no delay before the very first attempt — a function documented as
         * "fast"/"immediate" must actually try immediately; only attempts after the first wait
         * [retryDelayMs] apart.
         *
         * (Patch 9): reports the *last* observed [AudioRecorderManager.RecoveryOutcome] once every
         * attempt is exhausted, so a caller can tell "ran out of storage mid-recovery" apart from
         * a genuine unrecoverable hardware fault and alert accordingly.
         */
        internal suspend fun runHardwareRecoveryLoop(
            maxAttempts: Int,
            retryDelayMs: Long,
            attempt: suspend () -> AudioRecorderManager.RecoveryOutcome
        ): HardwareRecoveryResult {
            var lastOutcome = AudioRecorderManager.RecoveryOutcome.HARDWARE_FAILURE
            repeat(maxAttempts) { index ->
                if (index > 0) delay(retryDelayMs)
                lastOutcome = attempt()
                if (lastOutcome == AudioRecorderManager.RecoveryOutcome.RECOVERED) {
                    return HardwareRecoveryResult.RECOVERED
                }
            }
            return if (lastOutcome == AudioRecorderManager.RecoveryOutcome.STORAGE_INSUFFICIENT) {
                HardwareRecoveryResult.STORAGE_INSUFFICIENT
            } else {
                HardwareRecoveryResult.EXHAUSTED
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var chunkDao: ChunkDao
    @Inject lateinit var appEventLogger: AppEventLogger
    @Inject lateinit var appPreferencesRepository: AppPreferencesRepository

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Tracks the most recent chunk-save job so we can await it before enqueuing transcription. */
    private var lastChunkSaveJob: kotlinx.coroutines.Job? = null

    /**
     * Periodic "still alive" ticker (am3-2, FR2) — cancelled implicitly by `serviceScope.cancel()`
     * in [onDestroy] along with everything else launched in [serviceScope]; no separate cancel
     * needed.
     */
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    private lateinit var recorder: AudioRecorderManager
    private lateinit var interruptionManager: AudioInterruptionManager
    private lateinit var silenceDetector: SilenceDetector
    private lateinit var sessionStateManager: SessionStateManager
    private lateinit var batteryGuard: BatteryGuard
    private lateinit var mediaButtonHandler: MediaButtonHandler
    private lateinit var locationCaptureManager: LocationCaptureManager

    /** True while the service is actively recording (not stopped). Used to reject duplicate starts. */
    private var isRecordingActive = false

    /**
     * True when the user has manually paused via a headset/media button.
     *
     * `@Volatile` (code review, am-hotfix never-stop-recording, patch 6): normally written from
     * the main thread (media-button/ACTION_RESUME handlers) but now also read every tick by the
     * heartbeat sentinel running on `serviceScope` (`Dispatchers.IO`) — without `@Volatile` that
     * cross-thread read isn't guaranteed to see the latest write.
     */
    @Volatile private var isMediaButtonPaused = false

    /**
     * (am-hotfix, never-stop-recording): guards against overlapping immediate-recovery loops.
     * [AudioRecorderManager]'s async `setOnErrorListener` can fire again while a recovery attempt
     * is already in flight (e.g. the very retry attempt itself fails); [handleHardwareError] uses
     * this (via [runGuarded]) to no-op on re-entry rather than starting a second overlapping retry
     * loop — the loop already in progress will observe the same underlying failure on its own
     * next attempt.
     *
     * `AtomicBoolean`, not `@Volatile Boolean` (code review patch 3 — CRITICAL): a plain
     * `Volatile` boolean's check-then-set (`if (!flag) { flag = true; ... }`) is two separate,
     * non-atomic operations — two concurrent callers (the sentinel coroutine vs.
     * `MediaRecorder`'s own async error-listener thread) could both read `false` before either
     * writes `true`, both proceeding to start an overlapping recovery loop.
     * `compareAndSet(false, true)` (see [runGuarded]) is the atomic single-operation fix.
     */
    private val isRecoveringFromHardwareError = AtomicBoolean(false)

    private val _isStopped = MutableStateFlow(false)
    val isStopped: StateFlow<Boolean> = _isStopped.asStateFlow()

    private val _currentSessionId = MutableStateFlow(-1L)
    val currentSessionIdFlow: StateFlow<Long> = _currentSessionId.asStateFlow()

    val amplitude: StateFlow<Int> get() = recorder.amplitude
    val lastChunkFile: StateFlow<File?> get() = recorder.lastChunkFile
    val currentSessionId: Long
        get() = if (::sessionStateManager.isInitialized) sessionStateManager.currentSessionId else -1L

    // ── Pending intents ────────────────────────────────────────────────────────

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1,
        Intent(this, AudioRecordingService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun resumePendingIntent(): PendingIntent = PendingIntent.getService(
        this, 2,
        Intent(this, AudioRecordingService::class.java).apply { action = ACTION_RESUME },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        recorder = AudioRecorderManager(applicationContext)
        sessionStateManager = SessionStateManager(sessionDao, chunkDao)
        locationCaptureManager = LocationCaptureManager()

        recorder.onChunkCompleted = { file, amplitudeOutcome ->
            lastChunkSaveJob = serviceScope.launch {
                // am4-2 (FR9/FR10): a chunk whose amplitude never crossed the silence threshold
                // during its whole recording is never enqueued for Supabase upload — the file is
                // still saved locally exactly like any other chunk (see saveChunk's KDoc).
                val wasSilent = amplitudeOutcome == AudioRecorderManager.ChunkAmplitudeOutcome.SILENT
                val chunkId = sessionStateManager.saveChunk(file.absolutePath, wasSilent = wasSilent)
                val sessionId = sessionStateManager.currentSessionId
                if (chunkId > 0L && sessionId > 0L) {
                    // am4-1 (FR7): file size in KB alongside the chunk id, so the Logs screen
                    // shows whether a chunk came out unusually small/large without needing
                    // manual instrumentation. Interpolated straight into the free-text `message`
                    // (per this story's boundary) — LogEvent/formatLine/LogFileReader stay untouched.
                    // Guards file.exists() first (mirrors SupabaseUploadWorker.doWork's own check
                    // before touching a chunk file): a concurrently-deleted/moved file must log a
                    // value clearly distinguishable from a real small chunk (-1), never a plain 0
                    // that would masquerade as "just a small file" (code review, am4-1, patch 4).
                    val sizeKb = if (file.exists()) computeChunkSizeKb(file.length()) else -1L
                    appEventLogger.log(LogCategory.RECORDING, "Chunk finalized (id=$chunkId, sizeKb=$sizeKb)")
                    // Each enqueue is isolated: a failure enqueuing one worker (e.g. WorkManager
                    // internals throwing) must never prevent the other from running — they are
                    // independent upload pipelines (Whisper vs Supabase) for the same chunk.
                    // Whisper transcription is untouched by am4-2 — only the Supabase upload
                    // (below) is skipped for a silent chunk, per this story's Code Map.
                    runCatching { enqueueChunkUpload(chunkId, sessionId) }
                        .onFailure { Log.w(TAG, "Failed to enqueue WhisperUploadWorker for chunk $chunkId", it) }

                    if (amplitudeOutcome == AudioRecorderManager.ChunkAmplitudeOutcome.UNMEASURED) {
                        // am4-2 edge case: getMaxAmplitude() stayed at 0 for the whole chunk —
                        // some devices/emulators don't support amplitude reads. Treated as
                        // "couldn't measure", never as silence (safer default — see
                        // AudioRecorderManager.ChunkAmplitudeOutcome's KDoc), but logged
                        // distinctly from the silent-skip log below so this hardware/emulator
                        // limitation stays visible on the Logs screen.
                        appEventLogger.log(
                            LogCategory.UPLOAD,
                            "Chunk amplitude could not be measured (id=$chunkId) — upload proceeding normally for safety"
                        )
                    }

                    // am4-2 (FR9/FR10), code review patch 2: the actual "never upload a silent
                    // chunk" decision — extracted into shouldEnqueueSupabaseUpload (pure,
                    // unit-tested) rather than inlined here, mirroring evaluateChunkAmplitude.
                    if (!shouldEnqueueSupabaseUpload(amplitudeOutcome)) {
                        appEventLogger.log(LogCategory.UPLOAD, "Chunk skipped: no audio detected (id=$chunkId)")
                    } else {
                        // gps-location-capture-per-chunk: best-effort, isolated from the enqueue
                        // right below — reads the owner's toggle fresh each chunk (never cached),
                        // skips entirely (no location API call at all) when it's off. Moved inside
                        // this "will actually upload" branch in review_loop_iteration 1 (Verification
                        // Gap finding): a silent chunk never reaches enqueueSupabaseUpload at all
                        // (see the branch above), so capturing here unconditionally used to leave a
                        // sidecar .txt permanently orphaned on local storage for every silent chunk —
                        // this branch is the one place both capture and enqueue always happen
                        // together. Still called BEFORE enqueueSupabaseUpload so the worker sees the
                        // sidecar file if/when it runs — this call is itself suspend/sequential in
                        // this same coroutine, so "before" here also means "awaited before", not just
                        // "earlier in the source". The whole thing (preference read included) is
                        // inside a single runCatching — defense-in-depth on top of
                        // LocationCaptureManager's own internal try/catch, per the story's boundary:
                        // a failure here (even a DataStore read hiccup) must never block the
                        // Supabase enqueue call.
                        runCatching {
                            if (appPreferencesRepository.locationCaptureEnabled.first()) {
                                val captured = locationCaptureManager.captureLocationSidecar(applicationContext, file)
                                // review_loop_iteration 1 (Blind Hunter finding): capture-step
                                // outcomes were previously visible only in Logcat (Log.w on
                                // exception) — appEventLogger is what actually reaches the in-app
                                // Logs screen. A `false` return isn't necessarily an error (no
                                // permission / no fix / stale fix are all normal, expected skips per
                                // LocationCaptureManager's own I/O matrix), so this is UPLOAD-category
                                // informational, not a warning.
                                appEventLogger.log(
                                    LogCategory.UPLOAD,
                                    if (captured) {
                                        "Location captured (id=$chunkId)"
                                    } else {
                                        "Location not captured (id=$chunkId) — no permission, no recent fix, or write failed"
                                    }
                                )
                            }
                        }.onFailure { Log.w(TAG, "Location capture failed for chunk $chunkId", it) }

                        runCatching { enqueueSupabaseUpload(chunkId, sessionId) }
                            .onFailure { Log.w(TAG, "Failed to enqueue SupabaseUploadWorker for chunk $chunkId", it) }
                    }
                } else {
                    appEventLogger.log(
                        LogCategory.RECORDING,
                        "Chunk finalize failed: invalid chunkId=$chunkId sessionId=$sessionId"
                    )
                }
            }
        }
        recorder.onChunkStarted = { filePath ->
            // am3-5: this must complete before AudioRecorderManager's start() call (invoked
            // immediately after this callback returns — see startNewChunk) actually captures any
            // audio, otherwise the race this story exists to close (a chunk recording with no
            // Room row at all, producing an untraceable corrupted .m4a on a kill) stays open.
            // Blocking mirrors the same correctness-over-async tradeoff already established for
            // setRecordingShouldBeActive below (am3-2 code review, CRITICAL finding).
            val chunkId = runBlocking { sessionStateManager.markChunkStarted(filePath) }
            if (chunkId <= 0L) {
                // (code review, am3-5, patch 6): mirrors the explicit invalid-id logging already
                // done around onChunkCompleted below — shouldn't happen (markChunkStarted only
                // returns <= 0L with no active session), but silently discarding it here would
                // hide exactly the "no active session" edge case a reviewer would want visible.
                Log.w(TAG, "markChunkStarted returned invalid id=$chunkId for $filePath (no active session?)")
            }
        }
        recorder.onStorageLow = { handleLowStorage() }
        recorder.onHardwareError = { handleHardwareError() }

        interruptionManager = AudioInterruptionManager(
            context = this,
            onSourceChanged = { name -> handleSourceChanged(name) }
        )

        silenceDetector = SilenceDetector(
            context = this,
            amplitude = recorder.amplitude,
            // am-hotfix (owner request, TCK-20260817154948-6428, 2026-08-19): the "No audio
            // detected — check microphone" push notification was correctly firing during a real
            // silence condition (e.g. the mic capturing near-nothing during a WhatsApp call, a
            // documented OS-level restriction — see the kabbalah repo's
            // wiki/ledger/decisao-de-produto/audiomemo-gravacao-nunca-para-por-escolha-propria.md,
            // not something that lives inside this AudioMemo repo) but the owner doesn't want to
            // be interrupted by it. Detection itself keeps running unchanged — it now logs
            // quietly instead of pushing a notification, same pattern as every sibling
            // interruption handler below (handleSourceChanged/handleBatteryLow/etc. all log even
            // when they don't notify). am4-2's separate silent-chunk-upload-skip feature reads a
            // different amplitude signal entirely (AudioRecorderManager.SILENCE_AMPLITUDE_THRESHOLD)
            // and is untouched by this.
            onSilenceDetected = { handleSilenceDetected() },
            onPermissionRevoked = { handlePermissionRevoked() }
        )

        batteryGuard = BatteryGuard(
            context = this,
            onBatteryLow = { handleBatteryLow() }
        )

        mediaButtonHandler = MediaButtonHandler(
            context = this,
            onToggle    = { handleMediaButtonToggle() },
            onPauseOnly = { if (recorder.isRecording) handleMediaButtonPause() },
            onPlayOnly  = { if (isMediaButtonPaused) handleMediaButtonPlay() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecordingCleanly()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                // Clear any user-initiated media-button pause so handleInterruptionResume resumes normally.
                isMediaButtonPaused = false
                handleInterruptionResume()
                return START_NOT_STICKY
            }
        }

        // ── Guard against duplicate starts ─────────────────────────────────────
        // TranscriptScreen may call startForegroundService more than once (e.g. back-stack
        // manipulation). Skip re-initialization if recording is already in progress.
        if (isRecordingActive) return START_STICKY

        // ── Start recording ────────────────────────────────────────────────────
        isRecordingActive = true
        appEventLogger.log(LogCategory.RECORDING, "Recording started")
        NotificationHelper.createNotificationChannel(this)
        startForeground(
            NotificationHelper.NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, stopPendingIntent())
        )

        // am3-5: must complete — and currentSessionId must be set — before recorder.startRecording()
        // runs below. onChunkStarted fires synchronously inside startRecording() (for the
        // session's very first chunk) and needs a valid currentSessionId to create that chunk's
        // RECORDING row. The previous fire-and-forget serviceScope.launch here raced against that
        // first callback and would very likely lose it (a Dispatchers.IO thread-hop vs. an
        // immediate same-thread call a few lines below), silently skipping the Room row for
        // exactly the chunk this story cares about most. Blocking mirrors the same
        // correctness-over-async tradeoff already established for setRecordingShouldBeActive
        // just below (am3-2 code review, CRITICAL finding).
        val sessionId = runBlocking { sessionStateManager.startSession() }
        _currentSessionId.value = sessionId
        enqueueFinalizationWorker(sessionId)

        // am3-2 (FR2): record the owner's intent so RecordingWatchdogWorker (and, post-reboot,
        // the am3-3 boot receiver) know recording should be running. Only ever flipped back to
        // false by an explicit stop (stopRecordingCleanly) — never by an always-on mechanism.
        // Blocking on purpose (code review, am3-2): a fire-and-forget serviceScope.launch here
        // raced against later teardown on the stop path; a single DataStore edit is fast, and
        // correctness (the write actually lands before we move on) matters more than the tiny
        // blocking cost at start.
        runBlocking { appPreferencesRepository.setRecordingShouldBeActive(true) }

        // am3-2 (FR2): lightweight liveness ticker, independent of the 100ms amplitude loop.
        // Written immediately, then every HEARTBEAT_INTERVAL_MS, so the watchdog can tell a
        // healthy session apart from one whose process died without onDestroy ever running.
        // Cancel any previous job first (code review, am3-2): defensive-only today (the
        // duplicate-start guard above already prevents re-entry here), but cheap insurance
        // against a leaked/duplicated ticker if that guard ever changes.
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (true) {
                // A single failed write (e.g. a transient DataStore IOException) must never
                // permanently kill the ticker for the rest of the session — that would starve
                // the watchdog of heartbeats and cause it to spuriously restart a perfectly
                // healthy service every 15 minutes (code review, am3-2). CancellationException
                // is deliberately rethrown, never swallowed here — this loop must still stop
                // promptly when serviceScope.cancel() fires in onDestroy.
                try {
                    appPreferencesRepository.recordHeartbeat()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record heartbeat, will retry next tick", e)
                }
                delay(HEARTBEAT_INTERVAL_MS)

                // (am-hotfix, never-stop-recording): the sentinel now also actively checks
                // recording health every tick, not just writing a liveness timestamp — deferred
                // until AFTER the first delay (never on the very first iteration) so it can never
                // race the recorder.startRecording() call a few lines below and false-positive at
                // startup, before recording has actually begun. If the recorder has silently
                // stopped while it should still be active (a hardware failure the async
                // `setOnErrorListener` missed reporting, for whatever reason), self-heal
                // immediately by reusing the exact same recovery path as a reported hardware
                // error, instead of waiting up to 15 minutes for RecordingWatchdogWorker.
                if (isRecordingUnhealthy(_isStopped.value, isMediaButtonPaused, recorder.isRecording)) {
                    appEventLogger.log(
                        LogCategory.INTERRUPTION,
                        "Sentinel detected unhealthy recording — self-healing immediately"
                    )
                    handleHardwareError()
                }
            }
        }

        recorder.startRecording()
        interruptionManager.start()
        silenceDetector.start()
        batteryGuard.start()
        mediaButtonHandler.start()

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        if (!_isStopped.value) {
            recorder.stopRecording()
            silenceDetector.stop()
            interruptionManager.stop()
            batteryGuard.stop()
            serviceScope.launch { sessionStateManager.pauseSession() }
        }
        mediaButtonHandler.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Interruption handlers ──────────────────────────────────────────────────
    // (am-hotfix, never-stop-recording): handleInterruptionPause was removed entirely —
    // AudioInterruptionManager no longer has any pause trigger to call it (audio focus / mic
    // mute / phone call are no longer intentional-pause reasons; see that class's KDoc).
    // handleInterruptionResume stays: it's still the target of the "Resume" action on the
    // media-button-pause notification (ACTION_RESUME → here), an unrelated, still-existing
    // feature.

    /**
     * (code review patch 10): the old `if (isMediaButtonPaused) { ... }` early-return branch here
     * was removed as dead code — this function's only caller (`ACTION_RESUME` in
     * [onStartCommand]) unconditionally sets `isMediaButtonPaused = false` immediately before
     * calling it, so that branch could never actually run. (It dated from when
     * `AudioInterruptionManager`'s now-removed pause/resume callbacks could also reach this
     * function without having cleared the flag first — see that class's KDoc.)
     */
    private fun handleInterruptionResume() {
        if (_isStopped.value) return
        appEventLogger.log(LogCategory.INTERRUPTION, "Recording resumed")
        recorder.resumeRecording()
        silenceDetector.reset()
        silenceDetector.start()
        serviceScope.launch { sessionStateManager.resumeSession() }
        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildForegroundNotification(this, stopPendingIntent())
        )
    }

    // ── Media button handlers ──────────────────────────────────────────────────

    private fun handleMediaButtonToggle() {
        if (_isStopped.value) return
        if (recorder.isRecording) handleMediaButtonPause() else handleMediaButtonPlay()
    }

    private fun handleMediaButtonPause() {
        if (_isStopped.value || !recorder.isRecording) return
        isMediaButtonPaused = true
        recorder.pauseRecording()
        silenceDetector.stop()
        serviceScope.launch { sessionStateManager.pauseSession() }
        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildPausedMediaButtonNotification(
                this, resumePendingIntent(), stopPendingIntent()
            )
        )
    }

    private fun handleMediaButtonPlay() {
        if (_isStopped.value || !isMediaButtonPaused) return
        isMediaButtonPaused = false
        // (code review patch 10, comment fix): defensive check only, not a real race with any
        // other pause mechanism — audio-focus/mic-mute/phone-call are no longer intentional-pause
        // reasons at all (see AudioInterruptionManager's KDoc), so this is purely the media-button
        // pause's own resume, guarding against a redundant resumeRecording() call.
        if (!recorder.isRecording) {
            recorder.resumeRecording()
            silenceDetector.reset()
            silenceDetector.start()
            serviceScope.launch { sessionStateManager.resumeSession() }
            NotificationHelper.updateNotification(
                this,
                NotificationHelper.buildForegroundNotification(this, stopPendingIntent())
            )
        }
    }

    private fun handleSourceChanged(sourceName: String) {
        if (_isStopped.value) return
        appEventLogger.log(LogCategory.INTERRUPTION, "Audio source changed to $sourceName")
        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildMicSourceChangedNotification(this, sourceName, stopPendingIntent())
        )
        serviceScope.launch {
            kotlinx.coroutines.delay(3_000)
            if (!_isStopped.value) {
                NotificationHelper.updateNotification(
                    this@AudioRecordingService,
                    NotificationHelper.buildForegroundNotification(
                        this@AudioRecordingService, stopPendingIntent()
                    )
                )
            }
        }
    }

    /**
     * (am-hotfix, never-stop-recording): battery level is purely an app-policy decision — the
     * Android system never kills a foreground service just because the battery is low (confirmed
     * in this story's Problem statement). Recording continues uninterrupted; this handler is now
     * observability-only. [BatteryGuard] itself is unchanged (still detects the same conditions —
     * only what happens *after* detection changed, per the story's Code Map).
     */
    private fun handleBatteryLow() {
        appEventLogger.log(LogCategory.INTERRUPTION, BATTERY_LOW_CONTINUES_MESSAGE)
    }

    /**
     * (am-hotfix, owner request, TCK-20260817154948-6428): a sustained silence period (the mic
     * capturing near-nothing for 10+ seconds — e.g. during a WhatsApp/phone call, a documented
     * OS-level restriction, see the kabbalah repo's `wiki/ledger/decisao-de-produto/
     * audiomemo-gravacao-nunca-para-por-escolha-propria.md`) used to push a "No audio detected"
     * notification. The owner asked to stop being interrupted by it — this handler is now
     * observability-only, same shape as [handleBatteryLow] right above: [SilenceDetector] itself
     * is unchanged (still detects the same condition on the same schedule), only what happens
     * after detection changed. Recording is never paused/stopped by this either way.
     */
    private fun handleSilenceDetected() {
        if (_isStopped.value) return
        appEventLogger.log(LogCategory.INTERRUPTION, "No audio detected for 10+ seconds")
    }

    /**
     * Shared "genuine stop" tail (am-hotfix, never-stop-recording) for the stop reasons that
     * remain real Android restrictions: low storage, permission revoked, and hardware error
     * exhausted after immediate-recovery attempts. Always shows [notification] on
     * [NotificationHelper.ALERT_CHANNEL_ID] (high priority, distinct sound) — never the silent
     * recording channel — so the owner actually notices when recording genuinely stopped.
     *
     * [resolveLostChunkForSessionId] mirrors handleHardwareError's pre-existing CRITICAL fix
     * (code review, am3-5, patch 1): pass a valid sessionId only for the hardware-error path,
     * where a chunk's Room row can be stuck `RECORDING` if `onChunkStarted` fired right before
     * `MediaRecorder.start()` failed — must be resolved to `FAILED` before the session below is
     * marked `STOPPED`, or no sweep could ever reach it again.
     *
     * Idempotency guard (code review patch 8): the hardware-error path is now deliberately slower
     * (waits through the full retry loop before ever reaching here), which widens the window for
     * two concurrent triggers (e.g. low-storage AND permission-revoked close together) to both
     * reach this function — without the guard, both would double-run the stop/notification/
     * `stopSelf()` sequence.
     */
    private fun stopServiceWithAlert(notification: Notification, resolveLostChunkForSessionId: Long? = null) {
        if (_isStopped.value) return
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            if (resolveLostChunkForSessionId != null && resolveLostChunkForSessionId > 0L) {
                ChunkFinalizationWorker.resolveLostChunks(chunkDao, appEventLogger, resolveLostChunkForSessionId)
            }
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        _isStopped.value = true

        NotificationHelper.updateNotification(this, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun handleLowStorage() {
        appEventLogger.log(LogCategory.INTERRUPTION, LOW_STORAGE_STOPPED_MESSAGE)
        recorder.stopRecording()
        stopServiceWithAlert(NotificationHelper.buildLowStorageNotification(this))
    }

    private fun handlePermissionRevoked() {
        appEventLogger.log(LogCategory.INTERRUPTION, PERMISSION_REVOKED_STOPPED_MESSAGE)
        recorder.stopRecording()
        stopServiceWithAlert(NotificationHelper.buildPermissionRevokedNotification(this))
    }

    /**
     * (am-hotfix, never-stop-recording): a hardware error is no longer an immediate stop. It
     * first tries [attemptImmediateHardwareRecovery] — a short, bounded retry loop reusing
     * [AudioRecorderManager.attemptImmediateRecovery] — and only falls back to a real stop +
     * alert notification once that loop is exhausted. [RecordingWatchdogWorker] (15 min) remains
     * the final safety net for whatever this immediate path can't recover from (e.g. the process
     * itself gets killed mid-recovery) — this is a faster COMPLEMENT, not a replacement.
     *
     * [isRecoveringFromHardwareError] (via [runGuarded]) guards re-entrancy: the async
     * `MediaRecorder.setOnErrorListener` (or the sentinel below) can call this again while a
     * recovery attempt is already in flight — that must never start a second overlapping loop,
     * and (code review patch 4) the guard only releases once this ENTIRE flow — recovery attempts
     * AND the fallback stop, if it happens — has fully completed.
     */
    private fun handleHardwareError() {
        serviceScope.launch {
            runGuarded(isRecoveringFromHardwareError) {
                appEventLogger.log(
                    LogCategory.INTERRUPTION,
                    "Hardware error detected — attempting immediate recovery"
                )
                when (attemptImmediateHardwareRecovery()) {
                    HardwareRecoveryResult.RECOVERED -> {
                        appEventLogger.log(
                            LogCategory.RECORDING,
                            "Recovered from hardware error — recording resumed"
                        )
                    }
                    HardwareRecoveryResult.STORAGE_INSUFFICIENT -> {
                        // (code review patch 9): the recovery loop's last attempt failed
                        // specifically because storage ran out, not because of a genuine hardware
                        // fault — surface that distinction (a low-storage alert, not "microphone
                        // error") and reuse LOW_STORAGE_STOPPED_MESSAGE so this classifies
                        // identically to the direct low-storage path in HomeViewModel.
                        appEventLogger.log(
                            LogCategory.INTERRUPTION,
                            "Hardware error recovery aborted: storage ran out mid-recovery"
                        )
                        appEventLogger.log(LogCategory.INTERRUPTION, LOW_STORAGE_STOPPED_MESSAGE)
                        // (code review, am3-5, patch 1 — CRITICAL): resolveLostChunkForSessionId
                        // still applies here — an in-flight chunk's Room row can be stuck
                        // RECORDING regardless of which specific reason recovery failed for.
                        stopServiceWithAlert(
                            NotificationHelper.buildLowStorageNotification(this@AudioRecordingService),
                            resolveLostChunkForSessionId = sessionStateManager.currentSessionId
                        )
                    }
                    HardwareRecoveryResult.EXHAUSTED -> {
                        appEventLogger.log(LogCategory.INTERRUPTION, hardwareErrorStoppedMessage())
                        // (code review, am3-5, patch 1 — CRITICAL): this path deliberately never
                        // calls recorder.stopRecording() (the recorder may already be broken) —
                        // it never routes through AudioRecorderManager.finaliseCurrentChunk(),
                        // unlike every other stop handler in this class.
                        stopServiceWithAlert(
                            NotificationHelper.buildHardwareErrorNotification(this@AudioRecordingService),
                            resolveLostChunkForSessionId = sessionStateManager.currentSessionId
                        )
                    }
                }
            }
        }
    }

    /**
     * Thin instance wrapper around [runHardwareRecoveryLoop], supplying this service's real
     * [AudioRecorderManager.attemptImmediateRecovery] as the attempt function.
     */
    private suspend fun attemptImmediateHardwareRecovery(): HardwareRecoveryResult =
        runHardwareRecoveryLoop(
            maxAttempts = HARDWARE_RECOVERY_MAX_ATTEMPTS,
            retryDelayMs = HARDWARE_RECOVERY_RETRY_DELAY_MS,
            attempt = { recorder.attemptImmediateRecovery() }
        )

    /** Normal stop via user action (Stop button or ACTION_STOP intent). */
    private fun stopRecordingCleanly() {
        if (_isStopped.value) return
        _isStopped.value = true
        appEventLogger.log(LogCategory.RECORDING, "Recording stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        recorder.stopRecording()
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        mediaButtonHandler.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        // am3-2 (FR2): this is the only place the owner's "should be recording" intent is
        // cleared — the explicit stop path (button press or ACTION_STOP intent, which routes
        // here). Automatic safety stops (battery/storage/permission/hardware) deliberately leave
        // the intent as-is, so the watchdog can resume recording once the transient condition
        // clears — only an explicit stop counts as "the owner doesn't want this running anymore".
        // Blocking on purpose (code review, am3-2, CRITICAL): this used to be a fire-and-forget
        // serviceScope.launch racing against stopSelf()/onDestroy's serviceScope.cancel() right
        // below — a real race where the write could get cancelled before it landed, leaving
        // recordingShouldBeActive stuck true after an explicit stop and letting the watchdog
        // wrongly resurrect a session the owner explicitly stopped. Must complete before
        // stopSelf() is called, not concurrently with it.
        runBlocking { appPreferencesRepository.setRecordingShouldBeActive(false) }
        stopSelf()
    }

    // ── WorkManager helpers ────────────────────────────────────────────────────

    private fun enqueueFinalizationWorker(sessionId: Long) {
        if (sessionId <= 0L) return
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "${ChunkFinalizationWorker.WORK_NAME_PREFIX}$sessionId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ChunkFinalizationWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(ChunkFinalizationWorker.KEY_SESSION_ID, sessionId)
                        .build()
                )
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
        )
    }

    private fun cancelFinalizationWorker(sessionId: Long) {
        if (sessionId <= 0L) return
        WorkManager.getInstance(applicationContext)
            .cancelUniqueWork("${ChunkFinalizationWorker.WORK_NAME_PREFIX}$sessionId")
    }

    private fun enqueueChunkUpload(chunkId: Long, sessionId: Long) {
        if (chunkId <= 0L || sessionId <= 0L) {
            appEventLogger.log(
                LogCategory.UPLOAD,
                "Whisper upload not enqueued: invalid chunkId=$chunkId sessionId=$sessionId"
            )
            return
        }
        appEventLogger.log(LogCategory.UPLOAD, "Whisper upload enqueued (chunk=$chunkId)")
        val constraints = UploadPreferences.networkConstraints(applicationContext)
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "${WhisperUploadWorker.WORK_NAME_PREFIX}$chunkId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<WhisperUploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        WhisperUploadWorker.KEY_CHUNK_ID to chunkId,
                        WhisperUploadWorker.KEY_SESSION_ID to sessionId
                    )
                )
                .build()
        )
    }

    /**
     * Enqueues [SupabaseUploadWorker] for a finished chunk. Unlike [enqueueChunkUpload] (which
     * respects the Wi-Fi-only [UploadPreferences] toggle for Whisper), this is always constrained
     * to [NetworkType.CONNECTED] — never Wi-Fi-only — a deliberate FR5 decision, independent of
     * the user's Whisper upload preference.
     */
    private fun enqueueSupabaseUpload(chunkId: Long, sessionId: Long) {
        if (chunkId <= 0L || sessionId <= 0L) {
            appEventLogger.log(
                LogCategory.UPLOAD,
                "Supabase upload not enqueued: invalid chunkId=$chunkId sessionId=$sessionId"
            )
            return
        }
        appEventLogger.log(LogCategory.UPLOAD, "Supabase upload enqueued (chunk=$chunkId)")
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            supabaseUploadWorkName(chunkId),
            ExistingWorkPolicy.KEEP,
            buildSupabaseUploadWorkRequest(chunkId, sessionId)
        )
    }

    private suspend fun enqueueTranscriptionChain(sessionId: Long) {
        if (sessionId <= 0L) return

        // Enqueue any remaining PENDING chunks that weren't already uploaded live.
        // KEEP policy ensures we don't create duplicate workers for chunks already in flight.
        val pendingChunks = chunkDao.getChunksForSessionOnce(sessionId)
            .filter { it.status == ChunkStatus.PENDING }
        pendingChunks.forEach { chunk -> enqueueChunkUpload(chunk.id, chunk.sessionId) }

        // Enqueue summary independently with a delay so uploads can complete.
        // SummaryGenerationWorker will retry if uploads are still in progress.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "${SummaryGenerationWorker.WORK_NAME_PREFIX}$sessionId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SummaryGenerationWorker>()
                .setInputData(workDataOf(SummaryGenerationWorker.KEY_SESSION_ID to sessionId))
                .setInitialDelay(20, TimeUnit.SECONDS)
                .addTag("${SummaryGenerationWorker.WORK_NAME_PREFIX}$sessionId")
                .build()
        )
    }
}

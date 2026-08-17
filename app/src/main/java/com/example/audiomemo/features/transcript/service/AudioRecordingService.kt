package com.example.audiomemo.features.transcript.service

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class AudioRecordingService : Service() {

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

    /** True while the service is actively recording (not stopped). Used to reject duplicate starts. */
    private var isRecordingActive = false
    /** True when the user has manually paused via a headset/media button. */
    private var isMediaButtonPaused = false

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

        recorder.onChunkCompleted = { file ->
            lastChunkSaveJob = serviceScope.launch {
                val chunkId = sessionStateManager.saveChunk(file.absolutePath)
                val sessionId = sessionStateManager.currentSessionId
                if (chunkId > 0L && sessionId > 0L) {
                    appEventLogger.log(LogCategory.RECORDING, "Chunk finalized (id=$chunkId)")
                    // Each enqueue is isolated: a failure enqueuing one worker (e.g. WorkManager
                    // internals throwing) must never prevent the other from running — they are
                    // independent upload pipelines (Whisper vs Supabase) for the same chunk.
                    runCatching { enqueueChunkUpload(chunkId, sessionId) }
                        .onFailure { Log.w(TAG, "Failed to enqueue WhisperUploadWorker for chunk $chunkId", it) }
                    runCatching { enqueueSupabaseUpload(chunkId, sessionId) }
                        .onFailure { Log.w(TAG, "Failed to enqueue SupabaseUploadWorker for chunk $chunkId", it) }
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
            onPauseRequested = { reason -> handleInterruptionPause(reason) },
            onResumeRequested = { handleInterruptionResume() },
            onSourceChanged = { name -> handleSourceChanged(name) }
        )

        silenceDetector = SilenceDetector(
            context = this,
            amplitude = recorder.amplitude,
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

    private fun handleInterruptionPause(reason: AudioInterruptionManager.PauseReason) {
        if (_isStopped.value) return
        appEventLogger.log(LogCategory.INTERRUPTION, "Recording paused: $reason")
        recorder.pauseRecording()
        silenceDetector.stop()
        serviceScope.launch { sessionStateManager.pauseSession() }

        val notification = when (reason) {
            AudioInterruptionManager.PauseReason.PHONE_CALL ->
                NotificationHelper.buildPausedPhoneCallNotification(
                    this, resumePendingIntent(), stopPendingIntent()
                )
            AudioInterruptionManager.PauseReason.AUDIO_FOCUS ->
                NotificationHelper.buildPausedAudioFocusNotification(
                    this, resumePendingIntent(), stopPendingIntent()
                )
            AudioInterruptionManager.PauseReason.MIC_MUTED ->
                NotificationHelper.buildPausedMicMutedNotification(
                    this, resumePendingIntent(), stopPendingIntent()
                )
        }
        NotificationHelper.updateNotification(this, notification)
    }

    private fun handleInterruptionResume() {
        if (_isStopped.value) return
        // If the user has manually paused via headset button, keep the recorder paused
        // and show the media-button pause notification instead of resuming.
        if (isMediaButtonPaused) {
            appEventLogger.log(
                LogCategory.INTERRUPTION,
                "Interruption cleared, but recording stays paused (media button)"
            )
            NotificationHelper.updateNotification(
                this,
                NotificationHelper.buildPausedMediaButtonNotification(
                    this, resumePendingIntent(), stopPendingIntent()
                )
            )
            return
        }
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
        // Only physically resume the recorder if no other interruption (call/focus/mic) is
        // still active. If one is, handleInterruptionResume will resume when it clears.
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

    private fun handleSilenceDetected() {
        if (_isStopped.value) return
        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildSilenceWarningNotification(this, stopPendingIntent())
        )
    }

    private fun handleBatteryLow() {
        appEventLogger.log(LogCategory.INTERRUPTION, "Battery low — recording stopped")
        recorder.stopRecording()
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        _isStopped.value = true

        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildBatteryLowNotification(this)
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun handleLowStorage() {
        appEventLogger.log(LogCategory.INTERRUPTION, "Low storage — recording stopped")
        recorder.stopRecording()
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        _isStopped.value = true

        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildLowStorageNotification(this)
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun handlePermissionRevoked() {
        appEventLogger.log(LogCategory.INTERRUPTION, "Permission revoked — recording stopped")
        recorder.stopRecording()
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        _isStopped.value = true

        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildPermissionRevokedNotification(this)
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun handleHardwareError() {
        appEventLogger.log(LogCategory.INTERRUPTION, "Hardware error — recording stopped")
        silenceDetector.stop()
        interruptionManager.stop()
        batteryGuard.stop()
        val sessionId = sessionStateManager.currentSessionId
        val savedChunkJob = lastChunkSaveJob
        serviceScope.launch {
            savedChunkJob?.join()
            // (code review, am3-5, patch 1 — CRITICAL): this path deliberately never calls
            // recorder.stopRecording() (the recorder may already be broken) — it never routes
            // through AudioRecorderManager.finaliseCurrentChunk(), unlike every other stop
            // handler in this class. If onChunkStarted already created a RECORDING row for the
            // in-flight chunk right before MediaRecorder.start() failed, that row must be
            // resolved to FAILED HERE, before the session below is marked STOPPED and its
            // finalization worker cancelled — after that, no sweep could ever reach it again:
            // ChunkFinalizationWorker.doWork() early-returns once session.state == STOPPED, the
            // worker itself is about to be cancelled, and the heartbeat would likely still read
            // fresh at this exact moment anyway (the service was alive and ticking right up
            // until the error). Reuses the exact same resolution logic the worker's own sweep
            // uses (see ChunkFinalizationWorker.resolveLostChunks's KDoc), not a duplicate copy.
            if (sessionId > 0L) {
                ChunkFinalizationWorker.resolveLostChunks(chunkDao, appEventLogger, sessionId)
            }
            sessionStateManager.stopSession()
            cancelFinalizationWorker(sessionId)
            enqueueTranscriptionChain(sessionId)
        }
        _isStopped.value = true

        NotificationHelper.updateNotification(
            this,
            NotificationHelper.buildHardwareErrorNotification(this)
        )
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

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

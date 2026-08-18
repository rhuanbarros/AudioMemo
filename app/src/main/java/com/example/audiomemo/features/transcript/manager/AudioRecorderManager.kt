package com.example.audiomemo.features.transcript.manager

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioRecorderManager(private val context: Context) {

    /**
     * Outcome of [evaluateChunkAmplitude] for a just-finished chunk (am4-2, FR9). Deliberately
     * nested directly on the class, never inside `companion object` — a class nested inside a
     * companion object can only be referenced from other files as `AudioRecorderManager.Companion.
     * ChunkAmplitudeOutcome`, not the shorter `AudioRecorderManager.ChunkAmplitudeOutcome` this
     * type's callers (e.g. `AudioRecordingService`) use, since it's part of the public
     * [onChunkCompleted] callback's signature.
     */
    enum class ChunkAmplitudeOutcome {
        /** Max amplitude observed stayed below [SILENCE_AMPLITUDE_THRESHOLD] the whole chunk. */
        SILENT,
        /** Max amplitude observed reached [SILENCE_AMPLITUDE_THRESHOLD] at some point. */
        AUDIBLE,
        /**
         * Max amplitude observed was `0` for the whole chunk — some devices/emulators don't
         * support `getMaxAmplitude()` and always report `0`. Treated distinctly from [SILENT] on
         * purpose: a `0` reading means "couldn't measure", not "measured and it was quiet", and
         * must never be treated as silence (would risk discarding real audio purely because of a
         * hardware/emulator limitation — see the story's I/O matrix).
         */
        UNMEASURED
    }

    companion object {
        /** Target duration of a normal (non-interrupted) recording chunk. */
        val CHUNK_DURATION_MS = TimeUnit.MINUTES.toMillis(2)

        /** How often storage is re-checked while waiting for the next chunk rotation. */
        val STORAGE_CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15)

        /**
         * Minimum `MediaRecorder.getMaxAmplitude()` (0-32767 scale) a chunk must reach at least
         * once during its whole recording to be considered to contain real audio (am4-2, FR9).
         *
         * Starting value per the spec-gate's "Ask First" resolution (2026-08-17): deliberately
         * conservative/low to minimize the risk of discarding real quiet speech. Meant to be
         * validated empirically on a real device during this story's manual verification — if
         * that check shows it's wrong in either direction (discarding audible speech, or letting
         * obvious silence through), adjust this constant and record the final choice in the story's
         * Dev Agent Record rather than converging blindly on the starting value.
         *
         * **Deliberately independent of [SilenceDetector.SILENCE_THRESHOLD]** (code review, am4-2,
         * patch 4): both read the same underlying `MediaRecorder.getMaxAmplitude()` signal but
         * serve different purposes — this one decides whether a whole *finished* chunk skips
         * Supabase upload (FR9/FR10); `SilenceDetector`'s drives a live, user-facing "No audio
         * detected" warning *during* recording. Different values, not a bug — never unify them
         * without a separate, deliberate design decision.
         */
        const val SILENCE_AMPLITUDE_THRESHOLD = 300

        /**
         * Pure decision for what a chunk's observed max amplitude means (am4-2, FR9/FR10).
         * `Context`/`MediaRecorder`-free so it's unit-testable from plain-JVM `src/test`, mirroring
         * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker.decideUploadOutcome].
         */
        internal fun evaluateChunkAmplitude(
            maxAmplitudeObserved: Int,
            threshold: Int = SILENCE_AMPLITUDE_THRESHOLD
        ): ChunkAmplitudeOutcome = when {
            maxAmplitudeObserved <= 0 -> ChunkAmplitudeOutcome.UNMEASURED
            maxAmplitudeObserved < threshold -> ChunkAmplitudeOutcome.SILENT
            else -> ChunkAmplitudeOutcome.AUDIBLE
        }
    }

    private var mediaRecorder: MediaRecorder? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private var amplitudeJob: Job? = null
    private var chunkJob: Job? = null

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private val _lastChunkFile = MutableStateFlow<File?>(null)
    val lastChunkFile: StateFlow<File?> = _lastChunkFile.asStateFlow()

    private var currentOutputFile: File? = null

    /**
     * Max `MediaRecorder.getMaxAmplitude()` observed so far for the chunk currently recording
     * (am4-2, FR9) — reset to `0` every time a new chunk starts, read (and evaluated via
     * [evaluateChunkAmplitude]) right when that chunk finishes. Updated by the same 100ms poll
     * loop [amplitudeJob] already runs for [_amplitude], never a second/duplicate polling loop.
     *
     * `AtomicInteger`, not a plain `Int` (code review, am4-2, patch 1 — CRITICAL): [scope] is
     * `Dispatchers.IO`, a multi-threaded elastic pool, not a single confined thread.
     * [finaliseCurrentChunk] (which reads this) runs on genuinely different OS threads depending
     * on caller — synchronously on the **main thread** for [pauseRecording]/[stopRecording]
     * (invoked inline from `AudioRecordingService`'s interruption/media-button handlers), and from
     * [chunkJob], a *separate* `scope.launch` coroutine that can land on a different IO thread than
     * [amplitudeJob] (the writer). A plain unsynchronized `Int` here is a real JMM visibility/race
     * risk — a stale or lost write could misclassify an audible chunk as [ChunkAmplitudeOutcome.SILENT]
     * or vice versa, directly undermining this story's purpose.
     */
    private val maxAmplitudeInChunk = AtomicInteger(0)

    /**
     * True while the recorder is actively capturing audio (false when paused or stopped).
     *
     * `@Volatile` (code review, am-hotfix never-stop-recording, patch 1/6): written from the
     * async `MediaRecorder.setOnErrorListener` callback thread AND from
     * `AudioRecordingService`'s `serviceScope` (`Dispatchers.IO`) coroutine that drives the
     * immediate-recovery loop, but read by the heartbeat sentinel running on yet another
     * coroutine on the same dispatcher — without `@Volatile` a write on one thread isn't
     * guaranteed visible to a read on another (JMM), which would let the sentinel miss exactly
     * the "recorder silently stopped" case it exists to catch.
     */
    @Volatile var isRecording: Boolean = false
        private set

    /**
     * Called whenever a chunk is completed (rotation or pause/stop), with that chunk's
     * [ChunkAmplitudeOutcome] (am4-2, FR9) so the caller can decide whether to skip the upload.
     */
    var onChunkCompleted: ((file: File, amplitudeOutcome: ChunkAmplitudeOutcome) -> Unit)? = null

    /**
     * Called right before the underlying `MediaRecorder.start()` for a new chunk (am3-5) — lets
     * the caller record a "recording started" trace (a Room row in `RECORDING`) before any audio
     * is actually captured, so a chunk killed mid-recording is never entirely untraceable.
     */
    var onChunkStarted: ((filePath: String) -> Unit)? = null

    /** Called when storage drops below the minimum threshold during recording. */
    var onStorageLow: (() -> Unit)? = null

    /** Called when MediaRecorder reports a hardware or server error. */
    var onHardwareError: (() -> Unit)? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun startRecording() {
        if (!StorageGuard.hasEnoughStorage(context.filesDir)) {
            onStorageLow?.invoke()
            return
        }
        isRecording = true
        startNewChunk()
        startMonitoringJobs()
    }

    fun pauseRecording() {
        isRecording = false
        cancelMonitoringJobs()
        finaliseCurrentChunk(label = "paused")
        _amplitude.value = 0
    }

    fun resumeRecording() {
        if (!StorageGuard.hasEnoughStorage(context.filesDir)) {
            onStorageLow?.invoke()
            return
        }
        isRecording = true
        startNewChunk()
        startMonitoringJobs()
    }

    fun stopRecording() {
        isRecording = false
        cancelMonitoringJobs()
        finaliseCurrentChunk(label = "final")
        _amplitude.value = 0
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private fun startMonitoringJobs() {
        amplitudeJob = scope.launch {
            while (isActive) {
                val current = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (_: RuntimeException) {
                    0
                }
                _amplitude.value = current
                // am4-2 (FR9): reuse this existing 100ms poll to also track the current chunk's
                // max amplitude, instead of a second/duplicate polling loop. updateAndGet (not a
                // plain read-then-write) so this stays correct even if ever called concurrently.
                maxAmplitudeInChunk.updateAndGet { existing -> maxOf(existing, current) }
                delay(100)
            }
        }
        chunkJob = scope.launch {
            var elapsed = 0L
            while (isActive) {
                delay(STORAGE_CHECK_INTERVAL_MS)
                elapsed += STORAGE_CHECK_INTERVAL_MS
                if (!isActive) break
                if (!StorageGuard.hasEnoughStorage(context.filesDir)) {
                    onStorageLow?.invoke()
                    break
                }
                if (elapsed >= CHUNK_DURATION_MS) {
                    elapsed = 0L
                    startNewChunk()
                }
            }
        }
    }

    private fun cancelMonitoringJobs() {
        amplitudeJob?.cancel()
        chunkJob?.cancel()
        amplitudeJob = null
        chunkJob = null
    }

    /**
     * Creates and starts a brand-new chunk's `MediaRecorder`. Returns `true` once `start()`
     * returns without throwing (recording is healthy), `false` on any failure.
     *
     * [notifyOnHardwareError] (am-hotfix, never-stop-recording): defaults to `true` for every
     * normal caller (chunk rotation, [startRecording], [resumeRecording]) — unchanged behavior,
     * routes a failure to [onHardwareError] exactly like before. [attemptImmediateRecovery] below
     * passes `false`: it drives its own short, bounded retry loop from
     * [com.example.audiomemo.features.transcript.service.AudioRecordingService] and must decide
     * for itself when to give up, so a failing attempt here must never recursively re-trigger the
     * handler that's already running that loop.
     */
    private fun startNewChunk(notifyOnHardwareError: Boolean = true): Boolean {
        finaliseCurrentChunk(label = "chunk")
        // am4-2 (FR9): reset right after finalising the previous chunk (which reads this same
        // var) and before the new chunk's MediaRecorder is created below, so amplitudeJob starts
        // accumulating this new chunk's max from a clean 0.
        maxAmplitudeInChunk.set(0)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(context.filesDir, "audio_chunk_$timestamp.m4a")
        currentOutputFile = outputFile

        var started = false
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile.absolutePath)
            setOnErrorListener { _, _, _ ->
                Log.e("AudioRecorderManager", "MediaRecorder hardware error")
                // (code review, patch 1 — CRITICAL): isRecording must flip false here — this is
                // the exact "recorder silently stopped" case the heartbeat sentinel
                // (AudioRecordingService.isRecordingUnhealthy) exists to catch. Previously only
                // pauseRecording()/stopRecording() ever cleared it, so a real async hardware
                // error left isRecording reading true forever, making the sentinel's whole health
                // check permanently blind to the scenario its own KDoc describes.
                isRecording = false
                cancelMonitoringJobs()
                // (code review, patch 5): gate on the SAME notifyOnHardwareError this specific
                // startNewChunk() call was made with — a chunk created via attemptImmediateRecovery
                // (notifyOnHardwareError = false) must never have ITS async error listener
                // re-trigger onHardwareError either; only the synchronous prepare()/start() catch
                // below was gated before, leaving this async path free to recursively re-enter the
                // very recovery-loop handler that's already driving the retries.
                if (notifyOnHardwareError) onHardwareError?.invoke()
            }
            try {
                prepare()
                // am3-5: fire before start() so the caller can create the chunk's Room row
                // (RECORDING) before any audio is actually captured — closes the race where a
                // kill mid-chunk left no trace of the chunk anywhere in Room.
                onChunkStarted?.invoke(outputFile.absolutePath)
                start()
                started = true
            } catch (e: Exception) {
                e.printStackTrace()
                // (code review, patch 1 — CRITICAL): same isRecording=false fix as the async
                // listener above, for the synchronous failure path.
                isRecording = false
                // (am-hotfix, never-stop-recording): also cancel monitoring jobs on this
                // synchronous failure path — previously only the async setOnErrorListener above
                // did this, leaving amplitudeJob/chunkJob running against a broken/null recorder
                // if prepare()/start() threw here instead. Mirrors the listener's own cleanup.
                cancelMonitoringJobs()
                if (notifyOnHardwareError) onHardwareError?.invoke()
            }
        }
        return started
    }

    /**
     * Outcome of [attemptImmediateRecovery] (code review, am-hotfix never-stop-recording, patch
     * 9): distinguishes a genuine unrecoverable hardware fault from running out of disk space
     * mid-recovery. Both make the attempt fail, but they're different, differently-actionable
     * causes — [AudioRecordingService]'s eventual give-up alert should say "low storage", not a
     * generic "microphone error", when that's what actually happened.
     */
    enum class RecoveryOutcome {
        /** A new chunk's `MediaRecorder` started successfully — recording is healthy again. */
        RECOVERED,
        /** [StorageGuard] reports insufficient free space — a real restriction, not retried. */
        STORAGE_INSUFFICIENT,
        /** `prepare()`/`start()` failed for a reason other than storage. */
        HARDWARE_FAILURE
    }

    /**
     * (am-hotfix, never-stop-recording): fast, bounded recovery attempt after a hardware error —
     * reuses the exact same `prepare()`/`start()` path every normal chunk rotation already goes
     * through ([startNewChunk]), but never invokes [onHardwareError] itself on failure (see
     * [startNewChunk]'s `notifyOnHardwareError` KDoc). [AudioRecordingService] drives the actual
     * bounded retry loop (a few attempts, seconds apart) and falls back to a real stop + alert
     * notification only once that loop is exhausted.
     *
     * Re-checks storage first via [StorageGuard] since a hardware error can coincide with
     * genuinely running out of disk space — a real restriction, not something worth retrying
     * against — and reports that distinctly as [RecoveryOutcome.STORAGE_INSUFFICIENT].
     *
     * On [RecoveryOutcome.RECOVERED], flips [isRecording] back to `true` and restarts the
     * monitoring jobs if they aren't already active.
     */
    fun attemptImmediateRecovery(): RecoveryOutcome {
        if (!StorageGuard.hasEnoughStorage(context.filesDir)) return RecoveryOutcome.STORAGE_INSUFFICIENT
        val started = startNewChunk(notifyOnHardwareError = false)
        if (!started) return RecoveryOutcome.HARDWARE_FAILURE
        isRecording = true
        if (amplitudeJob?.isActive != true) startMonitoringJobs()
        return RecoveryOutcome.RECOVERED
    }

    private fun finaliseCurrentChunk(label: String) {
        val completed = currentOutputFile
        // am4-2 (FR9): captured before stopMediaRecorder()/reset — this is the max amplitude
        // observed over this exact chunk's whole recording.
        val amplitudeOutcome = evaluateChunkAmplitude(maxAmplitudeInChunk.get())
        stopMediaRecorder()
        if (completed != null && completed.length() > 0) {
            Log.d("AudioRecorderManager", "$label chunk saved: ${completed.absolutePath} (${completed.length()} bytes)")
            _lastChunkFile.value = completed
            onChunkCompleted?.invoke(completed, amplitudeOutcome)
        }
        currentOutputFile = null
    }

    private fun stopMediaRecorder() {
        val recorder = mediaRecorder
        mediaRecorder = null
        recorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

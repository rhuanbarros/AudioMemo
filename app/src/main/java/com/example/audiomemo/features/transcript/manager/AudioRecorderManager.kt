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

class AudioRecorderManager(private val context: Context) {

    companion object {
        /** Target duration of a normal (non-interrupted) recording chunk. */
        val CHUNK_DURATION_MS = TimeUnit.MINUTES.toMillis(2)

        /** How often storage is re-checked while waiting for the next chunk rotation. */
        val STORAGE_CHECK_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15)
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

    /** True while the recorder is actively capturing audio (false when paused or stopped). */
    var isRecording: Boolean = false
        private set

    /** Called whenever a chunk is completed (rotation or pause/stop). */
    var onChunkCompleted: ((File) -> Unit)? = null

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
                _amplitude.value = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (_: RuntimeException) {
                    0
                }
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

    private fun startNewChunk() {
        finaliseCurrentChunk(label = "chunk")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(context.filesDir, "audio_chunk_$timestamp.m4a")
        currentOutputFile = outputFile

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
                Log.e("AudioRecorderManager", "MediaRecorder hardware error — stopping recording")
                cancelMonitoringJobs()
                onHardwareError?.invoke()
            }
            try {
                prepare()
                // am3-5: fire before start() so the caller can create the chunk's Room row
                // (RECORDING) before any audio is actually captured — closes the race where a
                // kill mid-chunk left no trace of the chunk anywhere in Room.
                onChunkStarted?.invoke(outputFile.absolutePath)
                start()
            } catch (e: Exception) {
                e.printStackTrace()
                onHardwareError?.invoke()
            }
        }
    }

    private fun finaliseCurrentChunk(label: String) {
        val completed = currentOutputFile
        stopMediaRecorder()
        if (completed != null && completed.length() > 0) {
            Log.d("AudioRecorderManager", "$label chunk saved: ${completed.absolutePath} (${completed.length()} bytes)")
            _lastChunkFile.value = completed
            onChunkCompleted?.invoke(completed)
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

package com.example.audiomemo.features.transcript.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitors the MediaRecorder amplitude stream. After [SILENCE_TIMEOUT_MS] of continuous
 * silence (amplitude below [SILENCE_THRESHOLD]), fires [onSilenceDetected] once per
 * silence period so the service can show a "No audio detected – Check microphone" warning.
 *
 * Also checks on every tick whether RECORD_AUDIO has been revoked (Android 11+ one-time
 * permissions). Fires [onPermissionRevoked] so the service can stop cleanly.
 */
class SilenceDetector(
    private val context: Context,
    private val amplitude: StateFlow<Int>,
    private val onSilenceDetected: () -> Unit,
    private val onPermissionRevoked: () -> Unit = {}
) {
    companion object {
        /**
         * Deliberately independent of [AudioRecorderManager.SILENCE_AMPLITUDE_THRESHOLD] (code
         * review, am4-2, patch 4): both read the same `MediaRecorder.getMaxAmplitude()` signal but
         * serve different purposes — this one drives a live, user-facing "No audio detected"
         * warning *during* recording; `AudioRecorderManager`'s decides whether a whole *finished*
         * chunk skips Supabase upload (FR9/FR10). Different values, not a bug — never unify them
         * without a separate, deliberate design decision.
         */
        private const val SILENCE_THRESHOLD = 500
        private const val SILENCE_TIMEOUT_MS = 10_000L
        private const val CHECK_INTERVAL_MS = 1_000L
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private var silentDurationMs = 0L
    private var warningEmitted = false

    fun start() {
        silentDurationMs = 0L
        warningEmitted = false
        job = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                // Stop immediately if RECORD_AUDIO was revoked while backgrounded.
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    onPermissionRevoked()
                    break
                }
                if (amplitude.value < SILENCE_THRESHOLD) {
                    silentDurationMs += CHECK_INTERVAL_MS
                    if (silentDurationMs >= SILENCE_TIMEOUT_MS && !warningEmitted) {
                        warningEmitted = true
                        onSilenceDetected()
                    }
                } else {
                    silentDurationMs = 0L
                    warningEmitted = false
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Reset counters when recording resumes so the 10-second window restarts. */
    fun reset() {
        silentDurationMs = 0L
        warningEmitted = false
    }
}

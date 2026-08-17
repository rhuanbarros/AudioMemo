package com.example.audiomemo.features.transcript.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.features.transcript.data.worker.RecordingRestartEntryPoint
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * FR3 (am3-3): auto-starts [AudioRecordingService] after a full device reboot completes, but
 * **only** when [com.example.audiomemo.core.preferences.AppPreferencesRepository.recordingShouldBeActive]
 * was still `true` before the shutdown — the same persisted owner intent
 * [com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker] (am3-2, FR2)
 * already reads. If the owner had explicitly stopped recording before the reboot, this receiver
 * must never turn it back on by itself (see [shouldAutoStart]).
 *
 * Not `@AndroidEntryPoint`-compatible the way an Activity/Service/Worker is — a manifest-registered
 * `BroadcastReceiver` doesn't get constructor injection from Hilt, so dependencies are pulled via
 * [EntryPointAccessors] instead, through the same [RecordingRestartEntryPoint] used by
 * [com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker] (code review,
 * am3-3: was originally a second, duplicated `@EntryPoint` interface here — consolidated).
 *
 * `onReceive` itself is not `suspend` (a plain Android system callback), but reading the
 * DataStore-backed preference is — so this uses `goAsync()` to tell the system to keep the
 * receiver alive past `onReceive` returning, then does the actual work on a background coroutine
 * and calls `PendingResult.finish()` when done, from a `finally` so it always runs regardless of
 * how the coroutine body exits. Without `goAsync()`, the system would be free to kill the process
 * before the suspending preference read (or the foreground-service start it gates) ever
 * completes.
 *
 * **Known Android platform limitation** (see `README.md`, "Known Behavior"): since Android 3.1,
 * the system does not enable a freshly installed app's manifest-registered `BOOT_COMPLETED`
 * receiver until the user has manually launched the app at least once — expected system
 * behavior, not a bug in this receiver, and not something that can be worked around from app
 * code. (This is a different restriction from the Android 12+ background-foreground-service-start
 * limitation the inner `try`/`catch` below guards against — that one applies once this receiver
 * *is* running, not to whether it runs at all.)
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"

        /**
         * Budget for the DataStore read below, well inside `goAsync()`'s background-execution
         * window (Android enforces a limit, historically ~10s) — a hang here (e.g. file-lock
         * contention) degrades to a logged timeout instead of holding `pendingResult` open
         * indefinitely and risking the process being killed before `finish()` ever runs (code
         * review, am3-3).
         */
        private const val PREFERENCE_READ_TIMEOUT_MS = 5_000L

        /**
         * Pure predicate for the entire point of this story's contract: never auto-start
         * recording unless the owner's persisted intent was still active before the reboot.
         * Trivial today (a straight pass-through), but named and pure so it (a) has a place to
         * grow if the condition ever gets more complex, and (b) is unit-testable from plain-JVM
         * `src/test` without Robolectric/instrumentation — mirrors
         * [com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker.needsRestart]
         * (am3-2). Extracted after code review (am3-3): the un-extracted inline condition had no
         * test coverage, so an accidental inversion would have silently broken the whole feature.
         */
        internal fun shouldAutoStart(recordingShouldBeActive: Boolean): Boolean =
            recordingShouldBeActive
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        val entryPoint = EntryPointAccessors.fromApplication(
            appContext,
            RecordingRestartEntryPoint::class.java
        )
        val appPreferencesRepository = entryPoint.appPreferencesRepository()
        val appEventLogger = entryPoint.appEventLogger()

        val pendingResult = goAsync()
        // Dispatchers.IO, not Default: this coroutine's only work is a DataStore (disk I/O) read,
        // not CPU-bound computation (code review, am3-3).
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val shouldBeActive = withTimeout(PREFERENCE_READ_TIMEOUT_MS) {
                    appPreferencesRepository.recordingShouldBeActive.first()
                }

                if (!shouldAutoStart(shouldBeActive)) {
                    Log.i(
                        TAG,
                        "Boot completed but recording was not active before shutdown — not starting"
                    )
                    return@launch
                }

                Log.i(TAG, "Boot completed and recording should be active — starting recording service")

                // Only the service-start call gets its own inner try/catch: a failure here is a
                // distinct, expected-in-practice outcome (see catch clauses below) that deserves
                // its own log message, separate from the outer catch below which is a
                // catch-all for anything unexpected in the rest of this body (code review,
                // am3-3).
                try {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, AudioRecordingService::class.java)
                    )
                    appEventLogger.log(
                        LogCategory.RECORDING,
                        "Device rebooted — recording auto-started (was active before shutdown)"
                    )
                } catch (e: IllegalStateException) {
                    // On Android 12+ a background-initiated foreground-service start can throw
                    // ForegroundServiceStartNotAllowedException. Caught as the plain
                    // IllegalStateException supertype rather than the API-31 class by name, to
                    // avoid a class-verification failure on pre-31 devices where that class is
                    // never actually thrown — same pattern as RecordingWatchdogWorker.
                    logStartFailure(appEventLogger, e)
                } catch (e: SecurityException) {
                    // E.g. RECORD_AUDIO was revoked while the device was powered off — a
                    // different exception type than the one above, but the same "degrade
                    // gracefully, never crash" contract applies (code review, am3-3).
                    logStartFailure(appEventLogger, e)
                }
            } catch (e: Exception) {
                // Broad on purpose: a DataStore IOException (corrupted preferences file), the
                // withTimeout() above timing out, or anything else unexpected in this body must
                // never propagate unhandled out of this coroutine — goAsync()'s pendingResult
                // still has to be finished either way (code review, am3-3).
                Log.w(TAG, "Failed to process boot-completed auto-start", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun logStartFailure(appEventLogger: AppEventLogger, e: Exception) {
        Log.w(TAG, "Failed to auto-start recording service after boot", e)
        appEventLogger.log(
            LogCategory.RECORDING,
            "Device rebooted but auto-start of recording failed: ${e.message}"
        )
    }
}

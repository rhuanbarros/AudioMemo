package com.example.audiomemo

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadEntryPoint
import com.example.audiomemo.features.transcript.data.worker.RecordingRestartEntryPoint
import com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltAndroidApp
class AudioMemoApplication : Application() {

    companion object {
        private const val TAG = "AudioMemoApplication"

        /**
         * Budget for the `recordingShouldBeActive.first()` read below (code review finding —
         * mirrors [com.example.audiomemo.features.transcript.receiver.BootCompletedReceiver]'s
         * `PREFERENCE_READ_TIMEOUT_MS`, the established idiom in this codebase for bounding a
         * DataStore disk-I/O suspend call so a slow/stuck read can never hang this coroutine
         * forever).
         */
        private const val PREFERENCE_READ_TIMEOUT_MS = 5_000L

        /**
         * Pure predicate for the am-hotfix "auto-start on every process start" contract: only
         * start the service when `RECORD_AUDIO` is actually granted AND the owner's persisted
         * intent ([com.example.audiomemo.core.preferences.AppPreferencesRepository.recordingShouldBeActive])
         * is still `true`. Context/DAO-free so it's unit-testable from plain-JVM `src/test`,
         * mirroring [com.example.audiomemo.features.transcript.receiver.BootCompletedReceiver.shouldAutoStart]
         * (am3-3) and [RecordingWatchdogWorker.needsRestart] (am3-2) — same project convention of
         * extracting the actual decision out of the Context-bound caller so an accidental
         * inversion doesn't go uncaught.
         */
        internal fun shouldAutoStartOnProcessStart(
            hasRecordPermission: Boolean,
            recordingShouldBeActive: Boolean
        ): Boolean = hasRecordPermission && recordingShouldBeActive
    }

    override fun onCreate() {
        super.onCreate()
        // am3-2 (FR2): always-on safety net. Idempotent (ExistingPeriodicWorkPolicy.KEEP) —
        // safe to call on every process start, whether that's the owner opening the app, the
        // system relaunching it, or the watchdog itself restarting AudioRecordingService.
        RecordingWatchdogWorker.enqueue(this)

        // am-hotfix (FR2 gap): RecordingWatchdogWorker only wakes up every ~15min and
        // BootCompletedReceiver only fires after a full device reboot — neither one covers a
        // plain process start (icon tap, system respawn, watchdog itself restarting
        // AudioRecordingService), which is exactly what onCreate() runs on every single time.
        // Reuses the same Intent + ContextCompat.startForegroundService + IllegalStateException
        // idiom as TranscriptScreen.kt/RecordingWatchdogWorker.kt/BootCompletedReceiver.kt, and
        // the same RecordingRestartEntryPoint those last two already share.
        val hasRecordPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    this@AudioMemoApplication,
                    RecordingRestartEntryPoint::class.java
                )
                val appPreferencesRepository = entryPoint.appPreferencesRepository()
                val appEventLogger = entryPoint.appEventLogger()

                val recordingShouldBeActive = withTimeout(PREFERENCE_READ_TIMEOUT_MS) {
                    appPreferencesRepository.recordingShouldBeActive.first()
                }

                if (!shouldAutoStartOnProcessStart(hasRecordPermission, recordingShouldBeActive)) {
                    // Code review finding: log which of the two gates blocked it — otherwise a
                    // silent no-op here is indistinguishable from "already running"/"working as
                    // intended" when reading the log, on an app whose entire premise is
                    // observability (this same ticket's FR-set).
                    val reason = when {
                        !hasRecordPermission -> "RECORD_AUDIO not granted yet"
                        else -> "owner had explicitly stopped recording last"
                    }
                    Log.i(TAG, "Process start: recording NOT auto-started ($reason)")
                    return@launch
                }

                try {
                    ContextCompat.startForegroundService(
                        this@AudioMemoApplication,
                        Intent(this@AudioMemoApplication, AudioRecordingService::class.java)
                    )
                    Log.i(TAG, "Process start: recording auto-started (was active, permission granted)")
                    appEventLogger.log(
                        LogCategory.RECORDING,
                        "Process start: recording auto-started (was active before this process start)"
                    )
                } catch (e: IllegalStateException) {
                    // On Android 12+ a background-initiated foreground-service start can throw
                    // ForegroundServiceStartNotAllowedException. Caught as the plain
                    // IllegalStateException supertype rather than the API-31 class by name, same
                    // reasoning as RecordingWatchdogWorker/BootCompletedReceiver: referencing that
                    // class in a catch clause risks a class-verification failure on pre-31
                    // devices, even though it's never actually thrown there. Degrades safely —
                    // the watchdog (~15min) or the next manual open still covers the gap.
                    Log.w(TAG, "Process-start auto-start of recording failed", e)
                    appEventLogger.log(
                        LogCategory.RECORDING,
                        "Process start: auto-start of recording failed: ${e.message}"
                    )
                } catch (e: SecurityException) {
                    // E.g. RECORD_AUDIO was revoked between the check above and this call (mirrors
                    // BootCompletedReceiver's identical catch — code review finding, same class of
                    // TOCTOU the boot path already defends against).
                    Log.w(TAG, "Process-start auto-start of recording failed", e)
                    appEventLogger.log(
                        LogCategory.RECORDING,
                        "Process start: auto-start of recording failed: ${e.message}"
                    )
                }
            } catch (e: Exception) {
                // Broad on purpose, same as BootCompletedReceiver: a DataStore IOException or
                // anything else unexpected here must never crash the whole cold-start path —
                // this entire block is additive to onCreate(), never load-bearing for it.
                Log.w(TAG, "Failed to process auto-start check on process start", e)
            }
        }

        // am-hotfix (supabase session init): proactive warmup of gotrue-kt's async
        // session-from-disk load. Fire-and-forget — this is only an optimization that covers the
        // common case without blocking onCreate(); the decisive fix (closes the race
        // deterministically even if this warmup hasn't finished yet) is the
        // awaitInitialization() call in SupabaseUploadWorker.doWork().
        //
        // `Application` is not `@AndroidEntryPoint`-compatible the way an Activity/Service/Worker
        // is, so the SupabaseClient is pulled via EntryPointAccessors instead — reusing
        // SupabaseUploadEntryPoint (already declared in SupabaseUploadWorker.kt, which needs the
        // exact same accessor) rather than declaring a second, duplicated @EntryPoint interface
        // here. Same consolidation this project's own am3-3 code review already established for
        // RecordingWatchdogWorker/BootCompletedReceiver (see RecordingRestartEntryPoint).
        //
        // Wrapped in try/catch (code review, patch 1): awaitInitialization() is documented by the
        // SDK to never throw, but that guarantee lives only in a comment, not in the type system.
        // If a future SDK version or an unanticipated edge case ever violates it, this whole block
        // is a pure optimization — it must never be able to turn into a cold-start crash. A caught
        // failure is just logged; the decisive fix in SupabaseUploadWorker doesn't depend on this
        // warmup succeeding.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val supabaseClient = EntryPointAccessors.fromApplication(
                    this@AudioMemoApplication,
                    SupabaseUploadEntryPoint::class.java
                ).supabaseClient()
                supabaseClient.auth.awaitInitialization()
            } catch (e: Exception) {
                Log.w(TAG, "Supabase auth warmup failed — non-fatal, upload worker awaits its own init", e)
            }
        }
    }
}

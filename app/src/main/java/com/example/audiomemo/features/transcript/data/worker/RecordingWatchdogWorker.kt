package com.example.audiomemo.features.transcript.data.worker

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.preferences.AppPreferencesRepository
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Shared Hilt entry point for reaching [AppPreferencesRepository]/[AppEventLogger] from Android
 * components that can't get constructor injection from Hilt directly — `CoroutineWorker`s (like
 * [RecordingWatchdogWorker] below) and a manifest-registered `BroadcastReceiver` (
 * [com.example.audiomemo.features.transcript.receiver.BootCompletedReceiver], am3-3) both need
 * exactly the same two accessors to decide whether [AudioRecordingService] needs (re)starting, so
 * this single interface is shared between them instead of each declaring its own copy that then
 * needs to be kept in sync by hand (code review, am3-3).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingRestartEntryPoint {
    fun appPreferencesRepository(): AppPreferencesRepository
    fun appEventLogger(): AppEventLogger
}

/**
 * Always-on safety net for [AudioRecordingService] (am3-2, FR2). `onStartCommand` already returns
 * `START_STICKY` on its normal path, which covers most quick process kills (memory pressure) on
 * its own — this worker is the fallback for the case `START_STICKY` doesn't recover by itself
 * (e.g. the system decides not to redeliver the Intent). It wakes up periodically and, if the
 * owner's persisted intent ([AppPreferencesRepository.recordingShouldBeActive]) is still `true`
 * but the service hasn't proven it's alive recently ([AppPreferencesRepository.lastHeartbeatAt]),
 * restarts it exactly like a normal start.
 *
 * **Does NOT recover from an explicit force-stop** (`adb shell am force-stop` / "Forçar parada" in
 * the Android system UI) — that is an Android-level protection that suspends `START_STICKY`,
 * `WorkManager`, and receivers for the package until the user opens the app manually again. This
 * worker simply never runs in that case; that's expected platform behavior, not a bug (see story
 * am3-2 boundaries).
 *
 * The minimum interval Android allows for a `PeriodicWorkRequest` is 15 minutes (platform
 * limitation, not a design choice) — see [enqueue]. **That 15-minute cadence, not
 * [HEARTBEAT_STALE_THRESHOLD_MS], is what actually bounds real-world detection latency:** in the
 * worst case a dead service isn't restarted until just under 15 minutes after it died, even though
 * the staleness threshold itself is only 60s (code review, am3-2 — the original KDoc read as if
 * detection was near-immediate).
 */
class RecordingWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "recording_watchdog"
        private const val TAG = "RecordingWatchdogWorker"

        /** Android's platform-enforced minimum for a PeriodicWorkRequest. */
        private const val PERIODIC_INTERVAL_MINUTES = 15L

        /**
         * Slack threshold for how old [AppPreferencesRepository.lastHeartbeatAt] can be before
         * the service is considered dead — 2x [AudioRecordingService]'s ~30s heartbeat-write
         * interval, generous enough to absorb a single missed tick without false-positiving on a
         * perfectly healthy session. **This is a staleness threshold, not a detection-latency
         * bound** — this worker itself only runs every [PERIODIC_INTERVAL_MINUTES] minutes, so
         * worst-case real-world detection latency is bounded by that periodic cadence (up to
         * ~15 minutes), not by this 60s value (code review, am3-2).
         */
        internal const val HEARTBEAT_STALE_THRESHOLD_MS = 60_000L

        /**
         * Pure predicate for whether the service needs restarting: the owner's intent is still
         * "recording" but the heartbeat is older than the slack threshold. Context/DAO-free so
         * it's unit-testable from plain-JVM `src/test` — this project has no Robolectric/
         * androidTest WorkManager infra (see `AudioRecordingServiceConflictResolutionTest`,
         * am1-2, for the full rationale). [now] and [lastHeartbeatAt] must both be sourced from
         * the same monotonic clock ([SystemClock.elapsedRealtime], never wall-clock time — see
         * [doWork] and [AppPreferencesRepository.recordHeartbeat]) so this comparison is immune
         * to wall-clock jumps (NTP sync, manual time change, DST).
         *
         * **Reboot guard (am3-5 code review, twin fix):** [SystemClock.elapsedRealtime] resets to
         * ~0 on device reboot, but [lastHeartbeatAt] is persisted (survives reboot) — if the
         * process was killed around/by a reboot, `now - lastHeartbeatAt` goes negative and would
         * never read as stale, silently masking a genuinely dead service. `now < lastHeartbeatAt`
         * (clock went backward) is therefore also treated as stale.
         */
        internal fun needsRestart(
            recordingShouldBeActive: Boolean,
            lastHeartbeatAt: Long,
            now: Long
        ): Boolean =
            recordingShouldBeActive &&
                ((now - lastHeartbeatAt) > HEARTBEAT_STALE_THRESHOLD_MS || now < lastHeartbeatAt)

        /**
         * Schedules the periodic watchdog, idempotently — safe to call on every app process
         * start (see [com.example.audiomemo.AudioMemoApplication.onCreate]). `KEEP` never resets
         * an already-scheduled periodic timer, so repeated calls (app reopened, watchdog itself
         * restarting the service and thus the process) don't disturb the existing schedule.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecordingWatchdogWorker>(
                    PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
                ).build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            RecordingRestartEntryPoint::class.java
        )
        val appPreferencesRepository = entryPoint.appPreferencesRepository()
        val appEventLogger = entryPoint.appEventLogger()

        val shouldBeActive = appPreferencesRepository.recordingShouldBeActive.first()
        val lastHeartbeat = appPreferencesRepository.lastHeartbeatAt.first()

        // SystemClock.elapsedRealtime() (monotonic, immune to wall-clock jumps), matching the
        // write side in AppPreferencesRepository.recordHeartbeat() — see needsRestart's KDoc.
        if (!needsRestart(shouldBeActive, lastHeartbeat, SystemClock.elapsedRealtime())) {
            return Result.success()
        }

        Log.i(TAG, "Watchdog detected recording should be active but the service isn't — restarting")
        appEventLogger.log(
            LogCategory.RECORDING,
            "Watchdog restarted recording service after detecting a stale heartbeat"
        )

        // On Android 12+ a background-initiated foreground-service start can throw
        // ForegroundServiceStartNotAllowedException (code review, am3-2) — never let that (or
        // any other platform failure here) crash the whole "always-on safety net" worker
        // unhandled. Caught as the plain IllegalStateException supertype rather than the API-31
        // class by name: referencing that class in a catch clause on a minSdk-24 project risks a
        // class-verification failure on pre-31 devices, even though it's never actually thrown
        // there. Result.retry() gives WorkManager's own backoff another shot rather than either
        // silently giving up or spinning immediately.
        try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, AudioRecordingService::class.java)
            )
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Watchdog failed to restart the recording service", e)
            appEventLogger.log(
                LogCategory.RECORDING,
                "Watchdog failed to restart recording service: ${e.message}"
            )
            return Result.retry()
        }

        return Result.success()
    }
}

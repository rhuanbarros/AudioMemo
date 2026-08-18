package com.example.audiomemo.features.transcript.data.worker

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.preferences.AppPreferencesRepository
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared Hilt entry point for [RecordingWatchdogWorker]/[com.example.audiomemo.features.transcript.receiver.BootCompletedReceiver] —
 * Android components that can't get constructor injection from Hilt directly.
 * Originally scoped to just [AppPreferencesRepository]/[AppEventLogger] (what
 * `BootCompletedReceiver` still uses this for: deciding whether [AudioRecordingService] needs
 * (re)starting), so the two share one interface instead of each declaring its own copy that then
 * needs to be kept in sync by hand (code review, am3-3).
 *
 * [chunkDao] was added later, for [RecordingWatchdogWorker] alone (hotfix,
 * `am-hotfix-periodic-supabase-retry-sweep`) — its unrelated global `FAILED`-chunk retry sweep
 * needs it to reach chunks stuck `FAILED` for days into a long-running session, the same way
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryEntryPoint] already exposes
 * [ChunkDao] to its own (session-scoped) sweep. `BootCompletedReceiver` never uses this accessor —
 * this interface now serves two independent concerns for two independent callers, an accepted
 * trade-off vs. adding a second `@EntryPoint` (code review finding, hotfix).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecordingRestartEntryPoint {
    fun appPreferencesRepository(): AppPreferencesRepository
    fun appEventLogger(): AppEventLogger
    fun chunkDao(): ChunkDao
}

/**
 * Two independent responsibilities sharing one already-scheduled periodic worker (hotfix,
 * `am-hotfix-periodic-supabase-retry-sweep` — see below for why they're bolted together instead
 * of split): (1) the original always-on safety net for [AudioRecordingService] (am3-2, FR2), and
 * (2) a global `FAILED` Supabase-upload retry sweep.
 *
 * **Responsibility 1 — safety net.** `onStartCommand` already returns `START_STICKY` on its
 * normal path, which covers most quick process kills (memory pressure) on its own — this worker
 * is the fallback for the case `START_STICKY` doesn't recover by itself (e.g. the system decides
 * not to redeliver the Intent). It wakes up periodically and, if the owner's persisted intent
 * ([AppPreferencesRepository.recordingShouldBeActive]) is still `true` but the service hasn't
 * proven it's alive recently ([AppPreferencesRepository.lastHeartbeatAt]), restarts it exactly
 * like a normal start.
 *
 * **Responsibility 2 — global `FAILED` Supabase-upload retry sweep.**
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker] is only ever
 * enqueued once, ~15s after a session *starts* ([ChunkFinalizationWorker]'s crash-recovery sweep,
 * am1-3). With the product now recording in a single continuous session for days (am3-2..am3-5),
 * a chunk that exhausts its 3 upload attempts mid-session (`decideUploadOutcome`, unchanged by
 * this worker) becomes `FAILED` and would otherwise never be retried again until the session
 * restarts. [doWork] closes that gap by also sweeping **every** `FAILED` chunk globally
 * (`ChunkDao.getChunksBySupabaseUploadStatus(FAILED)`, no session scope — deliberately unlike
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker]) and re-enqueuing
 * each one exactly like that worker already does, on every 15-minute tick — reusing this already-
 * scheduled periodic worker instead of adding a new one (story's explicit `Never` boundary). A
 * chunk `FAILED` for a permanent reason (local file missing — see
 * [sweepFailedSupabaseUploads]/[needsGlobalRetryEnqueue]) is deliberately excluded from retry, so
 * this doesn't become an unbounded forever-retry loop for something that can never succeed.
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
 * detection was near-immediate). The same 15-minute bound now also applies to the global `FAILED`
 * retry sweep above.
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
         * Pure predicate for the global retry sweep (hotfix,
         * `am-hotfix-periodic-supabase-retry-sweep`): true when [chunk] is `FAILED` AND
         * [localFileExists] — i.e. worth reverting to `PENDING` and re-enqueuing. Deliberately
         * **not** scoped to any `sessionId` — unlike
         * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker.needsRetryEnqueue],
         * which mirrors this same shape but filters by session, this sweep is global by design (a
         * chunk from any past session must be picked up, not just the current one — see the
         * story's Approach).
         *
         * [localFileExists] gates against an infinite-retry loop (code review finding): a chunk
         * can reach `FAILED` because its local file is gone
         * (`SupabaseUploadWorker.doWork`'s `!file.exists()` branch) — a *permanent* condition, not
         * a transient network failure. Re-enqueuing that chunk would only have
         * `SupabaseUploadWorker` immediately fail it `FAILED` again for the exact same reason,
         * every single 15-minute tick, forever — the opposite of "resilient". Callers pass the
         * result of a real `File.exists()` check (impure, hence not evaluated inside this
         * function) so this predicate itself stays Context/DAO-free and unit-testable from
         * plain-JVM `src/test`, same pattern as every other worker predicate in this project.
         */
        internal fun needsGlobalRetryEnqueue(chunk: ChunkEntity, localFileExists: Boolean): Boolean =
            chunk.supabaseUploadStatus == ChunkStatus.FAILED && localFileExists

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
        val chunkDao = entryPoint.chunkDao()

        // Global FAILED-chunk retry sweep (hotfix, am-hotfix-periodic-supabase-retry-sweep) runs
        // on every tick, unconditionally — independent of the heartbeat/restart decision below
        // (see class KDoc's "Responsibility 2"). Must never be skipped by the early return that
        // follows, or a long-running healthy session (heartbeat fresh, no restart needed) would
        // never get its FAILED chunks retried at all. Wrapped in try/catch (code review finding):
        // this is the SECONDARY responsibility bolted onto this worker — a bug here (Room/SQLite
        // error, unexpected WorkManager exception) must never propagate out of doWork() and skip
        // the ORIGINAL, more critical heartbeat/restart check a few lines below.
        try {
            sweepFailedSupabaseUploads(chunkDao, appEventLogger)
        } catch (e: Exception) {
            Log.w(TAG, "Watchdog global retry sweep failed unexpectedly", e)
        }

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

    /**
     * Global `FAILED` Supabase-upload retry sweep (hotfix, `am-hotfix-periodic-supabase-retry-
     * sweep`) — mirrors
     * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker.doWork]'s exact
     * reenqueue pattern (revert `FAILED` -> `PENDING`, then `enqueueUniqueWork` with
     * [ExistingWorkPolicy.KEEP] under the very same unique work name
     * [AudioRecordingService.enqueueSupabaseUpload] itself uses, so an already-pending work item
     * — e.g. one [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker] just
     * enqueued — is never duplicated) but with **no session scope**: every `FAILED` chunk across
     * every session is picked up here, not just the current one.
     *
     * A chunk whose local file no longer exists is skipped, not re-enqueued (code review finding
     * — see [needsGlobalRetryEnqueue]'s KDoc for why: it's a permanent failure, retrying forever
     * would never succeed) and reported separately so it's visible as a genuine, permanent loss
     * rather than silently dropped or confused with an ordinary in-progress retry.
     *
     * Each chunk's update+enqueue is individually try/caught (code review finding) so one bad
     * chunk (DB write failure, WorkManager exception) doesn't abort the sweep for the rest of the
     * batch on the same tick.
     *
     * A no-op (no log, no DAO write beyond the read) when there is nothing to sweep.
     */
    private suspend fun sweepFailedSupabaseUploads(
        chunkDao: ChunkDao,
        appEventLogger: AppEventLogger
    ) {
        val failedChunks = chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.FAILED)
        if (failedChunks.isEmpty()) return

        var retriedCount = 0
        var unrecoverableCount = 0

        failedChunks.forEach { chunk ->
            val localFileExists = File(chunk.filePath).exists()
            if (!needsGlobalRetryEnqueue(chunk, localFileExists)) {
                if (!localFileExists) unrecoverableCount++
                return@forEach
            }
            try {
                chunkDao.updateSupabaseUploadStatus(chunk.id, ChunkStatus.PENDING)
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    AudioRecordingService.supabaseUploadWorkName(chunk.id),
                    ExistingWorkPolicy.KEEP,
                    AudioRecordingService.buildSupabaseUploadWorkRequest(chunk.id, chunk.sessionId)
                )
                retriedCount++
            } catch (e: Exception) {
                Log.w(TAG, "Watchdog global retry sweep: failed to re-enqueue chunk ${chunk.id}", e)
            }
        }

        if (retriedCount > 0) {
            Log.i(
                TAG,
                "Watchdog global retry sweep: $retriedCount FAILED Supabase chunk(s) " +
                    "reverted PENDING and re-enqueued"
            )
            appEventLogger.log(
                LogCategory.UPLOAD,
                "Watchdog re-enqueued $retriedCount FAILED Supabase upload(s) globally"
            )
        }
        if (unrecoverableCount > 0) {
            Log.w(
                TAG,
                "Watchdog global retry sweep: $unrecoverableCount FAILED chunk(s) skipped — " +
                    "local file missing, unrecoverable"
            )
            appEventLogger.log(
                LogCategory.UPLOAD,
                "$unrecoverableCount chunk(s) permanently lost: local file missing, upload not retried"
            )
        }
    }
}

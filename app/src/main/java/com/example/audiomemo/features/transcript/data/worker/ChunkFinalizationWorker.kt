package com.example.audiomemo.features.transcript.data.worker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.preferences.AppPreferencesRepository
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import com.example.audiomemo.features.transcript.domain.model.SessionState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChunkFinalizationEntryPoint {
    fun sessionDao(): SessionDao
    fun chunkDao(): ChunkDao

    /** am3-5: needed by the heartbeat-freshness guard in [ChunkFinalizationWorker.doWork]. */
    fun appPreferencesRepository(): AppPreferencesRepository

    /** am3-5: needed to log a "chunk lost" event when the RECORDING sweep finds one. */
    fun appEventLogger(): AppEventLogger
}

/**
 * WorkManager worker enqueued every time recording starts. It runs after a short delay
 * so that a clean service stop can cancel it first. If the process is killed before the
 * service can cancel it (crash / OOM), the worker runs and:
 *  - marks the session STOPPED in Room,
 *  - marks any PENDING chunks FAILED so the upload/transcription pipeline can retry them
 *    ([TranscriptRetryWorker] polls for `FAILED`),
 *  - marks any chunk stuck `UPLOADING` (Supabase) FAILED the same way, so [SupabaseRetryWorker]
 *    (which also polls for `FAILED`) picks it up (am1-3, FR8) — plus deletes any orphaned local
 *    file left behind by a chunk that was confirmed `DONE` but never got cleaned up before the
 *    process died (am1-3 code review finding), and
 *  - marks any chunk still stuck `RECORDING` FAILED in both `status` and `supabaseUploadStatus`
 *    (never `PENDING`) and logs the loss via [AppEventLogger] — the process died before that
 *    chunk's row was ever updated past its initial "recording started" state, so the underlying
 *    `.m4a` has no `moov` box and is undecodable; it must never be enqueued for upload (am3-5).
 *
 * **Heartbeat-freshness guard (am3-5, spec-gate finding):** this worker's ~15s delay means it
 * fires early into *every* session, not only a real crash — without a way to tell a healthy
 * session apart from a dead one, it would wrongly mark the still-recording first chunk of a
 * perfectly healthy session as lost, and the session itself as `STOPPED`, purely because the
 * worker happened to run before the (up to 2-minute) chunk finished. [doWork] checks
 * [AppPreferencesRepository.lastHeartbeatAt] (am3-2) before doing anything else: a fresh
 * heartbeat means the service is genuinely still alive, and the worker returns immediately
 * without touching the session or any chunk — see [RecordingWatchdogWorker], the same heartbeat's
 * other consumer, for the shared staleness threshold.
 */
class ChunkFinalizationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val WORK_NAME_PREFIX = "chunk_finalization_"
        private const val TAG = "ChunkFinalizationWorker"

        /**
         * Pure predicate for the crash-recovery sweep's Supabase-upload leg (am1-3, FR8): true
         * when [chunk] belongs to [sessionId] and was left mid-flight (`UPLOADING`) when the
         * process died — the exact condition this worker reverts back to `FAILED`, mirroring the
         * existing Whisper `status == PENDING -> FAILED` sweep below **exactly**, target state
         * included: `FAILED` is the state [SupabaseRetryWorker] polls for (same relationship as
         * `TranscriptRetryWorker` polling Whisper's `FAILED`) — reverting to `PENDING` instead
         * would silently strand the chunk with nothing left to re-enqueue it (code review
         * finding, am1-3: the original spec asked for `PENDING`, which broke this chain; fixed to
         * `FAILED` to close the loop). Context/DAO-free so it's unit-testable from plain-JVM
         * `src/test` (no Robolectric/androidTest WorkManager infra in this project — see
         * `AudioRecordingServiceConflictResolutionTest`, am1-2).
         */
        internal fun needsSupabaseUploadRecovery(chunk: ChunkEntity, sessionId: Long): Boolean =
            chunk.sessionId == sessionId && chunk.supabaseUploadStatus == ChunkStatus.UPLOADING

        /**
         * Pure predicate: true when [chunk] belongs to [sessionId] and was already confirmed
         * `DONE`. The crash-recovery sweep uses this to also clean up an orphaned local file for
         * such chunks — the process can die in the narrow window between
         * [SupabaseUploadWorker] marking a chunk `DONE` and actually deleting its file (am1-3
         * code review finding). Whether the file still exists on disk is checked separately at
         * the call site (real I/O, not part of this pure predicate).
         */
        internal fun hasConfirmedUploadNeedingCleanup(chunk: ChunkEntity, sessionId: Long): Boolean =
            chunk.sessionId == sessionId && chunk.supabaseUploadStatus == ChunkStatus.DONE

        /**
         * Pure predicate for the am3-5 heartbeat-freshness guard: true when [lastHeartbeatAt] is
         * old enough that the service must be considered genuinely dead, not just mid-session.
         * Reuses [RecordingWatchdogWorker.HEARTBEAT_STALE_THRESHOLD_MS] — the same heartbeat
         * (am3-2), the same staleness threshold, a single source of truth for both consumers.
         * [now] and [lastHeartbeatAt] must both be sourced from the same monotonic clock
         * ([SystemClock.elapsedRealtime], never wall-clock time — see [doWork] and
         * [AppPreferencesRepository.recordHeartbeat]).
         *
         * **Reboot case (code review, am3-5, patch 2):** `elapsedRealtime()` resets to ~0 on
         * device reboot, but [lastHeartbeatAt] is persisted in DataStore and survives the reboot
         * unchanged. If the process died at/around a reboot, `now - lastHeartbeatAt` goes
         * *negative* — never `> threshold` — which would otherwise read as "fresh" even though the
         * process is definitely dead (exactly the case this story exists to catch). Any backward
         * clock jump (`now < lastHeartbeatAt`) is therefore treated as stale too.
         */
        internal fun isHeartbeatStale(lastHeartbeatAt: Long, now: Long): Boolean =
            now < lastHeartbeatAt ||
                (now - lastHeartbeatAt) > RecordingWatchdogWorker.HEARTBEAT_STALE_THRESHOLD_MS

        /**
         * Pure predicate for the am3-5 lost-chunk sweep: true when [chunk] belongs to [sessionId]
         * and is still `RECORDING` — the process died before [SessionStateManager.saveChunk][
         * com.example.audiomemo.features.transcript.manager.SessionStateManager.saveChunk] ever
         * ran for it, so its `.m4a` file is corrupted (no `moov` box, undecodable) and must never
         * be enqueued for upload.
         */
        internal fun needsLostChunkRecovery(chunk: ChunkEntity, sessionId: Long): Boolean =
            chunk.sessionId == sessionId && chunk.status == ChunkStatus.RECORDING

        /**
         * Resolves every chunk still stuck `RECORDING` for [sessionId] to `FAILED` in both
         * `status` and `supabaseUploadStatus` (never `PENDING`/`UPLOADING`, so neither
         * [TranscriptRetryWorker] nor [SupabaseRetryWorker] ever tries to enqueue the corrupted
         * file), logging each loss via [appEventLogger]. Returns the resolved chunks.
         *
         * **Shared between two call sites (code review, am3-5, patch 1 — CRITICAL):** this
         * worker's own crash-recovery sweep in [doWork], AND
         * [com.example.audiomemo.features.transcript.service.AudioRecordingService.handleHardwareError],
         * which never routes through [com.example.audiomemo.features.transcript.manager.AudioRecorderManager.finaliseCurrentChunk]
         * (the recorder may already be broken there) — so a `RECORDING` row created by
         * `onChunkStarted` right before a `MediaRecorder.start()` failure would otherwise be
         * permanently unreachable: that path marks the session `STOPPED` and cancels this worker
         * itself, so no sweep (this one included) could ever run for that session again. Extracted
         * here, DAO/Context-free aside from the two collaborators passed in, so both call sites
         * apply the exact same resolution logic instead of two copies drifting apart.
         */
        internal suspend fun resolveLostChunks(
            chunkDao: ChunkDao,
            appEventLogger: AppEventLogger,
            sessionId: Long
        ): List<ChunkEntity> {
            val lostChunks = chunkDao.getChunksByStatus(ChunkStatus.RECORDING)
                .filter { needsLostChunkRecovery(it, sessionId) }
            lostChunks.forEach { chunk ->
                chunkDao.updateStatus(chunk.id, ChunkStatus.FAILED)
                chunkDao.updateSupabaseUploadStatus(chunk.id, ChunkStatus.FAILED)
                appEventLogger.log(
                    LogCategory.RECORDING,
                    "Chunk lost: recording interrupted (id=${chunk.id})"
                )
            }
            return lostChunks
        }
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId < 0L) return Result.failure()

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ChunkFinalizationEntryPoint::class.java
        )
        val sessionDao: SessionDao = entryPoint.sessionDao()
        val chunkDao: ChunkDao = entryPoint.chunkDao()
        val appPreferencesRepository: AppPreferencesRepository = entryPoint.appPreferencesRepository()
        val appEventLogger: AppEventLogger = entryPoint.appEventLogger()

        // am3-5 (spec-gate finding): MUST run before anything else touches the session/chunks.
        // This worker fires ~15s after every session starts, healthy or not (see class KDoc) — a
        // fresh heartbeat means the service is genuinely still alive right now, so this firing is
        // just the worker's normal early trigger on a healthy session, not a crash-recovery run.
        // Bail out immediately without marking the session STOPPED or sweeping any chunk.
        //
        // (code review, am3-5, patch 4): a transient DataStore read failure here must NOT skip
        // the entire recovery sweep below (including the pre-existing am1-3 Whisper/Supabase
        // sweeps) — erring toward running recovery is the safer default than silently no-op'ing
        // on every future firing for this session. A read failure is therefore treated as "no
        // heartbeat available", which resolves to `true` (stale) below the same way a real
        // never-recorded heartbeat (`0L` default) already does.
        val lastHeartbeat: Long? = try {
            appPreferencesRepository.lastHeartbeatAt.first()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read heartbeat preference — treating as stale so the recovery sweep still runs", e)
            null
        }
        val now = SystemClock.elapsedRealtime()
        if (lastHeartbeat != null && !isHeartbeatStale(lastHeartbeat, now)) {
            return Result.success()
        }

        val session = sessionDao.getById(sessionId)
        if (session == null || session.state == SessionState.STOPPED) return Result.success()

        sessionDao.updateState(sessionId, SessionState.STOPPED)

        val recoveredWhisperChunks = chunkDao.getChunksByStatus(ChunkStatus.PENDING)
            .filter { it.sessionId == sessionId }
        recoveredWhisperChunks.forEach { chunkDao.updateStatus(it.id, ChunkStatus.FAILED) }

        // am1-3 (FR8): analogous sweep for the independent supabaseUploadStatus column — a chunk
        // stuck UPLOADING means the process died mid-upload, never a confirmed DONE. Revert it to
        // FAILED (not PENDING) so SupabaseRetryWorker's own FAILED-polling sweep actually picks
        // it back up — exactly mirroring how the Whisper sweep above reverts to FAILED for
        // TranscriptRetryWorker, never leaving it in PENDING with nothing left to re-enqueue it.
        val recoveredSupabaseChunks = chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.UPLOADING)
            .filter { needsSupabaseUploadRecovery(it, sessionId) }
        recoveredSupabaseChunks.forEach { chunkDao.updateSupabaseUploadStatus(it.id, ChunkStatus.FAILED) }

        // am1-3 code review finding: clean up a local file orphaned by the narrow window between
        // SupabaseUploadWorker marking a chunk DONE and actually deleting the file (process died
        // in between). Reuses the same delete helper SupabaseUploadWorker itself uses.
        val cleanedUpOrphans = chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.DONE)
            .filter { hasConfirmedUploadNeedingCleanup(it, sessionId) }
            .count { chunk ->
                val file = File(chunk.filePath)
                file.exists() && SupabaseUploadWorker.deleteConfirmedUploadFile(file)
            }

        // am3-5: a chunk still stuck RECORDING means the process was killed before saveChunk ever
        // ran for it — the .m4a has no moov box and is undecodable. See resolveLostChunks's KDoc
        // for why this logic is shared with AudioRecordingService.handleHardwareError instead of
        // living only here.
        val lostChunks = resolveLostChunks(chunkDao, appEventLogger, sessionId)

        Log.i(
            TAG,
            "Crash-recovery sweep for session $sessionId: " +
                "${recoveredWhisperChunks.size} Whisper chunk(s) reverted PENDING->FAILED, " +
                "${recoveredSupabaseChunks.size} Supabase chunk(s) reverted UPLOADING->FAILED, " +
                "${lostChunks.size} chunk(s) lost to a hard kill mid-recording (RECORDING->FAILED), " +
                "$cleanedUpOrphans orphaned local file(s) cleaned up for already-DONE chunks"
        )

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "${TranscriptRetryWorker.WORK_NAME_PREFIX}$sessionId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<TranscriptRetryWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(TranscriptRetryWorker.KEY_SESSION_ID, sessionId)
                        .build()
                )
                .build()
        )

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "${SupabaseRetryWorker.WORK_NAME_PREFIX}$sessionId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SupabaseRetryWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(SupabaseRetryWorker.KEY_SESSION_ID, sessionId)
                        .build()
                )
                .build()
        )

        return Result.success()
    }
}

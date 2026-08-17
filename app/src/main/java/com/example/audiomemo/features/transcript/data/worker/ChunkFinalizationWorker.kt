package com.example.audiomemo.features.transcript.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChunkFinalizationEntryPoint {
    fun sessionDao(): SessionDao
    fun chunkDao(): ChunkDao
}

/**
 * WorkManager worker enqueued every time recording starts. It runs after a short delay
 * so that a clean service stop can cancel it first. If the process is killed before the
 * service can cancel it (crash / OOM), the worker runs and:
 *  - marks the session STOPPED in Room,
 *  - marks any PENDING chunks FAILED so the upload/transcription pipeline can retry them
 *    ([TranscriptRetryWorker] polls for `FAILED`), and
 *  - marks any chunk stuck `UPLOADING` (Supabase) FAILED the same way, so [SupabaseRetryWorker]
 *    (which also polls for `FAILED`) picks it up (am1-3, FR8) — plus deletes any orphaned local
 *    file left behind by a chunk that was confirmed `DONE` but never got cleaned up before the
 *    process died (am1-3 code review finding).
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

        Log.i(
            TAG,
            "Crash-recovery sweep for session $sessionId: " +
                "${recoveredWhisperChunks.size} Whisper chunk(s) reverted PENDING->FAILED, " +
                "${recoveredSupabaseChunks.size} Supabase chunk(s) reverted UPLOADING->FAILED, " +
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

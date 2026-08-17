package com.example.audiomemo.features.cloudsync.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupabaseRetryEntryPoint {
    fun chunkDao(): ChunkDao
}

/**
 * Finds all `FAILED` (`supabaseUploadStatus`) chunks for a session and re-enqueues
 * [SupabaseUploadWorker] for each. Mirrors
 * [com.example.audiomemo.features.transcript.data.worker.TranscriptRetryWorker] exactly, but
 * operates on the independent `supabaseUploadStatus` column (never the Whisper-only `status`
 * column, see am1-2's `ChunkDao` doc / RULE ZERO wiki entry) — am1-3, closes the FR6 retry loop
 * for Supabase uploads without manual intervention.
 *
 * Enqueued by
 * [com.example.audiomemo.features.transcript.data.worker.ChunkFinalizationWorker] after
 * crash-recovery, at the same point `TranscriptRetryWorker` already is.
 *
 * Constrained to [NetworkType.CONNECTED] — never Wi-Fi-only, mirroring the FR5 decision already
 * enforced by
 * [com.example.audiomemo.features.transcript.service.AudioRecordingService.enqueueSupabaseUpload]
 * for the initial (non-retry) enqueue.
 */
class SupabaseRetryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val WORK_NAME_PREFIX = "supabase_retry_"

        /**
         * Pure predicate: true when [chunk] (already known `FAILED` via the `ChunkDao` query)
         * belongs to [sessionId] — the chunks this worker reverts to `PENDING` and re-enqueues.
         * Extracted (am1-3 code review finding) instead of an untested inline `.filter {}`, same
         * Context/DAO-free pattern as
         * [com.example.audiomemo.features.transcript.data.worker.ChunkFinalizationWorker.needsSupabaseUploadRecovery]
         * so it's unit-testable from plain-JVM `src/test`.
         */
        internal fun needsRetryEnqueue(chunk: ChunkEntity, sessionId: Long): Boolean =
            chunk.sessionId == sessionId
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (sessionId < 0L) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            SupabaseRetryEntryPoint::class.java
        )
        val chunkDao = ep.chunkDao()

        val failedChunks = chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.FAILED)
            .filter { needsRetryEnqueue(it, sessionId) }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        failedChunks.forEach { chunk ->
            chunkDao.updateSupabaseUploadStatus(chunk.id, ChunkStatus.PENDING)
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "${SupabaseUploadWorker.WORK_NAME_PREFIX}${chunk.id}",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SupabaseUploadWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        Data.Builder()
                            .putLong(SupabaseUploadWorker.KEY_CHUNK_ID, chunk.id)
                            .putLong(SupabaseUploadWorker.KEY_SESSION_ID, chunk.sessionId)
                            .build()
                    )
                    .build()
            )
        }

        return Result.success()
    }
}

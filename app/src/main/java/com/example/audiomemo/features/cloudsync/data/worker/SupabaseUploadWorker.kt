package com.example.audiomemo.features.cloudsync.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.features.cloudsync.data.SupabaseStorageRepository
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupabaseUploadEntryPoint {
    fun chunkDao(): ChunkDao
    fun supabaseStorageRepository(): SupabaseStorageRepository
    fun supabaseClient(): SupabaseClient
}

/**
 * Uploads a single finished audio chunk to Supabase Storage. Mirrors
 * [com.example.audiomemo.features.transcript.data.worker.WhisperUploadWorker]'s
 * `@EntryPoint`/`EntryPointAccessors` + `runAttemptCount < 3` retry pattern, but tracks progress
 * in the independent `supabaseUploadStatus` column (never the Whisper-only `status` column).
 *
 * Never constrained to Wi-Fi — always enqueued with `NetworkType.CONNECTED`
 * (see `AudioRecordingService.enqueueSupabaseUpload`, FR5).
 */
class SupabaseUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_CHUNK_ID = "chunk_id"
        const val KEY_SESSION_ID = "session_id"
        const val WORK_NAME_PREFIX = "supabase_upload_"
    }

    override suspend fun doWork(): Result {
        val chunkId = inputData.getLong(KEY_CHUNK_ID, -1L)
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        // Room's autogenerate ids start at 1 — 0 is never legitimate, same bound as
        // AudioRecordingService.enqueueSupabaseUpload's own <= 0L guard at enqueue time.
        if (chunkId <= 0L || sessionId <= 0L) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            SupabaseUploadEntryPoint::class.java
        )
        val chunkDao = ep.chunkDao()
        val storageRepository = ep.supabaseStorageRepository()
        val supabaseClient = ep.supabaseClient()

        val chunk = chunkDao.getChunksForSessionOnce(sessionId)
            .firstOrNull { it.id == chunkId } ?: return Result.failure()

        // Idempotent: never re-upload a chunk already confirmed DONE.
        if (chunk.supabaseUploadStatus == ChunkStatus.DONE) return Result.success()

        // No Supabase login configured yet (am1-1 not done, or session expired/signed out): fail
        // safely without marking the chunk FAILED — it stays PENDING. Retrying here via
        // WorkManager backoff would just burn battery hitting the same wall every time; instead
        // the chunk is left for a future crash-recovery sweep (am1-3, NOT YET IMPLEMENTED as of
        // this story — today nothing re-enqueues a chunk that failed for this specific reason
        // until the next app process/session restart re-triggers the upload chain some other way).
        if (supabaseClient.auth.currentSessionOrNull() == null) return Result.failure()

        chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.UPLOADING)

        val file = File(chunk.filePath)
        if (!file.exists()) {
            chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.FAILED)
            return Result.failure()
        }

        return storageRepository.uploadChunk(sessionId, chunk.chunkIndex, file).fold(
            onSuccess = {
                chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.DONE)
                Result.success()
            },
            onFailure = {
                chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.FAILED)
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }
}

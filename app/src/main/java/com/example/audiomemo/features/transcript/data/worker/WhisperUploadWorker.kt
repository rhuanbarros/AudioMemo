package com.example.audiomemo.features.transcript.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.audiomemo.BuildConfig
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.TranscriptDao
import com.example.audiomemo.data.db.entities.TranscriptEntity
import com.example.audiomemo.data.network.OpenAIApiService
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WhisperUploadEntryPoint {
    fun chunkDao(): ChunkDao
    fun transcriptDao(): TranscriptDao
    fun openAIApiService(): OpenAIApiService
    fun appEventLogger(): AppEventLogger
}

/**
 * Uploads a single audio chunk to the OpenAI Whisper API and stores the resulting
 * transcript in Room. On success the chunk is marked DONE; on failure it is marked
 * FAILED so [TranscriptRetryWorker] can pick it up later.
 */
class WhisperUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_CHUNK_ID = "chunk_id"
        const val KEY_SESSION_ID = "session_id"
        const val WORK_NAME_PREFIX = "whisper_upload_"
    }

    override suspend fun doWork(): Result {
        val chunkId = inputData.getLong(KEY_CHUNK_ID, -1L)
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        if (chunkId < 0L || sessionId < 0L) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            WhisperUploadEntryPoint::class.java
        )
        val chunkDao = ep.chunkDao()
        val transcriptDao = ep.transcriptDao()
        val apiService = ep.openAIApiService()
        val appEventLogger = ep.appEventLogger()

        val chunk = chunkDao.getChunksForSessionOnce(sessionId)
            .firstOrNull { it.id == chunkId } ?: run {
                appEventLogger.log(LogCategory.UPLOAD, "Whisper upload failed: chunk $chunkId not found")
                return Result.failure()
            }

        if (chunk.status == ChunkStatus.DONE) return Result.success()

        chunkDao.updateStatus(chunkId, ChunkStatus.UPLOADING)

        return try {
            val file = File(chunk.filePath)
            if (!file.exists()) {
                chunkDao.updateStatus(chunkId, ChunkStatus.FAILED)
                appEventLogger.log(LogCategory.UPLOAD, "Whisper upload failed: local file missing (chunk=$chunkId)")
                return Result.failure()
            }

            val requestBody = file.asRequestBody("audio/m4a".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", file.name, requestBody)
            val modelPart = MultipartBody.Part.createFormData("model", "whisper-1")

            val response = apiService.transcribeAudio(
                authHeader = "Bearer ${BuildConfig.OPENAI_API_KEY}",
                file = filePart,
                model = modelPart
            )

            transcriptDao.insert(
                TranscriptEntity(
                    sessionId = chunk.sessionId,
                    chunkIndex = chunk.chunkIndex,
                    text = response.text,
                    createdAt = System.currentTimeMillis()
                )
            )
            chunkDao.updateStatus(chunkId, ChunkStatus.DONE)
            appEventLogger.log(LogCategory.UPLOAD, "Whisper upload succeeded (chunk=$chunkId)")
            Result.success()
        } catch (e: Exception) {
            chunkDao.updateStatus(chunkId, ChunkStatus.FAILED)
            if (runAttemptCount < 3) {
                appEventLogger.log(
                    LogCategory.UPLOAD,
                    "Whisper upload failed, retrying (chunk=$chunkId, attempt=$runAttemptCount)"
                )
                Result.retry()
            } else {
                appEventLogger.log(LogCategory.UPLOAD, "Whisper upload failed permanently (chunk=$chunkId)")
                Result.failure()
            }
        }
    }
}

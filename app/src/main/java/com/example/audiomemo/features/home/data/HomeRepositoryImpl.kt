package com.example.audiomemo.features.home.data

import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker
import com.example.audiomemo.features.home.domain.HomeRepository
import com.example.audiomemo.features.home.domain.PendingUploadsInfo
import com.example.audiomemo.features.home.domain.UploadedLocalFilesInfo
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HomeRepositoryImpl @Inject constructor(
    private val chunkDao: ChunkDao
) : HomeRepository {

    private companion object {
        // Genuinely "waiting to go up" — DONE (sent) and SILENT (deliberately never queued,
        // am4-2/FR10) are excluded on purpose, see PendingUploadsInfo's KDoc.
        val PENDING_UPLOAD_STATUSES = listOf(
            ChunkStatus.PENDING,
            ChunkStatus.UPLOADING,
            ChunkStatus.FAILED
        )
    }

    override suspend fun getPendingUploadsInfo(): PendingUploadsInfo = withContext(Dispatchers.IO) {
        val chunks = PENDING_UPLOAD_STATUSES.flatMap { chunkDao.getChunksBySupabaseUploadStatus(it) }
        PendingUploadsInfo(
            count = chunks.size,
            totalBytes = chunks.sumOf { File(it.filePath).length() }
        )
    }

    override suspend fun getUploadedLocalFilesInfo(): UploadedLocalFilesInfo = withContext(Dispatchers.IO) {
        val filesStillPresent = chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.DONE)
            .map { File(it.filePath) }
            .filter { it.exists() }
        UploadedLocalFilesInfo(
            count = filesStillPresent.size,
            totalBytes = filesStillPresent.sumOf { it.length() }
        )
    }

    override suspend fun deleteUploadedLocalFiles(): Int = withContext(Dispatchers.IO) {
        chunkDao.getChunksBySupabaseUploadStatus(ChunkStatus.DONE)
            .map { File(it.filePath) }
            .count { it.exists() && SupabaseUploadWorker.deleteConfirmedUploadFile(it) }
    }
}

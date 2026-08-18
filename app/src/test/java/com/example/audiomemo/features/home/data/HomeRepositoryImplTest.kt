package com.example.audiomemo.features.home.data

import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Covers [HomeRepositoryImpl] (code review, patch J — previously zero test coverage) against a
 * hand-written in-memory [ChunkDao] fake, same pattern as `SessionStateManagerTest` (this project
 * has no Robolectric/androidTest Room infra). Uses **real temp files** (not mocked `File`s) so
 * `File.exists()`/`File.length()`/`File.delete()` — all called directly by the class under test —
 * behave exactly as they would on a device, same rationale as `SupabaseUploadWorkerTest`.
 */
class HomeRepositoryImplTest : StringSpec({

    class FakeChunkDao(private val rows: MutableList<ChunkEntity> = mutableListOf()) : ChunkDao {
        override suspend fun insert(chunk: ChunkEntity): Long {
            rows += chunk
            return chunk.id
        }

        override suspend fun update(chunk: ChunkEntity) {
            val index = rows.indexOfFirst { it.id == chunk.id }
            if (index >= 0) rows[index] = chunk
        }

        override fun getChunksForSession(sessionId: Long): Flow<List<ChunkEntity>> =
            flowOf(rows.filter { it.sessionId == sessionId })

        override suspend fun getChunksByStatus(status: ChunkStatus): List<ChunkEntity> =
            rows.filter { it.status == status }

        override suspend fun getChunksForSessionOnce(sessionId: Long): List<ChunkEntity> =
            rows.filter { it.sessionId == sessionId }

        override suspend fun getChunkByFilePathAndSession(filePath: String, sessionId: Long): ChunkEntity? =
            rows.firstOrNull { it.filePath == filePath && it.sessionId == sessionId }

        override suspend fun updateStatus(id: Long, status: ChunkStatus) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(status = status)
        }

        override suspend fun getChunksBySupabaseUploadStatus(status: ChunkStatus): List<ChunkEntity> =
            rows.filter { it.supabaseUploadStatus == status }

        override suspend fun updateSupabaseUploadStatus(id: Long, status: ChunkStatus) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(supabaseUploadStatus = status)
        }

        override suspend fun deleteForSession(sessionId: Long) {
            rows.removeAll { it.sessionId == sessionId }
        }
    }

    fun tempFile(name: String, sizeBytes: Int): File {
        val dir = Files.createTempDirectory("home-repository-impl-test").toFile()
        val file = File(dir, name)
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }

    fun chunk(
        id: Long,
        filePath: String,
        supabaseUploadStatus: ChunkStatus,
        sessionId: Long = 1L
    ) = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = id.toInt(),
        filePath = filePath,
        status = ChunkStatus.DONE,
        supabaseUploadStatus = supabaseUploadStatus
    )

    "getPendingUploadsInfo counts PENDING/UPLOADING/FAILED but excludes DONE and SILENT (am4-2/FR10)" {
        runTest {
            val pendingFile = tempFile("pending.m4a", 100)
            val uploadingFile = tempFile("uploading.m4a", 200)
            val failedFile = tempFile("failed.m4a", 300)
            val doneFile = tempFile("done.m4a", 400)
            val silentFile = tempFile("silent.m4a", 500)

            val dao = FakeChunkDao(
                mutableListOf(
                    chunk(1, pendingFile.path, ChunkStatus.PENDING),
                    chunk(2, uploadingFile.path, ChunkStatus.UPLOADING),
                    chunk(3, failedFile.path, ChunkStatus.FAILED),
                    chunk(4, doneFile.path, ChunkStatus.DONE),
                    chunk(5, silentFile.path, ChunkStatus.SILENT)
                )
            )
            val repository = HomeRepositoryImpl(dao)

            val info = repository.getPendingUploadsInfo()

            check(info.count == 3) { "expected 3 pending chunks (PENDING+UPLOADING+FAILED), got ${info.count}" }
            check(info.totalBytes == 600L) {
                "expected 100+200+300=600 bytes from the 3 pending files, got ${info.totalBytes}"
            }
        }
    }

    "getPendingUploadsInfo is zero when every chunk is DONE or SILENT" {
        runTest {
            val doneFile = tempFile("done.m4a", 10)
            val silentFile = tempFile("silent.m4a", 10)
            val dao = FakeChunkDao(
                mutableListOf(
                    chunk(1, doneFile.path, ChunkStatus.DONE),
                    chunk(2, silentFile.path, ChunkStatus.SILENT)
                )
            )
            val repository = HomeRepositoryImpl(dao)

            val info = repository.getPendingUploadsInfo()

            check(info.count == 0) { "expected 0 pending, got ${info.count}" }
            check(info.totalBytes == 0L)
        }
    }

    "getUploadedLocalFilesInfo only counts DONE chunks whose local file still exists" {
        runTest {
            val presentFile = tempFile("present.m4a", 150)
            val missingFile = tempFile("missing.m4a", 999)
            check(missingFile.delete()) { "test setup: failed to delete the file that should be missing" }

            val dao = FakeChunkDao(
                mutableListOf(
                    chunk(1, presentFile.path, ChunkStatus.DONE),
                    chunk(2, missingFile.path, ChunkStatus.DONE), // DONE but auto-delete already ran
                    chunk(3, presentFile.path, ChunkStatus.PENDING) // never DONE — must be ignored
                )
            )
            val repository = HomeRepositoryImpl(dao)

            val info = repository.getUploadedLocalFilesInfo()

            check(info.count == 1) { "expected only the DONE chunk with a present file, got ${info.count}" }
            check(info.totalBytes == 150L) { "expected 150 bytes from the one present file, got ${info.totalBytes}" }
        }
    }

    "deleteUploadedLocalFiles deletes only DONE chunks with a present file, never PENDING/FAILED/UPLOADING/SILENT" {
        runTest {
            val doneFile = tempFile("done.m4a", 10)
            val pendingFile = tempFile("pending.m4a", 10)
            val failedFile = tempFile("failed.m4a", 10)
            val uploadingFile = tempFile("uploading.m4a", 10)
            val silentFile = tempFile("silent.m4a", 10)

            val dao = FakeChunkDao(
                mutableListOf(
                    chunk(1, doneFile.path, ChunkStatus.DONE),
                    chunk(2, pendingFile.path, ChunkStatus.PENDING),
                    chunk(3, failedFile.path, ChunkStatus.FAILED),
                    chunk(4, uploadingFile.path, ChunkStatus.UPLOADING),
                    chunk(5, silentFile.path, ChunkStatus.SILENT)
                )
            )
            val repository = HomeRepositoryImpl(dao)

            val deletedCount = repository.deleteUploadedLocalFiles()

            check(deletedCount == 1) { "expected exactly 1 file deleted (the DONE one), got $deletedCount" }
            check(!doneFile.exists()) { "the DONE chunk's file must be deleted" }
            check(pendingFile.exists()) { "a PENDING chunk's file must never be touched" }
            check(failedFile.exists()) { "a FAILED chunk's file must never be touched" }
            check(uploadingFile.exists()) { "an UPLOADING chunk's file must never be touched" }
            check(silentFile.exists()) { "a SILENT chunk's file must never be touched (am4-2/FR10)" }
        }
    }

    "deleteUploadedLocalFiles returns 0 and touches nothing when there are no DONE chunks with a present file" {
        runTest {
            val pendingFile = tempFile("pending.m4a", 10)
            val dao = FakeChunkDao(mutableListOf(chunk(1, pendingFile.path, ChunkStatus.PENDING)))
            val repository = HomeRepositoryImpl(dao)

            val deletedCount = repository.deleteUploadedLocalFiles()

            check(deletedCount == 0)
            check(pendingFile.exists())
        }
    }
})

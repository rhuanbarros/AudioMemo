package com.example.audiomemo.features.transcript.manager

import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.data.db.entities.SessionEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import com.example.audiomemo.features.transcript.domain.model.SessionState
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Covers the am3-5 "no duplicate row" contract between [SessionStateManager.markChunkStarted]
 * (inserts a `RECORDING` row right when a chunk starts) and [SessionStateManager.saveChunk] (must
 * UPDATE that same row, matched by `filePath`, instead of inserting a second one for the same
 * chunk) — the "delicate point" called out in the story's Design Notes.
 *
 * Uses hand-written in-memory fakes for [ChunkDao]/[SessionDao] instead of a real Room database:
 * this project has no Robolectric/androidTest Room infra (see `ChunkFinalizationWorkerTest`'s
 * docblock for the same constraint applied elsewhere). Both DAOs are plain Kotlin interfaces —
 * the `@Dao`/`@Insert`/etc. annotations are only interpreted by the Room annotation processor when
 * generating the real `_Impl`; nothing stops a fake implementing the interface directly for a
 * plain-JVM unit test.
 */
class SessionStateManagerTest : StringSpec({

    class FakeChunkDao : ChunkDao {
        val rows = mutableListOf<ChunkEntity>()
        private var nextId = 1L

        override suspend fun insert(chunk: ChunkEntity): Long {
            val id = nextId++
            rows += chunk.copy(id = id)
            return id
        }

        override suspend fun update(chunk: ChunkEntity) {
            val index = rows.indexOfFirst { it.id == chunk.id }
            check(index >= 0) { "update() called for a chunk id that was never inserted: ${chunk.id}" }
            rows[index] = chunk
        }

        override fun getChunksForSession(sessionId: Long): Flow<List<ChunkEntity>> =
            flowOf(rows.filter { it.sessionId == sessionId })

        override suspend fun getChunksByStatus(status: ChunkStatus): List<ChunkEntity> =
            rows.filter { it.status == status }

        override suspend fun getChunksForSessionOnce(sessionId: Long): List<ChunkEntity> =
            rows.filter { it.sessionId == sessionId }

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

        override suspend fun getChunkByFilePathAndSession(filePath: String, sessionId: Long): ChunkEntity? =
            rows.firstOrNull { it.filePath == filePath && it.sessionId == sessionId }
    }

    class FakeSessionDao : SessionDao {
        val rows = mutableListOf<SessionEntity>()
        private var nextId = 1L

        override suspend fun insert(session: SessionEntity): Long {
            val id = nextId++
            rows += session.copy(id = id)
            return id
        }

        override suspend fun update(session: SessionEntity) {
            val index = rows.indexOfFirst { it.id == session.id }
            if (index >= 0) rows[index] = session
        }

        override suspend fun getById(id: Long): SessionEntity? = rows.firstOrNull { it.id == id }

        override fun getAllSessions(): Flow<List<SessionEntity>> = flowOf(rows.toList())

        override suspend fun updateState(id: Long, state: SessionState) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(state = state)
        }

        override suspend fun updateDuration(id: Long, duration: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) rows[index] = rows[index].copy(totalDuration = duration)
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun getLastActiveSession(): SessionEntity? =
            rows.filter { it.state != SessionState.STOPPED }.maxByOrNull { it.startTime }

        override suspend fun abandonOrphanedSessions() {
            rows.replaceAll { if (it.state == SessionState.RECORDING) it.copy(state = SessionState.PAUSED) else it }
        }
    }

    "markChunkStarted inserts a RECORDING row, then saveChunk UPDATEs it (no duplicate row) for the same filePath" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            val filePath = "/data/chunks/audio_chunk_1.m4a"
            val startedId = manager.markChunkStarted(filePath)

            check(chunkDao.rows.size == 1) { "markChunkStarted must create exactly one row" }
            check(chunkDao.rows.single().status == ChunkStatus.RECORDING) {
                "the row created at chunk start must be RECORDING"
            }

            val savedId = manager.saveChunk(filePath)

            check(chunkDao.rows.size == 1) {
                "saveChunk must UPDATE the existing RECORDING row, never insert a second row for " +
                    "the same filePath — found ${chunkDao.rows.size} rows instead of 1"
            }
            check(savedId == startedId) {
                "saveChunk must resolve to the same row markChunkStarted created (by filePath)"
            }
            check(chunkDao.rows.single().status == ChunkStatus.PENDING) {
                "a chunk that finishes normally must end up PENDING, ready for the existing upload pipeline"
            }
        }
    }

    "saveChunk falls back to inserting when no RECORDING row exists for that filePath (defensive path)" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            // markChunkStarted was never called for this filePath (e.g. wiring gap) — saveChunk
            // must still record the chunk rather than silently losing it.
            val savedId = manager.saveChunk("/data/chunks/audio_chunk_orphan.m4a")

            check(chunkDao.rows.size == 1) { "the fallback insert must still create exactly one row" }
            check(savedId > 0L) { "the fallback insert must return a valid id" }
            check(chunkDao.rows.single().status == ChunkStatus.PENDING) {
                "the fallback-inserted row must be PENDING, matching the normal finished-chunk state"
            }
        }
    }

    "two chunks in the same session each get their own row, in order, with no cross-contamination" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            manager.markChunkStarted("/data/chunks/chunk_0.m4a")
            manager.saveChunk("/data/chunks/chunk_0.m4a")
            manager.markChunkStarted("/data/chunks/chunk_1.m4a")
            manager.saveChunk("/data/chunks/chunk_1.m4a")

            check(chunkDao.rows.size == 2) { "each chunk must end up as exactly one row" }
            check(chunkDao.rows.all { it.status == ChunkStatus.PENDING }) {
                "both chunks finished normally and must both be PENDING"
            }
            val indices = chunkDao.rows.sortedBy { it.filePath }.map { it.chunkIndex }
            check(indices == listOf(0, 1)) { "chunkIndex must be assigned in creation order: $indices" }
        }
    }

    "saveChunk never matches a same-filePath row from a different session (patch 3, code review)" {
        runTest {
            val chunkDao = FakeChunkDao()
            val filePath = "/data/chunks/audio_chunk_20260817_120000.m4a"
            // Simulate a stale RECORDING row orphaned by a different, earlier session — same
            // second-granularity filePath, never swept. sessionId is deliberately far from
            // FakeSessionDao's own id sequence (which starts at 1) so it can never collide with
            // the id startSession() is about to assign below.
            chunkDao.rows += ChunkEntity(
                id = 999L,
                sessionId = 555L,
                chunkIndex = 0,
                filePath = filePath,
                status = ChunkStatus.RECORDING
            )

            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession() // a new, different session (id 1 in this fresh fake's sequence)
            manager.markChunkStarted(filePath)
            val savedId = manager.saveChunk(filePath)

            check(chunkDao.rows.size == 2) {
                "the stale other-session row must be left untouched and a new row created for " +
                    "the current session — found ${chunkDao.rows.size} row(s)"
            }
            val staleRow = chunkDao.rows.first { it.id == 999L }
            check(staleRow.status == ChunkStatus.RECORDING) {
                "the other session's row must never be touched by this session's saveChunk"
            }
            val currentRow = chunkDao.rows.first { it.id == savedId }
            check(currentRow.sessionId == manager.currentSessionId) {
                "saveChunk must resolve to the row belonging to the CURRENT session"
            }
            check(currentRow.status == ChunkStatus.PENDING) {
                "the current session's chunk must still end up PENDING via the normal update path"
            }
        }
    }

    // am4-2, FR9/FR10: saveChunk(wasSilent = true) must persist supabaseUploadStatus = SILENT
    // (never DONE — see ChunkStatus.SILENT's KDoc for why reusing DONE would be unsafe here),
    // while leaving the Whisper-only status column on the normal PENDING path.

    "saveChunk(wasSilent = true) persists supabaseUploadStatus = SILENT on the normal update path" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            val filePath = "/data/chunks/audio_chunk_silent.m4a"
            manager.markChunkStarted(filePath)
            manager.saveChunk(filePath, wasSilent = true)

            val row = chunkDao.rows.single()
            check(row.supabaseUploadStatus == ChunkStatus.SILENT) {
                "expected supabaseUploadStatus = SILENT for a silent chunk, got ${row.supabaseUploadStatus}"
            }
            check(row.status == ChunkStatus.PENDING) {
                "the Whisper-only status column must stay on the normal PENDING path regardless " +
                    "of wasSilent — am4-2 only changes the Supabase upload leg"
            }
        }
    }

    "saveChunk(wasSilent = true) persists supabaseUploadStatus = SILENT on the defensive insert fallback path" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            // markChunkStarted never called for this filePath — exercises the fallback insert.
            manager.saveChunk("/data/chunks/audio_chunk_silent_orphan.m4a", wasSilent = true)

            val row = chunkDao.rows.single()
            check(row.supabaseUploadStatus == ChunkStatus.SILENT) {
                "expected supabaseUploadStatus = SILENT via the fallback insert path too, got " +
                    "${row.supabaseUploadStatus}"
            }
        }
    }

    "saveChunk defaults to wasSilent = false, preserving the pre-am4-2 supabaseUploadStatus = PENDING behavior" {
        runTest {
            val chunkDao = FakeChunkDao()
            val manager = SessionStateManager(FakeSessionDao(), chunkDao)
            manager.startSession()

            val filePath = "/data/chunks/audio_chunk_normal.m4a"
            manager.markChunkStarted(filePath)
            manager.saveChunk(filePath)

            check(chunkDao.rows.single().supabaseUploadStatus == ChunkStatus.PENDING) {
                "a normal (non-silent) chunk must still end up supabaseUploadStatus = PENDING, " +
                    "ready for the existing Supabase upload pipeline"
            }
        }
    }
})

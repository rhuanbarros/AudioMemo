package com.example.audiomemo.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {

    @Insert
    suspend fun insert(chunk: ChunkEntity): Long

    @Update
    suspend fun update(chunk: ChunkEntity)

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY chunkIndex ASC")
    fun getChunksForSession(sessionId: Long): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE status = :status")
    suspend fun getChunksByStatus(status: ChunkStatus): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE sessionId = :sessionId ORDER BY chunkIndex ASC")
    suspend fun getChunksForSessionOnce(sessionId: Long): List<ChunkEntity>

    /**
     * Looks up the row created by [SessionStateManager.markChunkStarted][com.example.audiomemo.features.transcript.manager.SessionStateManager.markChunkStarted]
     * so [SessionStateManager.saveChunk][com.example.audiomemo.features.transcript.manager.SessionStateManager.saveChunk]
     * can UPDATE it instead of inserting a second row for the same chunk (am3-5). `filePath` is
     * stable from the start of a chunk to its finalization, unlike the row's `id` (unknown to the
     * finalization call site until this lookup runs).
     *
     * Scoped to [sessionId] (code review, am3-5, patch 3): `filePath` has no unique index and its
     * timestamp component is only second-granularity — a collision against a stale, unswept row
     * from a *different* session is plausible (rapid pause/resume churn, or a previously-orphaned
     * row). An unscoped lookup could silently UPDATE the wrong row instead of falling back to a
     * fresh insert for the current session's chunk.
     */
    @Query("SELECT * FROM chunks WHERE filePath = :filePath AND sessionId = :sessionId LIMIT 1")
    suspend fun getChunkByFilePathAndSession(filePath: String, sessionId: Long): ChunkEntity?

    @Query("UPDATE chunks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ChunkStatus)

    @Query("SELECT * FROM chunks WHERE supabaseUploadStatus = :status")
    suspend fun getChunksBySupabaseUploadStatus(status: ChunkStatus): List<ChunkEntity>

    @Query("UPDATE chunks SET supabaseUploadStatus = :status WHERE id = :id")
    suspend fun updateSupabaseUploadStatus(id: Long, status: ChunkStatus)

    @Query("DELETE FROM chunks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

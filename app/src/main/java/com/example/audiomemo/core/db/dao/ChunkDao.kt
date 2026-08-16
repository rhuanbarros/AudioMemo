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

    @Query("UPDATE chunks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ChunkStatus)

    @Query("SELECT * FROM chunks WHERE supabaseUploadStatus = :status")
    suspend fun getChunksBySupabaseUploadStatus(status: ChunkStatus): List<ChunkEntity>

    @Query("UPDATE chunks SET supabaseUploadStatus = :status WHERE id = :id")
    suspend fun updateSupabaseUploadStatus(id: Long, status: ChunkStatus)

    @Query("DELETE FROM chunks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

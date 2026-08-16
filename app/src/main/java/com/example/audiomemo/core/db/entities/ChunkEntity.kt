package com.example.audiomemo.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val chunkIndex: Int,
    val filePath: String,
    val status: ChunkStatus,
    val overlapMs: Int = 0,
    /**
     * Tracks the chunk's upload to Supabase Storage — independent of [status], which belongs
     * exclusively to the Whisper transcription pipeline (see am1-2 story / RULE ZERO wiki entry).
     */
    val supabaseUploadStatus: ChunkStatus = ChunkStatus.PENDING
)

package com.example.audiomemo.features.transcript.domain.model

enum class ChunkStatus {
    /**
     * The chunk's Room row was created right when recording started (am3-5), before it has
     * finished. Never a terminal state on its own — either transitions to [PENDING] when
     * [com.example.audiomemo.features.transcript.manager.SessionStateManager.saveChunk] runs
     * normally, or is swept to [FAILED] by [com.example.audiomemo.features.transcript.data.worker.ChunkFinalizationWorker]
     * if it's still `RECORDING` when the owning session is confirmed dead (process killed
     * mid-chunk — the underlying `.m4a` has no `moov` box and is undecodable).
     */
    RECORDING,
    PENDING,
    UPLOADING,
    DONE,
    FAILED
}

data class Chunk(
    val id: Long = 0,
    val sessionId: Long,
    val chunkIndex: Int,
    val filePath: String,
    val status: ChunkStatus,
    val overlapMs: Int = 0
)

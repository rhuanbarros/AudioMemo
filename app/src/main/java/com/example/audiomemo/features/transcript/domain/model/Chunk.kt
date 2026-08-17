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
    FAILED,
    /**
     * `supabaseUploadStatus`-only terminal state (am4-2, FR10) for a chunk whose amplitude never
     * crossed [com.example.audiomemo.features.transcript.manager.AudioRecorderManager.Companion.SILENCE_AMPLITUDE_THRESHOLD]
     * during its whole recording — never enqueued for Supabase upload, but the local file is kept
     * exactly like any other chunk (deleted only by the normal post-upload-confirmation path,
     * which a `SILENT` chunk never reaches).
     *
     * Deliberately a **new** value instead of reusing [DONE] (the Design Notes' preferred, lower-
     * footprint option): [DONE] already means "confirmed uploaded" to
     * [com.example.audiomemo.features.transcript.data.worker.ChunkFinalizationWorker]'s
     * crash-recovery sweep, which treats any `supabaseUploadStatus == DONE` chunk still holding a
     * local file as an *orphan* left behind by [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker]
     * dying between marking `DONE` and deleting the file — and deletes it. Reusing `DONE` for a
     * silent chunk (whose file is deliberately kept forever) would make that sweep delete the
     * local audio on the very next crash-recovery run, directly violating this story's "never
     * apagado localmente só por ser silencioso" boundary. `SILENT` is invisible to every existing
     * `supabaseUploadStatus`-based sweep/query (`UPLOADING`→retry, `DONE`→orphan-cleanup,
     * `FAILED`→retry), which is exactly the "never touched again" behavior this state needs.
     */
    SILENT
}

data class Chunk(
    val id: Long = 0,
    val sessionId: Long,
    val chunkIndex: Int,
    val filePath: String,
    val status: ChunkStatus,
    val overlapMs: Int = 0
)

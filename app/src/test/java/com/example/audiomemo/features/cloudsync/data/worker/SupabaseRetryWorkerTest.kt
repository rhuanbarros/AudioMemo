package com.example.audiomemo.features.cloudsync.data.worker

import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [SupabaseRetryWorker.needsRetryEnqueue] (am1-3 code review finding) — previously an
 * untested inline `.filter { it.sessionId == sessionId }` in [SupabaseRetryWorker.doWork].
 *
 * **Why this doesn't drive [SupabaseRetryWorker.doWork] directly:** same constraint as every
 * other worker test in this project — `doWork()` needs a real/Robolectric `Context` for
 * `EntryPointAccessors.fromApplication`, which this project doesn't have (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2).
 */
class SupabaseRetryWorkerTest : StringSpec({

    fun chunk(id: Long = 1L, sessionId: Long = 100L) = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = 0,
        filePath = "/data/chunks/$id.m4a",
        status = ChunkStatus.DONE,
        supabaseUploadStatus = ChunkStatus.FAILED
    )

    "needsRetryEnqueue is true for a FAILED chunk belonging to the session being retried" {
        val sameSessionChunk = chunk(sessionId = 100L)

        val needsRetry = SupabaseRetryWorker.needsRetryEnqueue(sameSessionChunk, sessionId = 100L)

        check(needsRetry) { "a FAILED chunk in the target session must be re-enqueued" }
    }

    "needsRetryEnqueue is false for a chunk belonging to a different session (never cross-session)" {
        val otherSessionChunk = chunk(sessionId = 999L)

        val needsRetry = SupabaseRetryWorker.needsRetryEnqueue(otherSessionChunk, sessionId = 100L)

        check(!needsRetry) { "must never re-enqueue a chunk that belongs to a different session" }
    }
})

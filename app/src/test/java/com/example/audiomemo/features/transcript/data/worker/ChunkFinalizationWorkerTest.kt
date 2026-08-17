package com.example.audiomemo.features.transcript.data.worker

import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [ChunkFinalizationWorker.needsSupabaseUploadRecovery] and
 * [ChunkFinalizationWorker.hasConfirmedUploadNeedingCleanup] (am1-3, FR8) — the crash-recovery
 * predicates that (1) revert a chunk stuck `UPLOADING` (process died mid-upload) to `FAILED` so
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker] (which polls
 * `FAILED`) picks it back up, and (2) flag an already-`DONE` chunk whose local file might be an
 * orphan (process died between marking `DONE` and deleting the file) — both scoped to the session
 * being finalized.
 *
 * **Why this doesn't drive [ChunkFinalizationWorker.doWork] directly:** that method needs a real
 * (or Robolectric-simulated) `android.content.Context` for `EntryPointAccessors.fromApplication`
 * and a live `WorkManager`, and this project has no Robolectric/androidTest WorkManager infra (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same constraint
 * applies here). Both predicates were extracted as pure, DAO/Context-free functions so the actual
 * recovery *conditions* are testable from plain-JVM `src/test`.
 */
class ChunkFinalizationWorkerTest : StringSpec({

    fun chunk(
        id: Long = 1L,
        sessionId: Long = 100L,
        supabaseUploadStatus: ChunkStatus = ChunkStatus.PENDING
    ) = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = 0,
        filePath = "/data/chunks/$id.m4a",
        status = ChunkStatus.DONE,
        supabaseUploadStatus = supabaseUploadStatus
    )

    "needsSupabaseUploadRecovery is true for a chunk stuck UPLOADING in the finalized session" {
        val stuckChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.UPLOADING)

        val needsRecovery =
            ChunkFinalizationWorker.needsSupabaseUploadRecovery(stuckChunk, sessionId = 100L)

        check(needsRecovery) {
            "a chunk left UPLOADING when the process died must be flagged for crash recovery"
        }
    }

    "needsSupabaseUploadRecovery is false for a chunk from a different session (never cross-session)" {
        val otherSessionChunk = chunk(sessionId = 999L, supabaseUploadStatus = ChunkStatus.UPLOADING)

        val needsRecovery =
            ChunkFinalizationWorker.needsSupabaseUploadRecovery(otherSessionChunk, sessionId = 100L)

        check(!needsRecovery) {
            "the sweep must never revert a chunk that belongs to a different session"
        }
    }

    "needsSupabaseUploadRecovery is false for a chunk already DONE (never touch a confirmed upload)" {
        val doneChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.DONE)

        val needsRecovery =
            ChunkFinalizationWorker.needsSupabaseUploadRecovery(doneChunk, sessionId = 100L)

        check(!needsRecovery) { "a confirmed DONE upload must never be reverted" }
    }

    "needsSupabaseUploadRecovery is false for a chunk already PENDING (nothing to recover)" {
        val pendingChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.PENDING)

        val needsRecovery =
            ChunkFinalizationWorker.needsSupabaseUploadRecovery(pendingChunk, sessionId = 100L)

        check(!needsRecovery) { "a chunk that's already PENDING needs no recovery action" }
    }

    "needsSupabaseUploadRecovery is false for a chunk already FAILED (SupabaseRetryWorker's job, not this sweep's)" {
        val failedChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.FAILED)

        val needsRecovery =
            ChunkFinalizationWorker.needsSupabaseUploadRecovery(failedChunk, sessionId = 100L)

        check(!needsRecovery) {
            "a chunk already FAILED is SupabaseRetryWorker's responsibility, not the crash-recovery sweep's"
        }
    }

    "hasConfirmedUploadNeedingCleanup is true for a DONE chunk in the finalized session" {
        val doneChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.DONE)

        val needsCleanup =
            ChunkFinalizationWorker.hasConfirmedUploadNeedingCleanup(doneChunk, sessionId = 100L)

        check(needsCleanup) {
            "a DONE chunk in the finalized session is a candidate for orphaned-file cleanup " +
                "(actual file existence is checked separately, with real I/O, at the call site)"
        }
    }

    "hasConfirmedUploadNeedingCleanup is false for a DONE chunk from a different session" {
        val otherSessionChunk = chunk(sessionId = 999L, supabaseUploadStatus = ChunkStatus.DONE)

        val needsCleanup =
            ChunkFinalizationWorker.hasConfirmedUploadNeedingCleanup(otherSessionChunk, sessionId = 100L)

        check(!needsCleanup) { "must never touch a DONE chunk's file from a different session" }
    }

    "hasConfirmedUploadNeedingCleanup is false for a chunk that isn't DONE yet" {
        val uploadingChunk = chunk(sessionId = 100L, supabaseUploadStatus = ChunkStatus.UPLOADING)

        val needsCleanup =
            ChunkFinalizationWorker.hasConfirmedUploadNeedingCleanup(uploadingChunk, sessionId = 100L)

        check(!needsCleanup) {
            "only a confirmed DONE upload can have an orphaned file to clean up"
        }
    }
})

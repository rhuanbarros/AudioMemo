package com.example.audiomemo.features.transcript.data.worker

import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [ChunkFinalizationWorker.needsSupabaseUploadRecovery],
 * [ChunkFinalizationWorker.hasConfirmedUploadNeedingCleanup] (am1-3, FR8),
 * [ChunkFinalizationWorker.isHeartbeatStale] and [ChunkFinalizationWorker.needsLostChunkRecovery]
 * (am3-5) — the crash-recovery predicates that (1) revert a chunk stuck `UPLOADING` (process died
 * mid-upload) to `FAILED` so [com.example.audiomemo.features.cloudsync.data.worker.SupabaseRetryWorker]
 * (which polls `FAILED`) picks it back up, (2) flag an already-`DONE` chunk whose local file might
 * be an orphan (process died between marking `DONE` and deleting the file), (3) decide whether the
 * worker's ~15s-after-start trigger is a real crash-recovery run or just its normal early firing
 * on a healthy session (the am3-5 spec-gate false-positive fix), and (4) flag a chunk still stuck
 * `RECORDING` (killed before it ever finished) — all scoped to the session being finalized.
 *
 * **Why this doesn't drive [ChunkFinalizationWorker.doWork] directly:** that method needs a real
 * (or Robolectric-simulated) `android.content.Context` for `EntryPointAccessors.fromApplication`
 * and a live `WorkManager`, and this project has no Robolectric/androidTest WorkManager infra (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same constraint
 * applies here). All four predicates were extracted as pure, DAO/Context-free functions so the
 * actual recovery *conditions* are testable from plain-JVM `src/test`.
 */
class ChunkFinalizationWorkerTest : StringSpec({

    fun chunk(
        id: Long = 1L,
        sessionId: Long = 100L,
        status: ChunkStatus = ChunkStatus.DONE,
        supabaseUploadStatus: ChunkStatus = ChunkStatus.PENDING
    ) = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = 0,
        filePath = "/data/chunks/$id.m4a",
        status = status,
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

    // ── am3-5: heartbeat-freshness guard (spec-gate false-positive fix) ─────────────────────────

    val threshold = RecordingWatchdogWorker.HEARTBEAT_STALE_THRESHOLD_MS

    "isHeartbeatStale is false when the heartbeat is fresh (healthy session, worker's normal ~15s trigger)" {
        val now = 1_000_000L
        val freshHeartbeat = now - (threshold / 2)

        val stale = ChunkFinalizationWorker.isHeartbeatStale(freshHeartbeat, now)

        check(!stale) {
            "a fresh heartbeat means the service is genuinely alive — this must never read as a crash"
        }
    }

    "isHeartbeatStale is false right at the threshold boundary (not yet stale)" {
        val now = 1_000_000L
        val boundaryHeartbeat = now - threshold

        val stale = ChunkFinalizationWorker.isHeartbeatStale(boundaryHeartbeat, now)

        check(!stale) { "exactly at the threshold is not yet stale (strictly greater-than check)" }
    }

    "isHeartbeatStale is true when the heartbeat is older than the threshold (service genuinely dead)" {
        val now = 1_000_000L
        val staleHeartbeat = now - threshold - 1

        val stale = ChunkFinalizationWorker.isHeartbeatStale(staleHeartbeat, now)

        check(stale) { "a heartbeat older than the threshold means the service is genuinely dead" }
    }

    "isHeartbeatStale is true when now is before lastHeartbeatAt (device reboot, patch 2, code review)" {
        // elapsedRealtime() resets to ~0 on reboot but lastHeartbeatAt is persisted and survives
        // it — a process killed at/around a reboot must still read as stale, not "fresh" just
        // because the subtraction went negative.
        val lastHeartbeatBeforeReboot = 5_000_000L
        val nowAfterReboot = 10_000L

        val stale = ChunkFinalizationWorker.isHeartbeatStale(lastHeartbeatBeforeReboot, nowAfterReboot)

        check(stale) {
            "a backward clock jump (reboot resetting elapsedRealtime) must read as stale, never fresh"
        }
    }

    "isHeartbeatStale is true when no heartbeat was ever recorded (defaults to 0L)" {
        val now = 1_000_000L

        val stale = ChunkFinalizationWorker.isHeartbeatStale(lastHeartbeatAt = 0L, now = now)

        check(stale) {
            "a never-recorded heartbeat (0L default) reads as infinitely stale, same as the watchdog's own default"
        }
    }

    // ── am3-5: lost-chunk (RECORDING) sweep ──────────────────────────────────────────────────────

    "needsLostChunkRecovery is true for a chunk stuck RECORDING in the finalized session" {
        val stuckChunk = chunk(sessionId = 100L, status = ChunkStatus.RECORDING)

        val needsRecovery = ChunkFinalizationWorker.needsLostChunkRecovery(stuckChunk, sessionId = 100L)

        check(needsRecovery) {
            "a chunk still RECORDING when the session is confirmed dead must be flagged as lost"
        }
    }

    "needsLostChunkRecovery is false for a RECORDING chunk from a different session (never cross-session)" {
        val otherSessionChunk = chunk(sessionId = 999L, status = ChunkStatus.RECORDING)

        val needsRecovery =
            ChunkFinalizationWorker.needsLostChunkRecovery(otherSessionChunk, sessionId = 100L)

        check(!needsRecovery) {
            "the lost-chunk sweep must never touch a chunk that belongs to a different session"
        }
    }

    "needsLostChunkRecovery is false for a chunk that already finished normally (PENDING)" {
        val finishedChunk = chunk(sessionId = 100L, status = ChunkStatus.PENDING)

        val needsRecovery = ChunkFinalizationWorker.needsLostChunkRecovery(finishedChunk, sessionId = 100L)

        check(!needsRecovery) {
            "a chunk that finished normally (saveChunk ran, status PENDING) was never lost"
        }
    }

    "needsLostChunkRecovery is false for a chunk already FAILED (not this sweep's job again)" {
        val failedChunk = chunk(sessionId = 100L, status = ChunkStatus.FAILED)

        val needsRecovery = ChunkFinalizationWorker.needsLostChunkRecovery(failedChunk, sessionId = 100L)

        check(!needsRecovery) { "a chunk already FAILED needs no further action from this sweep" }
    }
})

package com.example.audiomemo.features.transcript.data.worker

import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [RecordingWatchdogWorker.needsRestart] (am3-2, FR2) — the pure predicate that decides
 * whether the always-on watchdog restarts [com.example.audiomemo.features.transcript.service.AudioRecordingService] —
 * and [RecordingWatchdogWorker.needsGlobalRetryEnqueue] (hotfix,
 * `am-hotfix-periodic-supabase-retry-sweep`) — the pure predicate for the watchdog's second
 * responsibility, the global `FAILED` Supabase-upload retry sweep.
 *
 * **Why this doesn't drive [RecordingWatchdogWorker.doWork] directly:** that method needs a real
 * (or Robolectric-simulated) `android.content.Context` for `EntryPointAccessors.fromApplication`
 * and a live `WorkManager`, and this project has no Robolectric/androidTest WorkManager infra (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same constraint
 * applies here). The restart *condition* was extracted as a pure, Context/DAO-free function so
 * it's testable from plain-JVM `src/test`.
 */
class RecordingWatchdogWorkerTest : StringSpec({

    val threshold = RecordingWatchdogWorker.HEARTBEAT_STALE_THRESHOLD_MS

    "needsRestart is true when the owner's intent is active and the heartbeat is stale" {
        val now = 1_000_000L
        val staleHeartbeat = now - threshold - 1

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = true,
            lastHeartbeatAt = staleHeartbeat,
            now = now
        )

        check(restart) { "a stale heartbeat with an active intent must trigger a restart" }
    }

    "needsRestart is false when the owner's intent is active but the heartbeat is fresh" {
        val now = 1_000_000L
        val freshHeartbeat = now - (threshold / 2)

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = true,
            lastHeartbeatAt = freshHeartbeat,
            now = now
        )

        check(!restart) { "a fresh heartbeat means the service is alive — never restart it" }
    }

    "needsRestart is false when the owner explicitly stopped recording, no matter how stale the heartbeat" {
        val now = 1_000_000L
        val veryStaleHeartbeat = 0L

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = false,
            lastHeartbeatAt = veryStaleHeartbeat,
            now = now
        )

        check(!restart) {
            "the watchdog must never start recording on its own when the owner's intent is false"
        }
    }

    "needsRestart is true when the intent is active and no heartbeat was ever recorded (defaults to 0L)" {
        val now = 1_000_000L

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = true,
            lastHeartbeatAt = 0L,
            now = now
        )

        check(restart) {
            "a never-recorded heartbeat (0L default) reads as infinitely stale and must trigger a restart"
        }
    }

    "needsRestart is false right at the threshold boundary (not yet stale)" {
        val now = 1_000_000L
        val boundaryHeartbeat = now - threshold

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = true,
            lastHeartbeatAt = boundaryHeartbeat,
            now = now
        )

        check(!restart) { "exactly at the threshold is not yet stale (strictly greater-than check)" }
    }

    "needsRestart is true when the clock went backward (device reboot resets elapsedRealtime)" {
        // now < lastHeartbeatAt only happens when the monotonic clock itself was reset (reboot)
        // while a persisted (and therefore pre-reboot) heartbeat value survived in DataStore.
        val lastHeartbeatAt = 500_000L
        val now = 10_000L

        val restart = RecordingWatchdogWorker.needsRestart(
            recordingShouldBeActive = true,
            lastHeartbeatAt = lastHeartbeatAt,
            now = now
        )

        check(restart) {
            "a clock that went backward must never mask a genuinely dead service as fresh"
        }
    }

    fun chunk(id: Long = 1L, sessionId: Long = 100L, supabaseUploadStatus: ChunkStatus) = ChunkEntity(
        id = id,
        sessionId = sessionId,
        chunkIndex = 0,
        filePath = "/data/chunks/$id.m4a",
        status = ChunkStatus.DONE,
        supabaseUploadStatus = supabaseUploadStatus
    )

    "needsGlobalRetryEnqueue is true for a FAILED chunk whose local file still exists" {
        val failedChunk = chunk(supabaseUploadStatus = ChunkStatus.FAILED)

        val needsRetry = RecordingWatchdogWorker.needsGlobalRetryEnqueue(failedChunk, localFileExists = true)

        check(needsRetry) { "a FAILED chunk with a recoverable local file must be re-enqueued" }
    }

    "needsGlobalRetryEnqueue is true for a FAILED chunk regardless of which session it belongs to" {
        val oldSessionChunk = chunk(sessionId = 1L, supabaseUploadStatus = ChunkStatus.FAILED)
        val currentSessionChunk = chunk(sessionId = 999L, supabaseUploadStatus = ChunkStatus.FAILED)

        check(RecordingWatchdogWorker.needsGlobalRetryEnqueue(oldSessionChunk, localFileExists = true)) {
            "the global sweep must never be scoped to a single session — unlike SupabaseRetryWorker"
        }
        check(RecordingWatchdogWorker.needsGlobalRetryEnqueue(currentSessionChunk, localFileExists = true)) {
            "the global sweep must never be scoped to a single session — unlike SupabaseRetryWorker"
        }
    }

    "needsGlobalRetryEnqueue is false for a chunk that isn't FAILED, even with its local file present" {
        val pendingChunk = chunk(supabaseUploadStatus = ChunkStatus.PENDING)
        val uploadingChunk = chunk(supabaseUploadStatus = ChunkStatus.UPLOADING)
        val doneChunk = chunk(supabaseUploadStatus = ChunkStatus.DONE)

        check(!RecordingWatchdogWorker.needsGlobalRetryEnqueue(pendingChunk, localFileExists = true)) {
            "a PENDING chunk is already queued for upload — never re-enqueue it here"
        }
        check(!RecordingWatchdogWorker.needsGlobalRetryEnqueue(uploadingChunk, localFileExists = true)) {
            "an UPLOADING chunk is mid-flight — never re-enqueue it here"
        }
        check(!RecordingWatchdogWorker.needsGlobalRetryEnqueue(doneChunk, localFileExists = true)) {
            "a DONE chunk already uploaded successfully — never re-enqueue it here"
        }
    }

    "needsGlobalRetryEnqueue is false for a FAILED chunk whose local file no longer exists" {
        val failedChunk = chunk(supabaseUploadStatus = ChunkStatus.FAILED)

        val needsRetry = RecordingWatchdogWorker.needsGlobalRetryEnqueue(failedChunk, localFileExists = false)

        check(!needsRetry) {
            "a permanently unrecoverable chunk (local file gone) must never be retried forever " +
                "— SupabaseUploadWorker would just fail it again for the same reason every tick"
        }
    }
})

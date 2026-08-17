package com.example.audiomemo.features.cloudsync.data.worker

import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker.Companion.UploadWorkerResultKind
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import io.kotest.core.spec.style.StringSpec
import java.io.File

/**
 * Covers [SupabaseUploadWorker.deleteConfirmedUploadFile] and
 * [SupabaseUploadWorker.decideUploadOutcome] (am1-3, FR7) — "delete the local file only after a
 * confirmed Supabase upload, never on failure/pending".
 *
 * **Why this doesn't drive [SupabaseUploadWorker.doWork] directly:** that method needs a real (or
 * Robolectric-simulated) `android.content.Context` for `EntryPointAccessors.fromApplication`, and
 * this project has no Robolectric/androidTest WorkManager infra (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same constraint
 * applies here). Both helpers were extracted as pure, `Context`/`Log`-free functions so the
 * actual decision — what to delete, what status to persist, what `Result` to return — is
 * testable from plain-JVM `src/test`; `doWork()` itself is a thin executor of that decision
 * (am1-3 code review finding: previously this was only "verified by reading the code").
 */
class SupabaseUploadWorkerTest : StringSpec({

    "deleteConfirmedUploadFile deletes an existing file and reports success" {
        val file = File.createTempFile("chunk-done", ".m4a")
        file.writeText("fake-audio-bytes")

        val deleted = SupabaseUploadWorker.deleteConfirmedUploadFile(file)

        check(deleted) { "expected deleteConfirmedUploadFile to report success for an existing file" }
        check(!file.exists()) { "file must no longer exist on disk after a confirmed upload" }
    }

    "deleteConfirmedUploadFile treats an already-missing file as success, not a failure to log" {
        val file = File.createTempFile("chunk-already-gone", ".m4a")
        check(file.delete()) { "test setup: failed to pre-delete the temp file" }
        check(!file.exists())

        val deleted = SupabaseUploadWorker.deleteConfirmedUploadFile(file)

        check(deleted) {
            "a file that's already gone must count as already-deleted, never a failure the " +
                "caller has to log"
        }
    }

    "decideUploadOutcome on success: DONE, deletes the file, and reports SUCCESS" {
        val decision = SupabaseUploadWorker.decideUploadOutcome(uploadSucceeded = true, runAttemptCount = 0)

        check(decision.newStatus == ChunkStatus.DONE) { "expected DONE, got ${decision.newStatus}" }
        check(decision.shouldDeleteFile) { "a confirmed successful upload must delete the local file (FR7)" }
        check(decision.resultKind == UploadWorkerResultKind.SUCCESS)
    }

    "decideUploadOutcome on failure below the retry ceiling: FAILED, never deletes, RETRY" {
        val decision = SupabaseUploadWorker.decideUploadOutcome(uploadSucceeded = false, runAttemptCount = 0)

        check(decision.newStatus == ChunkStatus.FAILED) { "expected FAILED, got ${decision.newStatus}" }
        check(!decision.shouldDeleteFile) {
            "a failed/pending upload must NEVER delete the local file (FR7/FR8) — this is the " +
                "exact invariant am1-3 exists to guarantee"
        }
        check(decision.resultKind == UploadWorkerResultKind.RETRY)
    }

    "decideUploadOutcome on failure at the retry ceiling (attempt 3): FAILED, never deletes, FAILURE" {
        val decision = SupabaseUploadWorker.decideUploadOutcome(uploadSucceeded = false, runAttemptCount = 3)

        check(decision.newStatus == ChunkStatus.FAILED)
        check(!decision.shouldDeleteFile) { "still never deletes, even after retries are exhausted" }
        check(decision.resultKind == UploadWorkerResultKind.FAILURE) {
            "expected FAILURE once runAttemptCount reaches the ceiling (mirrors runAttemptCount < 3)"
        }
    }

    // am4-1, FR8: computeUploadLatencyMs is the single source of truth for the
    // "enqueued -> confirmed" latency interpolated into the success log message.

    "computeUploadLatencyMs returns the elapsed milliseconds between enqueue and confirmation" {
        val latency = SupabaseUploadWorker.computeUploadLatencyMs(
            enqueuedAtMs = 1_000L,
            confirmedAtMs = 1_750L
        )

        check(latency == 750L) { "expected 750ms elapsed, got $latency" }
    }

    "computeUploadLatencyMs returns zero when enqueue and confirmation are simultaneous" {
        val latency = SupabaseUploadWorker.computeUploadLatencyMs(
            enqueuedAtMs = 5_000L,
            confirmedAtMs = 5_000L
        )

        check(latency == 0L) { "expected 0ms elapsed, got $latency" }
    }

    "computeUploadLatencyMs never returns a negative value, even if confirmedAtMs precedes enqueuedAtMs" {
        val latency = SupabaseUploadWorker.computeUploadLatencyMs(
            enqueuedAtMs = 10_000L,
            confirmedAtMs = 9_000L
        )

        check(latency == 0L) {
            "a clock adjustment between the two reads must never surface as a negative latency: got $latency"
        }
    }
})

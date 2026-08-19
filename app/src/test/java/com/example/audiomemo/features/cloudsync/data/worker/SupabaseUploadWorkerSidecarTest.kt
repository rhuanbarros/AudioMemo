package com.example.audiomemo.features.cloudsync.data.worker

import io.kotest.core.spec.style.StringSpec
import java.io.File

/**
 * Covers the pure sidecar-upload decision/path logic (gps-location-capture-per-chunk) extracted
 * onto [SupabaseUploadWorker]'s companion object — [SupabaseUploadWorker.doWork] itself is not
 * unit-testable in this project (no Robolectric/androidTest WorkManager infra, and
 * [io.github.jan.supabase.SupabaseClient]/`Storage` are unfakeable sealed interfaces — see
 * [com.example.audiomemo.features.cloudsync.data.SupabaseStorageRepository.uploadChunk]'s KDoc
 * for the full rationale), so these pure companion functions are what proves "sidecar upload is
 * attempted exactly once, only on a confirmed `.m4a` success, independent of the `.m4a`'s own
 * status/deletion" without needing any of that infra.
 */
class SupabaseUploadWorkerSidecarTest : StringSpec({

    "sidecarFileFor swaps the .m4a extension for .txt, same basename, same parent dir" {
        val audioFile = File("/data/user/0/com.example.audiomemo/files/12/3.m4a")

        val sidecar = SupabaseUploadWorker.sidecarFileFor(audioFile)

        check(sidecar.parentFile == audioFile.parentFile)
        check(sidecar.name == "3.txt")
    }

    "shouldAttemptSidecarUpload is true only for a confirmed SUCCESS result" {
        check(
            SupabaseUploadWorker.shouldAttemptSidecarUpload(
                SupabaseUploadWorker.Companion.UploadWorkerResultKind.SUCCESS
            )
        )
    }

    "shouldAttemptSidecarUpload is false for RETRY (never re-attempted mid-retry-loop)" {
        check(
            !SupabaseUploadWorker.shouldAttemptSidecarUpload(
                SupabaseUploadWorker.Companion.UploadWorkerResultKind.RETRY
            )
        )
    }

    "shouldAttemptSidecarUpload is false for terminal FAILURE (best-effort, at most one attempt)" {
        check(
            !SupabaseUploadWorker.shouldAttemptSidecarUpload(
                SupabaseUploadWorker.Companion.UploadWorkerResultKind.FAILURE
            )
        )
    }

    "decideUploadOutcome (the .m4a's own decision) is unaffected by anything sidecar-related" {
        // Regression guard: the .m4a's status/deletion decision must stay exactly what it was
        // before this story existed — sidecar upload is additive, never a input to this function.
        val decision = SupabaseUploadWorker.decideUploadOutcome(
            uploadSucceeded = true,
            runAttemptCount = 0
        )

        check(decision.newStatus == com.example.audiomemo.features.transcript.domain.model.ChunkStatus.DONE)
        check(decision.shouldDeleteFile)
        check(decision.resultKind == SupabaseUploadWorker.Companion.UploadWorkerResultKind.SUCCESS)
    }
})

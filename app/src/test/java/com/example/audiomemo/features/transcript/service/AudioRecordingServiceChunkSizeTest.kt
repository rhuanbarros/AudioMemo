package com.example.audiomemo.features.transcript.service

import io.kotest.core.spec.style.StringSpec

/**
 * Covers [AudioRecordingService.computeChunkSizeKb] (am4-1, FR7) — the "Chunk finalized" log
 * message's file-size-in-KB field.
 *
 * Extracted as a pure, `File`/`Context`-free function (code review, am4-1, patch 2) so the
 * rounding rule is unit-testable from plain-JVM `src/test`, mirroring
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker.computeUploadLatencyMs]
 * — same rationale as [AudioRecordingServiceConflictResolutionTest]'s docblock re: no
 * Robolectric/androidTest infra in this project.
 */
class AudioRecordingServiceChunkSizeTest : StringSpec({

    "computeChunkSizeKb reports an exact multi-KB size unchanged" {
        val sizeKb = AudioRecordingService.computeChunkSizeKb(fileSizeBytes = 2048L)

        check(sizeKb == 2L) { "expected 2KB for a 2048-byte file, got $sizeKb" }
    }

    "computeChunkSizeKb rounds a sub-1KB size up to 1, never down to 0 (patch 3)" {
        val sizeKb = AudioRecordingService.computeChunkSizeKb(fileSizeBytes = 500L)

        check(sizeKb == 1L) {
            "a genuinely tiny non-zero chunk (500 bytes) must report sizeKb=1, never sizeKb=0 " +
                "(0 must stay reserved for a truly empty file) — got $sizeKb"
        }
    }

    "computeChunkSizeKb rounds a size just over a whole KB boundary up to the next KB" {
        val sizeKb = AudioRecordingService.computeChunkSizeKb(fileSizeBytes = 2049L)

        check(sizeKb == 3L) { "expected ceiling rounding to 3KB for 2049 bytes, got $sizeKb" }
    }

    "computeChunkSizeKb reports 0 for a genuinely empty (zero-byte) file" {
        val sizeKb = AudioRecordingService.computeChunkSizeKb(fileSizeBytes = 0L)

        check(sizeKb == 0L) { "expected 0KB for a zero-byte file, got $sizeKb" }
    }
})

package com.example.audiomemo.features.transcript.service

import com.example.audiomemo.features.transcript.manager.AudioRecorderManager
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [AudioRecordingService.shouldEnqueueSupabaseUpload] (am4-2, FR9/FR10) — the actual
 * "never upload a silent chunk" decision.
 *
 * Extracted (code review, am4-2, patch 2 — confirmed independently by 2 reviewers) after this
 * exact branching lived inline-only inside the `onChunkCompleted` lambda in
 * [AudioRecordingService.onCreate], with zero test coverage: unlike every other decision in this
 * story (e.g. [AudioRecorderManager.evaluateChunkAmplitude]), inverting the condition or deleting
 * the branch entirely — defeating this story's whole point — would have been caught by no test in
 * the repo. Pure, `Context`/`WorkManager`-free `internal` function, same rationale as
 * [AudioRecordingServiceChunkSizeTest]'s docblock re: no Robolectric/androidTest infra here.
 */
class AudioRecordingServiceSilentSkipTest : StringSpec({

    "shouldEnqueueSupabaseUpload is false for a SILENT chunk — the one case this story exists to skip" {
        val result = AudioRecordingService.shouldEnqueueSupabaseUpload(
            AudioRecorderManager.ChunkAmplitudeOutcome.SILENT
        )

        check(!result) { "a SILENT chunk must never be enqueued for Supabase upload (FR10)" }
    }

    "shouldEnqueueSupabaseUpload is true for an AUDIBLE chunk — the normal, unchanged happy path" {
        val result = AudioRecordingService.shouldEnqueueSupabaseUpload(
            AudioRecorderManager.ChunkAmplitudeOutcome.AUDIBLE
        )

        check(result) { "an AUDIBLE chunk must upload normally — this story never changes that path" }
    }

    "shouldEnqueueSupabaseUpload is true for an UNMEASURED chunk — the safe default, never treated as silent" {
        val result = AudioRecordingService.shouldEnqueueSupabaseUpload(
            AudioRecorderManager.ChunkAmplitudeOutcome.UNMEASURED
        )

        check(result) {
            "UNMEASURED (couldn't read amplitude, e.g. unsupported emulator) must upload normally " +
                "— never silently discarded just because measurement was unavailable"
        }
    }
})

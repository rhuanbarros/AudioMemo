package com.example.audiomemo.features.transcript.manager

import com.example.audiomemo.features.transcript.manager.AudioRecorderManager.ChunkAmplitudeOutcome
import com.example.audiomemo.features.transcript.manager.AudioRecorderManager.Companion.SILENCE_AMPLITUDE_THRESHOLD
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [AudioRecorderManager.evaluateChunkAmplitude] (am4-2, FR9/FR10) — the pure "is this
 * chunk silent?" decision, extracted so it's unit-testable from plain-JVM `src/test` without
 * Robolectric/`MediaRecorder`, mirroring
 * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker.decideUploadOutcome].
 */
class AudioRecorderManagerTest : StringSpec({

    "a max amplitude below the threshold is SILENT" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = SILENCE_AMPLITUDE_THRESHOLD - 1)

        check(outcome == ChunkAmplitudeOutcome.SILENT) {
            "expected SILENT for a max amplitude just below the threshold, got $outcome"
        }
    }

    "a max amplitude exactly at the threshold is AUDIBLE (threshold is inclusive of the boundary)" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = SILENCE_AMPLITUDE_THRESHOLD)

        check(outcome == ChunkAmplitudeOutcome.AUDIBLE) {
            "expected AUDIBLE once the max amplitude reaches the threshold, got $outcome"
        }
    }

    "a max amplitude well above the threshold is AUDIBLE" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = 20_000)

        check(outcome == ChunkAmplitudeOutcome.AUDIBLE) { "expected AUDIBLE, got $outcome" }
    }

    "a max amplitude of exactly 0 is UNMEASURED, never SILENT (safety edge case)" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = 0)

        check(outcome == ChunkAmplitudeOutcome.UNMEASURED) {
            "a device/emulator that never reports a non-zero amplitude must be treated as " +
                "'couldn't measure', never as silence — got $outcome"
        }
    }

    "a negative max amplitude (defensive) is also treated as UNMEASURED, never SILENT" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = -1)

        check(outcome == ChunkAmplitudeOutcome.UNMEASURED) {
            "an impossible negative reading must never be treated as a confident silence " +
                "measurement — got $outcome"
        }
    }

    "a custom threshold is respected instead of the default constant" {
        val outcome = AudioRecorderManager.evaluateChunkAmplitude(maxAmplitudeObserved = 50, threshold = 100)

        check(outcome == ChunkAmplitudeOutcome.SILENT) {
            "expected SILENT against a custom threshold of 100, got $outcome"
        }
    }
})

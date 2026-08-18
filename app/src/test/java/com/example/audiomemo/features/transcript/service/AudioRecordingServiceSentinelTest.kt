package com.example.audiomemo.features.transcript.service

import com.example.audiomemo.features.transcript.manager.AudioRecorderManager
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Covers [AudioRecordingService.isRecordingUnhealthy] (am-hotfix, never-stop-recording) — the
 * pure predicate the heartbeat sentinel uses every tick to decide whether to self-heal
 * immediately, mirroring [AudioRecordingServiceChunkSizeTest]'s/[AudioRecordingServiceSilentSkipTest]'s
 * rationale for extracting pure decisions out of the service for plain-JVM `src/test` coverage.
 *
 * Also covers [AudioRecordingService.runHardwareRecoveryLoop] and
 * [AudioRecordingService.runGuarded] (code review patch 11) — real orchestration behavior (attempt
 * counting, early exit, delay timing, re-entrancy), not just the constant-value sanity checks the
 * original version of this file had.
 */
class AudioRecordingServiceSentinelTest : StringSpec({

    "recording is unhealthy when it should be active but the recorder isn't recording" {
        val unhealthy = AudioRecordingService.isRecordingUnhealthy(
            isStopped = false,
            isMediaButtonPaused = false,
            recorderIsRecording = false
        )

        check(unhealthy) {
            "a recorder that silently stopped while the service is neither stopped nor " +
                "media-button-paused must be flagged unhealthy so the sentinel self-heals"
        }
    }

    "recording is healthy when the recorder is actively recording" {
        val unhealthy = AudioRecordingService.isRecordingUnhealthy(
            isStopped = false,
            isMediaButtonPaused = false,
            recorderIsRecording = true
        )

        check(!unhealthy) { "an actively-recording recorder must never be flagged unhealthy" }
    }

    "a deliberately stopped service is never flagged unhealthy, even though the recorder isn't recording" {
        val unhealthy = AudioRecordingService.isRecordingUnhealthy(
            isStopped = true,
            isMediaButtonPaused = false,
            recorderIsRecording = false
        )

        check(!unhealthy) {
            "an explicit stop (button/ACTION_STOP) is not a hardware failure — must never trigger " +
                "the sentinel's self-heal path"
        }
    }

    "a media-button pause is never flagged unhealthy, even though the recorder isn't recording" {
        val unhealthy = AudioRecordingService.isRecordingUnhealthy(
            isStopped = false,
            isMediaButtonPaused = true,
            recorderIsRecording = false
        )

        check(!unhealthy) {
            "a user-initiated media-button pause is a legitimate reason the recorder isn't " +
                "recording — must never trigger self-heal"
        }
    }

    "the immediate-recovery bound matches this project's existing 3-attempt convention" {
        check(AudioRecordingService.HARDWARE_RECOVERY_MAX_ATTEMPTS == 3) {
            "expected 3 immediate-recovery attempts (matching SupabaseUploadWorker's existing " +
                "retry convention), got ${AudioRecordingService.HARDWARE_RECOVERY_MAX_ATTEMPTS}"
        }
    }

    "the retry delay is measured in seconds, not minutes, per the story's boundary" {
        val delayMs = AudioRecordingService.HARDWARE_RECOVERY_RETRY_DELAY_MS
        check(delayMs in 1..10_000) {
            "expected a delay on the order of seconds (<=10s), got ${delayMs}ms — the story " +
                "explicitly requires recovery to be fast (seconds, not minutes)"
        }
    }

    // ── runHardwareRecoveryLoop: real orchestration behavior (code review patch 7/9/11) ────────

    "runHardwareRecoveryLoop recovers on the very first attempt with NO delay beforehand" {
        runTest {
            var attempts = 0
            val result = AudioRecordingService.runHardwareRecoveryLoop(
                maxAttempts = 3,
                retryDelayMs = 1_000L
            ) {
                attempts++
                AudioRecorderManager.RecoveryOutcome.RECOVERED
            }

            check(result == AudioRecordingService.HardwareRecoveryResult.RECOVERED) {
                "expected RECOVERED, got $result"
            }
            check(attempts == 1) { "expected exactly 1 attempt, got $attempts" }
            check(testScheduler.currentTime == 0L) {
                "expected zero virtual time elapsed before the first attempt (patch 7: 'immediate' " +
                    "must actually try immediately) — got ${testScheduler.currentTime}ms"
            }
        }
    }

    "runHardwareRecoveryLoop recovers mid-loop, waiting exactly one retry delay between attempts" {
        runTest {
            var attempts = 0
            val result = AudioRecordingService.runHardwareRecoveryLoop(
                maxAttempts = 3,
                retryDelayMs = 1_000L
            ) {
                attempts++
                if (attempts == 1) {
                    AudioRecorderManager.RecoveryOutcome.HARDWARE_FAILURE
                } else {
                    AudioRecorderManager.RecoveryOutcome.RECOVERED
                }
            }

            check(result == AudioRecordingService.HardwareRecoveryResult.RECOVERED) {
                "expected RECOVERED on the 2nd attempt, got $result"
            }
            check(attempts == 2) { "expected exactly 2 attempts (stop retrying once recovered), got $attempts" }
            check(testScheduler.currentTime == 1_000L) {
                "expected exactly one retry delay (1000ms) elapsed between the 2 attempts, got " +
                    "${testScheduler.currentTime}ms"
            }
        }
    }

    "runHardwareRecoveryLoop exhausts every attempt and reports EXHAUSTED on repeated hardware failure" {
        runTest {
            var attempts = 0
            val result = AudioRecordingService.runHardwareRecoveryLoop(
                maxAttempts = 3,
                retryDelayMs = 1_000L
            ) {
                attempts++
                AudioRecorderManager.RecoveryOutcome.HARDWARE_FAILURE
            }

            check(result == AudioRecordingService.HardwareRecoveryResult.EXHAUSTED) {
                "expected EXHAUSTED once every attempt fails, got $result"
            }
            check(attempts == 3) { "expected exactly maxAttempts=3 attempts, got $attempts" }
            check(testScheduler.currentTime == 2_000L) {
                "expected exactly 2 retry delays (between attempts 1-2 and 2-3), got " +
                    "${testScheduler.currentTime}ms"
            }
        }
    }

    "runHardwareRecoveryLoop reports STORAGE_INSUFFICIENT (not EXHAUSTED) when the last attempt failed on storage" {
        runTest {
            val result = AudioRecordingService.runHardwareRecoveryLoop(
                maxAttempts = 2,
                retryDelayMs = 1_000L
            ) {
                AudioRecorderManager.RecoveryOutcome.STORAGE_INSUFFICIENT
            }

            check(result == AudioRecordingService.HardwareRecoveryResult.STORAGE_INSUFFICIENT) {
                "expected STORAGE_INSUFFICIENT surfaced distinctly from a generic hardware " +
                    "failure, got $result"
            }
        }
    }

    "runHardwareRecoveryLoop never runs more than maxAttempts even if the caller ignores an early RECOVERED" {
        runTest {
            var attempts = 0
            AudioRecordingService.runHardwareRecoveryLoop(maxAttempts = 1, retryDelayMs = 1_000L) {
                attempts++
                AudioRecorderManager.RecoveryOutcome.HARDWARE_FAILURE
            }
            check(attempts == 1) { "expected maxAttempts=1 to run exactly once, got $attempts" }
        }
    }

    // ── runGuarded: re-entrancy guard behavior (code review patch 3/4/11) ───────────────────────

    "runGuarded rejects a concurrent re-entrant call while the first is still in flight" {
        runTest {
            val guard = AtomicBoolean(false)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()

            val firstJob = launch {
                AudioRecordingService.runGuarded(guard) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    "first result"
                }
            }
            firstStarted.await()

            val secondResult = AudioRecordingService.runGuarded(guard) { "second result" }
            check(secondResult == null) {
                "a concurrent call while the guard is held must be rejected (return null), " +
                    "never run its action — got $secondResult"
            }

            releaseFirst.complete(Unit)
            firstJob.join()
        }
    }

    "runGuarded releases the guard only after the action fully completes, allowing a later call to run" {
        runTest {
            val guard = AtomicBoolean(false)

            val firstResult = AudioRecordingService.runGuarded(guard) { "first" }
            check(firstResult == "first") { "expected the first call's action to run, got $firstResult" }

            val secondResult = AudioRecordingService.runGuarded(guard) { "second" }
            check(secondResult == "second") {
                "once the first call's action fully completed, the guard must release so a " +
                    "later call can run — got $secondResult"
            }
        }
    }

    "runGuarded still releases the guard if the action throws" {
        runTest {
            val guard = AtomicBoolean(false)

            runCatching {
                AudioRecordingService.runGuarded(guard) { throw IllegalStateException("boom") }
            }

            val afterThrow = AudioRecordingService.runGuarded(guard) { "recovered" }
            check(afterThrow == "recovered") {
                "the guard must release even when the action throws (finally block) — a stuck " +
                    "guard would permanently block every future hardware-error recovery attempt"
            }
        }
    }
})

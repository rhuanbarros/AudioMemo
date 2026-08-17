package com.example.audiomemo.features.transcript.receiver

import io.kotest.core.spec.style.StringSpec

/**
 * Covers [BootCompletedReceiver.shouldAutoStart] (am3-3, FR3) — the pure predicate guarding the
 * entire point of this story's contract: never auto-start recording after a reboot unless the
 * owner's persisted intent was still active before the shutdown.
 *
 * **Why this doesn't drive [BootCompletedReceiver.onReceive] directly:** that method needs a real
 * (or Robolectric-simulated) `android.content.Context` for `EntryPointAccessors.fromApplication`
 * and a real broadcast delivery, and this project has no Robolectric/androidTest infra for that
 * (see `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same
 * constraint applies here, and to
 * [com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorkerTest], am3-2,
 * whose `needsRestart` predicate this test mirrors). The auto-start *condition* was extracted as
 * a pure, Context/DAO-free function so it's testable from plain-JVM `src/test` — added after code
 * review (am3-3) flagged that the un-extracted inline condition had zero test coverage, so an
 * accidental inversion would have silently broken the whole feature with nothing in CI catching
 * it.
 */
class BootCompletedReceiverTest : StringSpec({

    "shouldAutoStart is true when the owner's persisted intent was active before the reboot" {
        val result = BootCompletedReceiver.shouldAutoStart(recordingShouldBeActive = true)

        check(result) {
            "recording must auto-start after boot when the owner left it active before shutdown"
        }
    }

    "shouldAutoStart is false when the owner's persisted intent was NOT active before the reboot" {
        val result = BootCompletedReceiver.shouldAutoStart(recordingShouldBeActive = false)

        check(!result) {
            "recording must never auto-start after boot when the owner had explicitly stopped it"
        }
    }
})

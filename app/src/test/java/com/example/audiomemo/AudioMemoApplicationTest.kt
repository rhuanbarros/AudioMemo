package com.example.audiomemo

import io.kotest.core.spec.style.StringSpec

/**
 * Covers [AudioMemoApplication.shouldAutoStartOnProcessStart] (am-hotfix, FR2 gap) — the pure
 * predicate guarding the "recording auto-starts on every process start" contract:
 * `RECORD_AUDIO` must actually be granted AND the owner's persisted intent
 * ([com.example.audiomemo.core.preferences.AppPreferencesRepository.recordingShouldBeActive])
 * must still be `true`.
 *
 * **Why this doesn't drive [AudioMemoApplication.onCreate] directly:** that method needs a real
 * `android.content.Context` for `ContextCompat.checkSelfPermission`/`EntryPointAccessors`, and
 * this project has no Robolectric/androidTest infra for that (see
 * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale — same constraint
 * this project's `RecordingWatchdogWorkerTest`/`BootCompletedReceiverTest` already work around by
 * extracting a pure, Context/DAO-free predicate this test mirrors).
 */
class AudioMemoApplicationTest : StringSpec({

    "shouldAutoStartOnProcessStart is true when permission is granted and the intent is active" {
        val result = AudioMemoApplication.shouldAutoStartOnProcessStart(
            hasRecordPermission = true,
            recordingShouldBeActive = true
        )

        check(result) {
            "recording must auto-start on process start when permission is granted and the " +
                "owner's persisted intent is still active"
        }
    }

    "shouldAutoStartOnProcessStart is false when permission is NOT granted, even if the intent is active" {
        val result = AudioMemoApplication.shouldAutoStartOnProcessStart(
            hasRecordPermission = false,
            recordingShouldBeActive = true
        )

        check(!result) {
            "recording must never auto-start without RECORD_AUDIO granted, regardless of intent"
        }
    }

    "shouldAutoStartOnProcessStart is false when the owner had explicitly stopped recording" {
        val result = AudioMemoApplication.shouldAutoStartOnProcessStart(
            hasRecordPermission = true,
            recordingShouldBeActive = false
        )

        check(!result) {
            "recording must never auto-start on process start when the owner explicitly stopped it"
        }
    }

    "shouldAutoStartOnProcessStart is false when neither permission nor intent are present" {
        val result = AudioMemoApplication.shouldAutoStartOnProcessStart(
            hasRecordPermission = false,
            recordingShouldBeActive = false
        )

        check(!result)
    }
})

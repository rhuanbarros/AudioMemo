package com.example.audiomemo.features.transcript.manager

import io.kotest.core.spec.style.StringSpec
import java.lang.reflect.Modifier

/**
 * Regression lock (code review, am-hotfix never-stop-recording, patch 12): pins down that
 * [AudioInterruptionManager]'s public surface can never again silently reintroduce a pause/resume
 * trigger for audio-focus loss, mic mute, or phone-call state — exactly the behavior this story
 * removed (see that class's KDoc).
 *
 * **Why reflection, not a behavioral test:** constructing a real [AudioInterruptionManager] needs
 * a live Android `Context` (`AudioManager`/`TelephonyManager` system services), and this project
 * has no Robolectric/Mockito/MockK dependency in `src/test` to fake one (confirmed: `app/
 * build.gradle.kts` has no such dependency — same constraint documented in
 * `AudioRecordingServiceConflictResolutionTest`'s docblock for `WorkManagerTestInitHelper`). A
 * structural assertion on the compiled class's public API is the only plain-JVM-testable way to
 * lock this in, and it's sufficient for what this test actually needs to catch: nothing about this
 * class's public shape can drive a pause anymore.
 */
class AudioInterruptionManagerRegressionTest : StringSpec({

    "the only public constructor takes exactly (Context, onSourceChanged) — no pause/resume callback" {
        val publicConstructors = AudioInterruptionManager::class.java.declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) }

        check(publicConstructors.size == 1) {
            "expected exactly one public constructor, found ${publicConstructors.size}"
        }
        check(publicConstructors.single().parameterCount == 2) {
            "expected exactly 2 constructor parameters (context, onSourceChanged) — a 3rd " +
                "parameter reappearing is exactly the shape a reintroduced onPauseRequested/" +
                "onResumeRequested callback would take"
        }
    }

    "no PauseReason type exists anywhere on the class (nested or otherwise)" {
        val nestedClassNames = AudioInterruptionManager::class.java.declaredClasses.map { it.simpleName }
        check("PauseReason" !in nestedClassNames) {
            "PauseReason was removed entirely by this story (not just disabled) — its " +
                "reappearance as a nested type is itself a sign the removed pause mechanism is " +
                "being reintroduced"
        }
    }

    "the only public instance methods are start()/stop() — no pause/resume-named method exists" {
        val publicMethodNames = AudioInterruptionManager::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()

        check(publicMethodNames == setOf("start", "stop")) {
            "expected exactly {start, stop} as the public API, found $publicMethodNames — any " +
                "additional public method (e.g. a resumed pause/resume trigger) must be justified " +
                "against this story's permanent rule before being added"
        }
    }

    "no method anywhere on the class (public or private) is named like a pause/resume trigger" {
        val suspiciousMethodNames = AudioInterruptionManager::class.java.declaredMethods
            .map { it.name.lowercase() }
            .filter { "pause" in it || "resume" in it }
        check(suspiciousMethodNames.isEmpty()) {
            "found method(s) suggesting a pause/resume trigger reappeared: $suspiciousMethodNames"
        }
    }
})

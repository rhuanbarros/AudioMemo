package com.example.audiomemo.features.home.ui

import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.logging.LogEvent
import com.example.audiomemo.features.home.domain.model.HealthState
import com.example.audiomemo.features.home.ui.state.HealthBanner
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [HomeViewModel.computeHourlySegments] (am-hotfix-home-status-redesign) — the pure,
 * `Context`/DAO-free health-strip classifier, extracted for exactly the same plain-JVM
 * testability reason as every other pure decision function in this codebase (see
 * `RecordingWatchdogWorkerTest`'s docblock). Also covers [HomeViewModel.classifyEvent] and
 * [HomeViewModel.currentHealthStatus] directly where the strip-level tests wouldn't otherwise
 * exercise every branch.
 */
class HomeViewModelTest : StringSpec({

    val hourMs = 60 * 60 * 1000L
    val now = 1_700_000_000_000L
    val windowStart = now - HomeViewModel.HEALTH_STRIP_HOURS * hourMs

    fun segmentsAt(events: List<LogEvent>) = HomeViewModel.computeHourlySegments(events, now)

    "an uninterrupted recording session started well before the 24h window paints the whole strip RECORDING" {
        val events = listOf(
            LogEvent(windowStart - 10 * hourMs, LogCategory.RECORDING, "Recording started")
        )

        val segments = segmentsAt(events)

        check(segments.size == HomeViewModel.HEALTH_STRIP_HOURS) { "expected 24 buckets, got ${segments.size}" }
        check(segments.all { it.state == HealthState.RECORDING }) {
            "expected every bucket RECORDING, got ${segments.map { it.state }}"
        }
    }

    "a bucket before the very first event ever recorded is NO_DATA, never RECORDING or ERROR (fresh install)" {
        // First-ever event happens 5h before "now" — well inside the 24h window.
        val events = listOf(
            LogEvent(now - 5 * hourMs, LogCategory.RECORDING, "Recording started")
        )

        val segments = segmentsAt(events)

        val firstRecordingIndex = segments.indexOfFirst { it.state == HealthState.RECORDING }
        check(firstRecordingIndex == 19) {
            "expected recording to start at bucket 19 (5h before now), started at $firstRecordingIndex"
        }
        check(segments.take(firstRecordingIndex).all { it.state == HealthState.NO_DATA }) {
            "expected every bucket before install to be NO_DATA, got ${segments.take(firstRecordingIndex).map { it.state }}"
        }
        check(segments.drop(firstRecordingIndex).all { it.state == HealthState.RECORDING }) {
            "expected every bucket from install onward to be RECORDING, got ${segments.drop(firstRecordingIndex).map { it.state }}"
        }
    }

    "an hour with no events at all is NO_DATA when nothing ever preceded it (never invents a state)" {
        val segments = segmentsAt(emptyList())

        check(segments.all { it.state == HealthState.NO_DATA }) {
            "expected every bucket NO_DATA with zero history, got ${segments.map { it.state }}"
        }
    }

    "a hardware/permission error event marks that hour ERROR and persists forward until the next Recording started" {
        val events = listOf(
            LogEvent(windowStart - hourMs, LogCategory.RECORDING, "Recording started"),
            LogEvent(now - 3 * hourMs - 30 * 60 * 1000L, LogCategory.INTERRUPTION, "Hardware error — recording stopped")
        )

        val segments = segmentsAt(events)

        val errorBucketIndex = 20 // (now - 3h30m - windowStart) / 1h = 20.5h -> bucket 20
        check(segments[errorBucketIndex].state == HealthState.ERROR) {
            "expected bucket $errorBucketIndex to be ERROR, was ${segments[errorBucketIndex].state}"
        }
        check(segments.subList(0, errorBucketIndex).all { it.state == HealthState.RECORDING }) {
            "expected every bucket before the error to be RECORDING"
        }
        check(segments.subList(errorBucketIndex, segments.size).all { it.state == HealthState.ERROR }) {
            "expected the error to persist forward with no recovery event, got ${segments.drop(errorBucketIndex).map { it.state }}"
        }
    }

    "a paused-then-resumed interruption produces a PAUSED bucket sandwiched by RECORDING" {
        val events = listOf(
            LogEvent(windowStart - hourMs, LogCategory.RECORDING, "Recording started"),
            LogEvent(now - 10 * hourMs, LogCategory.INTERRUPTION, "Recording paused: PHONE_CALL"),
            LogEvent(now - 9 * hourMs + 5 * 60 * 1000L, LogCategory.INTERRUPTION, "Recording resumed")
        )

        val segments = segmentsAt(events)

        val pausedBucketIndex = 14 // (now - 10h - windowStart) / 1h = 14
        check(segments[pausedBucketIndex].state == HealthState.PAUSED) {
            "expected bucket $pausedBucketIndex PAUSED, was ${segments[pausedBucketIndex].state}"
        }
        check(segments[pausedBucketIndex - 1].state == HealthState.RECORDING)
        check(segments[pausedBucketIndex + 2].state == HealthState.RECORDING) {
            "expected recording to resume after the interruption cleared, was ${segments[pausedBucketIndex + 2].state}"
        }
    }

    "worst-state-wins inside a single hour: RECORDING then ERROR in the same bucket reports ERROR" {
        val bucket20Start = windowStart + 20 * hourMs
        val events = listOf(
            LogEvent(windowStart - hourMs, LogCategory.RECORDING, "Recording started"),
            LogEvent(bucket20Start + 5 * 60 * 1000L, LogCategory.RECORDING, "Recording started"), // no-op re-state
            LogEvent(bucket20Start + 40 * 60 * 1000L, LogCategory.INTERRUPTION, "Low storage — recording stopped")
        )

        val segments = segmentsAt(events)

        check(segments[20].state == HealthState.ERROR) {
            "expected the hour containing an error to report ERROR even though it also had a RECORDING event, was ${segments[20].state}"
        }
    }

    "UPLOAD-category and non-state INTERRUPTION events (e.g. audio source changed) never override the carried state" {
        val events = listOf(
            LogEvent(windowStart - hourMs, LogCategory.RECORDING, "Recording started"),
            LogEvent(now - 5 * hourMs, LogCategory.UPLOAD, "Supabase upload succeeded (chunk=1, latencyMs=200)"),
            LogEvent(now - 4 * hourMs, LogCategory.INTERRUPTION, "Audio source changed to Bluetooth")
        )

        val segments = segmentsAt(events)

        check(segments.all { it.state == HealthState.RECORDING }) {
            "expected UPLOAD/non-state events to be ignored entirely, got ${segments.map { it.state }}"
        }
    }

    "classifyEvent maps every documented state-setting message and ignores everything else" {
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.RECORDING, "Recording started")) == HealthState.RECORDING)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.RECORDING, "Recording stopped")) == HealthState.PAUSED)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.INTERRUPTION, "Recording paused: MIC_MUTED")) == HealthState.PAUSED)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.INTERRUPTION, "Recording resumed")) == HealthState.RECORDING)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.INTERRUPTION, "Battery low — recording stopped")) == HealthState.ERROR)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.INTERRUPTION, "Permission revoked — recording stopped")) == HealthState.ERROR)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.UPLOAD, "Supabase upload succeeded (chunk=1)")) == null)
        check(HomeViewModel.classifyEvent(LogEvent(0, LogCategory.INTERRUPTION, "Audio source changed to Bluetooth")) == null)
    }

    "currentHealthStatus reports the most recent classifiable event regardless of the 24h window" {
        val veryOldEvents = listOf(
            LogEvent(now - 40 * hourMs, LogCategory.RECORDING, "Recording started")
        )

        val (state, event) = HomeViewModel.currentHealthStatus(veryOldEvents, now)

        check(state == HealthState.RECORDING) { "expected RECORDING, got $state" }
        check(event?.message == "Recording started")
    }

    "currentHealthStatus is NO_DATA when there is no classifiable event at all" {
        val (state, event) = HomeViewModel.currentHealthStatus(emptyList(), now)

        check(state == HealthState.NO_DATA)
        check(event == null)
    }

    "currentHealthStatus ignores a future-dated event (clock skew) and falls back to the real most-recent one" {
        // Code review, patch D: a device clock moving backward (NTP correction) must never let a
        // stale future-dated event outrank the real most-recent classifiable one.
        val events = listOf(
            LogEvent(now - hourMs, LogCategory.RECORDING, "Recording started"),
            LogEvent(now + 10 * hourMs, LogCategory.INTERRUPTION, "Battery low — recording stopped")
        )

        val (state, event) = HomeViewModel.currentHealthStatus(events, now)

        check(state == HealthState.RECORDING) {
            "expected the future-dated ERROR event to be ignored, got $state"
        }
        check(event?.message == "Recording started")
    }

    "computeHealthBanner reports Error with the triggering event's message when the current state is ERROR" {
        val errorEvent = LogEvent(now, LogCategory.INTERRUPTION, "Hardware error — recording stopped")

        val banner = HomeViewModel.computeHealthBanner(HealthState.ERROR, errorEvent)

        check(banner is HealthBanner.Error)
        check((banner as HealthBanner.Error).detail == errorEvent.message)
    }

    "computeHealthBanner reports NoData (not Normal, not Error) when there is no classifiable history" {
        val banner = HomeViewModel.computeHealthBanner(HealthState.NO_DATA, null)

        check(banner == HealthBanner.NoData) { "expected NoData, got $banner" }
    }

    "computeHealthBanner reports Normal for both RECORDING and PAUSED" {
        check(HomeViewModel.computeHealthBanner(HealthState.RECORDING, null) == HealthBanner.Normal)
        check(HomeViewModel.computeHealthBanner(HealthState.PAUSED, null) == HealthBanner.Normal)
    }

    "computeIsLikelyOffline is false the instant a chunk first becomes pending, even with zero prior successful uploads" {
        // Code review, patch A: the un-debounced version treated lastSuccessfulUploadAtMs == null
        // as instant offline — a fresh install's very first pending chunk must NOT false-positive.
        val firstObservedNow = HomeViewModel.nextFirstPendingObservedAt(
            previous = null,
            pendingCount = 1,
            lastSuccessfulUploadAtMs = null,
            nowMs = now
        )

        val offline = HomeViewModel.computeIsLikelyOffline(
            pendingCount = 1,
            lastSuccessfulUploadAtMs = null,
            firstPendingObservedAtMs = firstObservedNow,
            nowMs = now
        )

        check(!offline) { "expected no false-positive on the very first pending chunk" }
    }

    "computeIsLikelyOffline becomes true once the pending-with-no-success condition outlasts the grace window" {
        val observedSince = now - HomeViewModel.OFFLINE_INFERENCE_WINDOW_MS - 1

        val offline = HomeViewModel.computeIsLikelyOffline(
            pendingCount = 2,
            lastSuccessfulUploadAtMs = null,
            firstPendingObservedAtMs = observedSince,
            nowMs = now
        )

        check(offline) { "expected offline once the debounce window has elapsed" }
    }

    "computeIsLikelyOffline is false once pending count drops to 0, regardless of firstPendingObservedAtMs" {
        val offline = HomeViewModel.computeIsLikelyOffline(
            pendingCount = 0,
            lastSuccessfulUploadAtMs = null,
            firstPendingObservedAtMs = now - HomeViewModel.OFFLINE_INFERENCE_WINDOW_MS - 1,
            nowMs = now
        )

        check(!offline)
    }

    "computeIsLikelyOffline is false when a success was logged within the grace window, even with stale firstPendingObservedAtMs" {
        val offline = HomeViewModel.computeIsLikelyOffline(
            pendingCount = 3,
            lastSuccessfulUploadAtMs = now - 60_000L,
            firstPendingObservedAtMs = now - HomeViewModel.OFFLINE_INFERENCE_WINDOW_MS - 1,
            nowMs = now
        )

        check(!offline) { "expected a recent success to clear the offline inference" }
    }

    "nextFirstPendingObservedAt resets to null once pending drops to 0" {
        val next = HomeViewModel.nextFirstPendingObservedAt(
            previous = now - 10 * 60_000L,
            pendingCount = 0,
            lastSuccessfulUploadAtMs = null,
            nowMs = now
        )

        check(next == null)
    }

    "nextFirstPendingObservedAt resets to null once a recent success is logged" {
        val next = HomeViewModel.nextFirstPendingObservedAt(
            previous = now - 10 * 60_000L,
            pendingCount = 2,
            lastSuccessfulUploadAtMs = now - 60_000L,
            nowMs = now
        )

        check(next == null)
    }

    "nextFirstPendingObservedAt keeps counting from the original timestamp, not the latest tick" {
        val originalTimestamp = now - 2 * 60_000L

        val next = HomeViewModel.nextFirstPendingObservedAt(
            previous = originalTimestamp,
            pendingCount = 1,
            lastSuccessfulUploadAtMs = null,
            nowMs = now
        )

        check(next == originalTimestamp) {
            "expected the tracker to keep the original observation timestamp, got $next"
        }
    }

    "mergeAllEvents dedupes overlapping live/persisted events without capping at 200" {
        val live = listOf(
            LogEvent(101L, LogCategory.RECORDING, "newest"),
            LogEvent(100L, LogCategory.RECORDING, "overlap")
        )
        val persisted = (1L..250L).map { LogEvent(it, LogCategory.UPLOAD, "historical $it") } +
            LogEvent(100L, LogCategory.RECORDING, "overlap")

        val merged = HomeViewModel.mergeAllEvents(live, persisted)

        check(merged.size == 252) {
            "expected 250 historical + 2 live with the overlap deduped (no 200 cap), got ${merged.size}"
        }
        check(merged.count { it.message == "overlap" } == 1) {
            "expected the overlapping event deduped, not duplicated"
        }
    }
})

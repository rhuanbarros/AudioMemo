package com.example.audiomemo.core.logging

import io.kotest.core.spec.style.StringSpec

/**
 * Covers am2-1's AC #3: the in-memory event list never exceeds [AppEventLogger.MAX_EVENTS] (200)
 * items — the oldest event is discarded on every `log()` call once the cap is reached.
 */
class AppEventLoggerTest : StringSpec({

    "events is empty before any log() call" {
        val logger = AppEventLogger()

        check(logger.events.value.isEmpty()) { "expected no events, got ${logger.events.value.size}" }
    }

    "log() prepends the newest event first" {
        val logger = AppEventLogger()

        logger.log(LogCategory.RECORDING, "first")
        logger.log(LogCategory.UPLOAD, "second")

        val events = logger.events.value
        check(events.size == 2) { "expected 2 events, got ${events.size}" }
        check(events[0].message == "second") { "expected newest event first, got ${events[0].message}" }
        check(events[1].message == "first") { "expected oldest event last, got ${events[1].message}" }
    }

    "log() never lets the list exceed MAX_EVENTS (200) items" {
        val logger = AppEventLogger()

        repeat(250) { i -> logger.log(LogCategory.RECORDING, "event $i") }

        val events = logger.events.value
        check(events.size == AppEventLogger.MAX_EVENTS) {
            "expected exactly ${AppEventLogger.MAX_EVENTS} events, got ${events.size}"
        }
    }

    "log() past the cap drops the oldest event, keeps the newest" {
        val logger = AppEventLogger()

        repeat(201) { i -> logger.log(LogCategory.RECORDING, "event $i") }

        val events = logger.events.value
        check(events.size == AppEventLogger.MAX_EVENTS) {
            "expected exactly ${AppEventLogger.MAX_EVENTS} events, got ${events.size}"
        }
        check(events.first().message == "event 200") {
            "expected the most recent event (index 200) first, got ${events.first().message}"
        }
        check(events.none { it.message == "event 0" }) {
            "expected the oldest event ('event 0') to have been dropped once the cap was exceeded"
        }
    }

    "category is preserved on the logged event" {
        val logger = AppEventLogger()

        logger.log(LogCategory.INTERRUPTION, "paused")

        check(logger.events.value.single().category == LogCategory.INTERRUPTION)
    }
})

package com.example.audiomemo.core.logging

import io.kotest.core.spec.style.StringSpec
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Creates a fresh temp directory for a single test — never a real Android `Context` (see class docblock). */
private fun tempLogsDir(): File = Files.createTempDirectory("app-event-logger-test").toFile()

/**
 * Polls [condition] for up to [timeoutMillis] (short sleeps in between), returning as soon as it's
 * `true`. `AppEventLogger.persist` writes via its own `ioScope` (real `Dispatchers.IO`, not a test
 * dispatcher) — there is no hook to await "the background write finished" synchronously, so tests
 * that assert on-disk content after `log()` must poll instead of assuming immediate completion.
 */
private fun pollUntil(timeoutMillis: Long = 2_000L, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20L)
    }
    return condition()
}

/**
 * Covers am2-1's AC #3 (in-memory cap) plus am2-2's file-persistence behavior:
 * [AppEventLogger.rotateAndAppend] (size-based rotation) and [AppEventLogger.formatLine].
 *
 * [AppEventLogger] takes a plain `java.io.File` (the logs directory, via `@LogsDirectory`) rather
 * than an `android.content.Context` specifically so it's constructible here without Robolectric or
 * a mocking framework (this project has neither — see `SupabaseUploadWorkerTest`'s docblock for
 * the same rationale applied elsewhere).
 */
class AppEventLoggerTest : StringSpec({

    "events is empty before any log() call" {
        val logger = AppEventLogger(tempLogsDir())

        check(logger.events.value.isEmpty()) { "expected no events, got ${logger.events.value.size}" }
    }

    "log() prepends the newest event first" {
        val logger = AppEventLogger(tempLogsDir())

        logger.log(LogCategory.RECORDING, "first")
        logger.log(LogCategory.UPLOAD, "second")

        val events = logger.events.value
        check(events.size == 2) { "expected 2 events, got ${events.size}" }
        check(events[0].message == "second") { "expected newest event first, got ${events[0].message}" }
        check(events[1].message == "first") { "expected oldest event last, got ${events[1].message}" }
    }

    "log() never lets the list exceed MAX_EVENTS (200) items" {
        val logger = AppEventLogger(tempLogsDir())

        repeat(250) { i -> logger.log(LogCategory.RECORDING, "event $i") }

        val events = logger.events.value
        check(events.size == AppEventLogger.MAX_EVENTS) {
            "expected exactly ${AppEventLogger.MAX_EVENTS} events, got ${events.size}"
        }
    }

    "log() past the cap drops the oldest event, keeps the newest" {
        val logger = AppEventLogger(tempLogsDir())

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
        val logger = AppEventLogger(tempLogsDir())

        logger.log(LogCategory.INTERRUPTION, "paused")

        check(logger.events.value.single().category == LogCategory.INTERRUPTION)
    }

    "formatLine renders timestamp | category | message" {
        val event = LogEvent(timestamp = 1_700_000_000_000L, category = LogCategory.UPLOAD, message = "chunk=42 done")

        val line = AppEventLogger.formatLine(event)

        check(line == "1700000000000 | UPLOAD | chunk=42 done") { "unexpected line: $line" }
    }

    "rotateAndAppend creates the parent directory and appends a line to a fresh file" {
        val dir = tempLogsDir()
        val logFile = File(dir, "nested/app-events.log")
        val backupFile = File(dir, "nested/app-events.log.1")

        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-1")

        check(logFile.exists()) { "expected rotateAndAppend to create the log file (and its parent dir)" }
        check(logFile.readText() == "line-1\n") { "unexpected content: ${logFile.readText()}" }
        check(!backupFile.exists()) { "no rotation should have happened on a fresh file" }
    }

    "rotateAndAppend appends without rotating while under the size threshold" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")

        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-1", maxSizeBytes = 1_000L)
        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-2", maxSizeBytes = 1_000L)

        check(logFile.readText() == "line-1\nline-2\n") { "unexpected content: ${logFile.readText()}" }
        check(!backupFile.exists()) { "no rotation should have happened while under the size threshold" }
    }

    "rotateAndAppend rotates the current file into the backup once at/over the size threshold" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")
        // "line-1\n" is 7 bytes — use a threshold it already meets so the *next* append rotates.
        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-1", maxSizeBytes = 7L)

        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-2", maxSizeBytes = 7L)

        check(backupFile.exists()) { "expected the pre-rotation content to have moved into the backup file" }
        check(backupFile.readText() == "line-1\n") { "unexpected backup content: ${backupFile.readText()}" }
        check(logFile.readText() == "line-2\n") {
            "expected the log file to start fresh with only the newest line after rotation, got: ${logFile.readText()}"
        }
    }

    "rotateAndAppend keeps only one backup — a second rotation overwrites the first" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")

        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-1", maxSizeBytes = 7L) // seeds logFile
        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-2", maxSizeBytes = 7L) // rotates line-1 -> backup
        AppEventLogger.rotateAndAppend(logFile, backupFile, "line-3", maxSizeBytes = 7L) // rotates line-2 -> backup

        check(backupFile.readText() == "line-2\n") {
            "expected the backup to hold only the most recent rotation, got: ${backupFile.readText()}"
        }
        check(logFile.readText() == "line-3\n") { "unexpected log content: ${logFile.readText()}" }
    }

    "formatLine sanitizes an embedded newline in the message so one event stays one physical line" {
        val event = LogEvent(
            timestamp = 1_700_000_000_000L,
            category = LogCategory.UPLOAD,
            message = "line one\nline two\r\nline three\rline four"
        )

        val line = AppEventLogger.formatLine(event)

        check(!line.contains('\n') && !line.contains('\r')) {
            "expected no raw newline/carriage-return characters left in the formatted line, got: $line"
        }
        check(line == "1700000000000 | UPLOAD | line one line two line three line four") {
            "unexpected line: $line"
        }
    }

    "log() writes a matching line to disk (the actual restart-history wiring, not just formatLine/rotateAndAppend in isolation)" {
        val dir = tempLogsDir()
        val logger = AppEventLogger(dir)

        logger.log(LogCategory.RECORDING, "recording started")

        val logFile = File(dir, AppEventLogger.LOG_FILE_NAME)
        val wrote = pollUntil { logFile.exists() && logFile.readText().contains("recording started") }

        check(wrote) {
            "expected log() to eventually persist a line containing the message to " +
                "${logFile.path}, got: ${if (logFile.exists()) logFile.readText() else "<file does not exist>"}"
        }
        check(logFile.readText().contains("RECORDING")) { "expected the category to be persisted too" }
    }

    "log() called concurrently from many threads never corrupts the persisted file" {
        val dir = tempLogsDir()
        val logger = AppEventLogger(dir)
        val threadCount = 25
        val executor = Executors.newFixedThreadPool(threadCount)
        val allSubmitted = CountDownLatch(threadCount)

        repeat(threadCount) { i ->
            executor.submit {
                logger.log(LogCategory.RECORDING, "concurrent-event-$i")
                allSubmitted.countDown()
            }
        }
        check(allSubmitted.await(5, TimeUnit.SECONDS)) { "test setup: not all log() calls completed in time" }
        executor.shutdown()

        val logFile = File(dir, AppEventLogger.LOG_FILE_NAME)
        val expectedMessages = (0 until threadCount).map { "concurrent-event-$it" }.toSet()
        val wroteAll = pollUntil(timeoutMillis = 5_000L) {
            logFile.exists() &&
                logFile.readLines().mapNotNull { LogFileReader.parseLine(it) }.map { it.message }.toSet() ==
                expectedMessages
        }

        val parsedLines = if (logFile.exists()) logFile.readLines() else emptyList()
        check(wroteAll) {
            "expected all $threadCount concurrent log() calls to land as distinct, uncorrupted lines " +
                "(no interleaved/partial writes from the race between rotateAndAppend callers), got " +
                "${parsedLines.size} raw lines: $parsedLines"
        }
        // Every raw line must have parsed cleanly — a corrupted interleaved write would show up here
        // as either a malformed line (parseLine returns null) or a line count mismatch.
        check(parsedLines.size == threadCount) {
            "expected exactly $threadCount physical lines with no interleaving, got ${parsedLines.size}: $parsedLines"
        }
    }
})

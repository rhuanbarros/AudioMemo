package com.example.audiomemo.core.logging

import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files

private fun tempLogsDir(): File = Files.createTempDirectory("log-file-reader-test").toFile()

/**
 * Covers [LogFileReader.parseLine] and [LogFileReader.readEvents] (am2-2, FR10) — reading and
 * parsing the file [AppEventLogger] writes back into [LogEvent]s, including the "app was
 * restarted" scenario from the story's I/O matrix.
 */
class LogFileReaderTest : StringSpec({

    "parseLine parses a well-formed line" {
        val event = LogFileReader.parseLine("1700000000000 | UPLOAD | chunk=42 done")

        check(event != null) { "expected a parsed event, got null" }
        check(event!!.timestamp == 1_700_000_000_000L)
        check(event.category == LogCategory.UPLOAD)
        check(event.message == "chunk=42 done")
    }

    "parseLine keeps a delimiter that appears again inside the message" {
        val event = LogFileReader.parseLine("1700000000000 | RECORDING | a | b | c")

        check(event != null)
        check(event!!.message == "a | b | c") { "expected the full remainder as the message, got: ${event.message}" }
    }

    "parseLine returns null for a blank line" {
        check(LogFileReader.parseLine("") == null)
        check(LogFileReader.parseLine("   ") == null)
    }

    "parseLine returns null for an unparseable timestamp" {
        check(LogFileReader.parseLine("not-a-number | UPLOAD | msg") == null)
    }

    "parseLine returns null for an unknown category" {
        check(LogFileReader.parseLine("1700000000000 | NOT_A_CATEGORY | msg") == null)
    }

    "parseLine returns null for a line missing fields (e.g. truncated by a mid-write crash)" {
        check(LogFileReader.parseLine("1700000000000 | UPLOAD") == null)
    }

    "readEvents returns an empty list when neither file exists" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")

        check(LogFileReader.readEvents(logFile, backupFile).isEmpty())
    }

    "readEvents reads the backup before the current file (oldest-first)" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")
        backupFile.writeText("1 | RECORDING | old-event\n")
        logFile.writeText("2 | RECORDING | new-event\n")

        val events = LogFileReader.readEvents(logFile, backupFile)

        check(events.size == 2) { "expected 2 events, got ${events.size}" }
        check(events[0].message == "old-event") { "expected the backup's event first (oldest), got ${events[0].message}" }
        check(events[1].message == "new-event") { "expected the current file's event last (newest), got ${events[1].message}" }
    }

    "readEvents skips unparseable lines without losing the rest of the file" {
        val dir = tempLogsDir()
        val logFile = File(dir, "app-events.log")
        val backupFile = File(dir, "app-events.log.1")
        logFile.writeText("1 | RECORDING | good-event-1\ngarbage-line\n2 | UPLOAD | good-event-2\n")

        val events = LogFileReader.readEvents(logFile, backupFile)

        check(events.map { it.message } == listOf("good-event-1", "good-event-2")) {
            "unexpected events: ${events.map { it.message }}"
        }
    }

    "readPersistedEvents (suspend entrypoint) delegates to readEvents off the caller's dispatcher" {
        runTest {
            val dir = tempLogsDir()
            File(dir, AppEventLogger.LOG_FILE_NAME).writeText("1 | RECORDING | persisted-event\n")
            val reader = LogFileReader(dir)

            val events = reader.readPersistedEvents()

            check(events.map { it.message } == listOf("persisted-event")) {
                "unexpected events: ${events.map { it.message }}"
            }
        }
    }
})

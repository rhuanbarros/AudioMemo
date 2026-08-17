package com.example.audiomemo.core.logging

import com.example.audiomemo.core.di.LogsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads back events persisted by [AppEventLogger] (am2-2, FR10) — the file-backed half of the
 * Logs screen's "history survives a restart" contract. [com.example.audiomemo.features.logs.ui.LogsViewModel]
 * loads this once at startup and merges it with [AppEventLogger.events] (the live, in-memory feed).
 */
@Singleton
class LogFileReader @Inject constructor(
    @LogsDirectory logsDir: File
) {

    companion object {
        /**
         * Parses one persisted line (`timestamp | category | message`, see
         * [AppEventLogger.formatLine]) back into a [LogEvent]. Returns `null` for anything that
         * doesn't parse cleanly — a blank line, an unknown [LogCategory], a truncated line (e.g.
         * the process died mid-`appendText`) — rather than throwing, so one bad line never loses
         * the rest of the file's history.
         *
         * `limit = 3` on the split matters: [LogEvent.message] is free text and may itself
         * contain the `" | "` delimiter (e.g. a future message like `"a | b"`) — splitting with a
         * limit keeps everything after the second delimiter as the message instead of silently
         * truncating it.
         */
        internal fun parseLine(line: String): LogEvent? {
            if (line.isBlank()) return null
            val parts = line.split(AppEventLogger.FIELD_DELIMITER, limit = 3)
            if (parts.size != 3) return null
            val timestamp = parts[0].toLongOrNull() ?: return null
            val category = try {
                LogCategory.valueOf(parts[1])
            } catch (e: IllegalArgumentException) {
                return null
            }
            return LogEvent(timestamp = timestamp, category = category, message = parts[2])
        }

        /**
         * Reads [backupFile] then [logFile] and parses each into [LogEvent]s, oldest-first —
         * [backupFile] is always the older rotation (see [AppEventLogger.rotateAndAppend]), and
         * within each file `appendText` means later lines are newer. A missing file (no history
         * yet, or never rotated) contributes nothing rather than erroring. Any read failure on a
         * given file (rare: permissions, filesystem hiccup) is swallowed and that file simply
         * contributes nothing — never crashes the caller, mirroring [AppEventLogger.persist]'s
         * "silently swallow disk failures" boundary.
         *
         * Extracted as a plain-`java.io.File` function (no `Context`) — same testability
         * rationale as [AppEventLogger.rotateAndAppend].
         */
        internal fun readEvents(logFile: File, backupFile: File): List<LogEvent> =
            readFile(backupFile) + readFile(logFile)

        private fun readFile(file: File): List<LogEvent> = try {
            if (file.exists()) file.readLines().mapNotNull(::parseLine) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val logFile = File(logsDir, AppEventLogger.LOG_FILE_NAME)
    private val backupFile = File(logsDir, AppEventLogger.BACKUP_FILE_NAME)

    /**
     * Reads and parses [logFile] + [backupFile] off the main thread, oldest-first (see
     * [readEvents]). Called once from [com.example.audiomemo.features.logs.ui.LogsViewModel]'s
     * `init`.
     */
    suspend fun readPersistedEvents(): List<LogEvent> = withContext(Dispatchers.IO) {
        readEvents(logFile, backupFile)
    }
}

package com.example.audiomemo.core.logging

import android.util.Log
import com.example.audiomemo.core.di.LogsDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, app-wide event log (am2-1, FR9), now also persisted to a local file (am2-2, FR10).
 * Holds up to [MAX_EVENTS] recent [LogEvent]s, most-recent-first, exposed as a hot [StateFlow] so
 * the Logs screen updates live without polling.
 *
 * Singleton scope (one instance for the whole process, shared by [AudioRecordingService][
 * com.example.audiomemo.features.transcript.service.AudioRecordingService] — which may outlive
 * any UI — and the Logs screen's ViewModel) is what makes events logged while no UI is open still
 * be there the next time the Logs screen is opened.
 *
 * The in-memory [events] StateFlow still loses its history on process death — that's still an
 * accepted trade-off (am2-1). What am2-2 adds is a parallel, append-only write of every event to
 * [logFile] (`context.filesDir/logs/app-events.log`, wired via [LogsDirectory]) with size-based
 * rotation, so [LogFileReader] can rebuild history across restarts for [LogsViewModel][
 * com.example.audiomemo.features.logs.ui.LogsViewModel] to merge back in.
 */
@Singleton
class AppEventLogger @Inject constructor(
    @LogsDirectory logsDir: File
) {

    companion object {
        /** Cap on in-memory events (AC #3, am2-1) — the oldest event is dropped once this is exceeded. */
        internal const val MAX_EVENTS = 200

        /** File name for the active log — lives under [LogsDirectory]. */
        internal const val LOG_FILE_NAME = "app-events.log"

        /** File name for the single rotated backup — [LOG_FILE_NAME] once it hit [MAX_FILE_SIZE_BYTES]. */
        internal const val BACKUP_FILE_NAME = "app-events.log.1"

        /**
         * Rotation threshold (am2-2 AC #2) — "atinge ~1MB" per the story's I/O matrix. Plain
         * `Long` byte count, checked against [File.length] before each write.
         */
        internal const val MAX_FILE_SIZE_BYTES = 1_000_000L

        /** Separates the three fields of a persisted line. Also used by [LogFileReader] to parse it back. */
        internal const val FIELD_DELIMITER = " | "

        private const val TAG = "AppEventLogger"

        /**
         * Formats [event] as the single line persisted to disk: `timestamp | category | message`.
         *
         * [LogEvent.message] is sanitized first — `\r\n`/`\n`/`\r` are replaced with a plain
         * space. The persisted format is one physical line per event ([LogFileReader] reads it
         * back with [File.readLines]); an embedded newline in a message would otherwise split
         * that event across two physical lines, corrupting both it and whatever follows it.
         */
        internal fun formatLine(event: LogEvent): String {
            val sanitizedMessage = event.message
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
            return "${event.timestamp}$FIELD_DELIMITER${event.category}$FIELD_DELIMITER$sanitizedMessage"
        }

        /**
         * Rotates [logFile] into [backupFile] (overwriting any previous backup) when [logFile] is
         * already at/over [maxSizeBytes], then appends [line] as a new line to (the now-fresh)
         * [logFile]. Creates [logFile]'s parent directory if it doesn't exist yet (first-ever
         * write on a clean install).
         *
         * Extracted as a plain-`java.io.File` function — no `android.content.Context` involved —
         * so it's unit-testable from plain-JVM `src/test` against a real temp directory (this
         * project has no Robolectric; see `SupabaseUploadWorkerTest`'s docblock for the same
         * rationale applied to `SupabaseUploadWorker`).
         */
        internal fun rotateAndAppend(
            logFile: File,
            backupFile: File,
            line: String,
            maxSizeBytes: Long = MAX_FILE_SIZE_BYTES
        ) {
            logFile.parentFile?.mkdirs()
            if (logFile.exists() && logFile.length() >= maxSizeBytes) {
                if (backupFile.exists()) backupFile.delete()
                // renameTo is the cheap path (same directory, same filesystem) but its contract is
                // platform-dependent — fall back to copy+delete if it ever reports failure so
                // rotation still completes rather than silently losing the backup.
                if (!logFile.renameTo(backupFile)) {
                    logFile.copyTo(backupFile, overwrite = true)
                    logFile.delete()
                }
            }
            logFile.appendText(line + "\n")
        }
    }

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    private val logFile = File(logsDir, LOG_FILE_NAME)
    private val backupFile = File(logsDir, BACKUP_FILE_NAME)

    // Own IO-dispatcher scope, never the caller's: log() is called synchronously from many
    // contexts (service callbacks, WorkManager coroutine workers, interruption-manager callbacks
    // on arbitrary threads) and must never block whichever thread called it on file I/O ("Escrita
    // em arquivo nunca bloqueia a thread principal" — story boundary). SupervisorJob so one failed
    // write never cancels the scope for future writes. This scope lives for the whole process
    // (this class is a Hilt @Singleton, never cleared) — an intentional, bounded leak, same
    // pattern AudioRecordingService uses for its own serviceScope.
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Guards rotateAndAppend against concurrent writers: log() is called from multiple
    // threads/coroutines (service callbacks, WorkManager workers, interruption-manager callbacks),
    // and each call launches its own coroutine on ioScope's thread pool with no ordering between
    // them. Without this lock, two concurrent writers can race on rotateAndAppend's
    // check-then-act rotation (`logFile.length() >= maxSizeBytes`) — both observing "needs
    // rotation" and both renaming/overwriting backupFile, or interleaving two appendText calls
    // into the same file. A plain JVM monitor is enough here (java.io, not a suspend function to
    // guard, and no need to hold it across a suspension point) — no Mutex/locking library needed.
    private val fileLock = Any()

    /**
     * Records a new event as the newest entry (index 0). If the list would exceed [MAX_EVENTS],
     * the oldest event is dropped so the list never grows past the cap. Also enqueues a
     * fire-and-forget write of the same event to [logFile] (am2-2, FR10).
     *
     * Uses [MutableStateFlow.update], which is a lock-free compare-and-set loop — safe to call
     * concurrently from any thread/coroutine (recording happens on `serviceScope` (IO dispatcher)
     * and from interruption-manager callbacks that may run on other threads).
     */
    fun log(category: LogCategory, message: String) {
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            category = category,
            message = message
        )
        _events.update { current -> (listOf(event) + current).take(MAX_EVENTS) }
        persist(event)
    }

    private fun persist(event: LogEvent) {
        ioScope.launch {
            try {
                synchronized(fileLock) {
                    rotateAndAppend(logFile, backupFile, formatLine(event))
                }
            } catch (e: Exception) {
                // Boundary: "Falha de escrita em disco é engolida silenciosamente (não deve
                // derrubar o worker/serviço chamador)" — this coroutine already runs off the
                // caller's thread, so the exception can't propagate to it anyway; still caught
                // explicitly so it never crashes via an uncaught-exception handler either, and
                // logged to logcat for local debugging only (never re-thrown, never re-logged
                // through this same logger — would risk an infinite loop on a persistently
                // failing disk).
                Log.w(TAG, "Failed to persist log event to disk", e)
            }
        }
    }
}

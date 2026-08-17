package com.example.audiomemo.core.logging

/**
 * A single in-app diagnostic event captured by [AppEventLogger], shown on the Logs screen (am2-1).
 *
 * NEVER put raw credentials (email/password or any secret) in [message] — see the "credentials
 * never appear in log/logcat" boundary carried over from am1-1 and extended to this in-app
 * logging mechanism by the am2-1 spec gate. Event messages describing login only report the
 * outcome (e.g. "Sign-in failed"), never the typed values.
 */
data class LogEvent(
    val timestamp: Long,
    val category: LogCategory,
    val message: String
)

/** Broad classification of a [LogEvent], used to group/badge events on the Logs screen. */
enum class LogCategory { RECORDING, UPLOAD, INTERRUPTION }

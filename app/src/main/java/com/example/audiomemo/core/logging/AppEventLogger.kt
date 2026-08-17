package com.example.audiomemo.core.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, app-wide event log (am2-1, FR9). Holds up to [MAX_EVENTS] recent [LogEvent]s,
 * most-recent-first, exposed as a hot [StateFlow] so the Logs screen updates live without polling.
 *
 * Singleton scope (one instance for the whole process, shared by [AudioRecordingService][
 * com.example.audiomemo.features.transcript.service.AudioRecordingService] — which may outlive
 * any UI — and the Logs screen's ViewModel) is what makes events logged while no UI is open still
 * be there the next time the Logs screen is opened.
 *
 * Deliberately **not** persisted to disk here — file persistence is am2-2's job. Process death
 * (or the app being killed) loses this history; that's an accepted trade-off for this story.
 */
@Singleton
class AppEventLogger @Inject constructor() {

    companion object {
        /** Cap on in-memory events (AC #3) — the oldest event is dropped once this is exceeded. */
        internal const val MAX_EVENTS = 200
    }

    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    /**
     * Records a new event as the newest entry (index 0). If the list would exceed [MAX_EVENTS],
     * the oldest event is dropped so the list never grows past the cap.
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
    }
}

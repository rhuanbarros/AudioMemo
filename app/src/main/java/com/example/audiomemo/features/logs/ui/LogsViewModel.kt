package com.example.audiomemo.features.logs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogEvent
import com.example.audiomemo.core.logging.LogFileReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Merges [AppEventLogger]'s live, in-memory [StateFlow] with the events [LogFileReader] reads
 * back from disk (am2-2, FR10) — the Logs screen's "history survives a restart" contract. The
 * live StateFlow already lives for the whole process (am2-1), so events logged while no UI was
 * open still show up the moment this screen starts collecting; [LogFileReader] additionally
 * covers events from *before* the current process even started.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    appEventLogger: AppEventLogger,
    private val logFileReader: LogFileReader
) : ViewModel() {

    internal companion object {
        /**
         * Combines the live (in-memory, current-process, newest-first) feed with events read
         * back from the persisted file (oldest-first, may span previous processes too — see
         * [LogFileReader.readEvents]).
         *
         * Every live event is also written to the same file it's read back from (see
         * [AppEventLogger.persist]), so a persisted snapshot loaded once at startup can overlap
         * with live events from *this* session — deduped by full [LogEvent] equality before
         * being appended after the live list, oldest-overall-last.
         *
         * Capped at [AppEventLogger.MAX_EVENTS] — same bound the live list itself enforces.
         * Without this, a long-lived install with a near-full ~2MB of log + backup on disk could
         * merge in tens of thousands of persisted events into a single in-memory list backing a
         * `LazyColumn`, unbounded unlike every other list this app keeps in memory.
         */
        internal fun mergeEvents(live: List<LogEvent>, persisted: List<LogEvent>): List<LogEvent> {
            if (persisted.isEmpty()) return live
            val seen = live.toSet()
            val historicalOnly = persisted.asReversed().filterNot { it in seen }
            return (live + historicalOnly).take(AppEventLogger.MAX_EVENTS)
        }
    }

    private val persistedEvents = MutableStateFlow<List<LogEvent>>(emptyList())

    val events: StateFlow<List<LogEvent>> =
        combine(appEventLogger.events, persistedEvents) { live, persisted ->
            mergeEvents(live, persisted)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = appEventLogger.events.value
        )

    init {
        viewModelScope.launch {
            persistedEvents.value = logFileReader.readPersistedEvents()
        }
    }
}

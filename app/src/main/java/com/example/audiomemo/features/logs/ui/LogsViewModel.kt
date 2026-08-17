package com.example.audiomemo.features.logs.ui

import androidx.lifecycle.ViewModel
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes [AppEventLogger]'s already-hot, singleton-scoped [StateFlow] straight to the Logs
 * screen. No `stateIn`/`viewModelScope` re-wrapping needed: the events list already lives for the
 * whole process (survives this ViewModel being cleared), so the screen sees real-time updates
 * (including events logged while no UI was open) the moment it starts collecting.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    appEventLogger: AppEventLogger
) : ViewModel() {

    val events: StateFlow<List<LogEvent>> = appEventLogger.events
}

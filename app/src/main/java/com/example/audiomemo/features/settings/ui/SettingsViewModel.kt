package com.example.audiomemo.features.settings.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiomemo.core.preferences.AppPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

/**
 * Backs [SettingsScreen]'s "Location capture" toggle (gps-location-capture-per-chunk). Mirrors
 * [AppearanceViewModel]'s exact shape — a thin `StateFlow` projection of
 * [AppPreferencesRepository] plus a `viewModelScope`-launched setter.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: AppPreferencesRepository
) : ViewModel() {

    val locationCaptureEnabled: StateFlow<Boolean> = repo.locationCaptureEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true
    )

    /**
     * Review patch (Edge Case Hunter, review_loop_iteration 1): `repo.setLocationCaptureEnabled`'s
     * `dataStore.edit` can throw `IOException` (disk write failure) — uncaught, that would crash a
     * `viewModelScope` coroutine and take the whole app down over what is, worst case, a toggle
     * that silently didn't persist. Logged, not surfaced to the UI: the switch's Compose state
     * (`locationCaptureEnabled`) reflects whatever the DataStore actually holds on the next
     * recomposition regardless, same as every other setter in [AppPreferencesRepository].
     */
    fun setLocationCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { repo.setLocationCaptureEnabled(enabled) }
                .onFailure { Log.w(TAG, "Failed to persist location capture setting", it) }
        }
    }
}

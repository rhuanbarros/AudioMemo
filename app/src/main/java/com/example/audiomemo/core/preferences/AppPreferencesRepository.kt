package com.example.audiomemo.core.preferences

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val THEME_MODE   = stringPreferencesKey("theme_mode")
        val APP_FONT     = stringPreferencesKey("app_font")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")

        // am3-2 (FR2): the owner's persisted intent ("recording should be active") plus a
        // liveness heartbeat — both read by RecordingWatchdogWorker to decide whether the
        // AudioRecordingService needs restarting after the process was killed. lastHeartbeatAt
        // is also consumed by the am3-5 lost-chunk fix (see story am3-2 Design Notes).
        val RECORDING_SHOULD_BE_ACTIVE = booleanPreferencesKey("recording_should_be_active")
        val LAST_HEARTBEAT_AT = longPreferencesKey("last_heartbeat_at")

        // gps-location-capture-per-chunk: owner-facing on/off switch for per-chunk location
        // capture (Settings row). Same default-true-on-absent-key shape as
        // RECORDING_SHOULD_BE_ACTIVE above — a fresh install (or an existing install that never
        // wrote this key) captures location by default, requiring an explicit opt-out rather
        // than an opt-in tap.
        val LOCATION_CAPTURE_ENABLED = booleanPreferencesKey("location_capture_enabled")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM
    }

    val appFont: Flow<AppFont> = dataStore.data.map { prefs ->
        AppFont.entries.firstOrNull { it.name == prefs[Keys.APP_FONT] } ?: AppFont.DEFAULT
    }

    val accentColor: Flow<AccentColor> = dataStore.data.map { prefs ->
        AccentColor.entries.firstOrNull { it.name == prefs[Keys.ACCENT_COLOR] } ?: AccentColor.VIOLET
    }

    /**
     * The owner's persisted intent: "recording should be active". No always-on mechanism
     * (watchdog, boot receiver, process-start check in `AudioMemoApplication.onCreate()`) ever
     * WRITES this flag — they only read it and decide whether to (re)start the service based on
     * it. The only writer is [AppPreferencesRepository.setRecordingShouldBeActive], called `true`
     * when a session actually starts and `false` from `stopRecordingCleanly()` when the owner
     * explicitly stops.
     *
     * **Defaults to `true` (am-hotfix)**, not `false`, when the DataStore key was never written
     * at all — the product's central premise is "always recording unless explicitly stopped", so
     * a fresh install (or an existing install that upgraded but never wrote this key) reads as
     * "should be recording" the moment `RECORD_AUDIO` is granted, rather than requiring a first
     * manual tap to ever flip it on. This default-value change is what actually inverts the
     * original "only grab, if already turned on manually" intent — this Flow's own read-only
     * contract above is unchanged; only what an absent key means changed. Applies globally to
     * every existing consumer of this flag (`BootCompletedReceiver.shouldAutoStart`,
     * `RecordingWatchdogWorker.needsRestart`), not only the new
     * `AudioMemoApplication.onCreate()` check — intentional: boot recovery and the periodic
     * watchdog should also treat "never explicitly stopped" as "should be recording", the same
     * "always on unless stopped" premise this hotfix establishes everywhere the flag is read.
     */
    val recordingShouldBeActive: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.RECORDING_SHOULD_BE_ACTIVE] ?: true
    }

    /**
     * Millis ([SystemClock.elapsedRealtime], **not** wall-clock time) of the last time
     * [AudioRecordingService][com.example.audiomemo.features.transcript.service.AudioRecordingService]
     * proved it was still alive. `elapsedRealtime()` is monotonic and immune to wall-clock jumps
     * (NTP sync, manual time change, DST) that would otherwise make a staleness check based on
     * `System.currentTimeMillis()` unreliable in both directions (a backward jump masking a
     * genuinely stale heartbeat, a forward jump falsely flagging a healthy session as stale) — it
     * does reset on device reboot, but that's fine, a reboot is handled by the separate am3-3
     * boot-receiver story, not this staleness check. Defaults to `0L` when nothing was ever
     * recorded, which reads as "infinitely stale" to any staleness check.
     */
    val lastHeartbeatAt: Flow<Long> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_HEARTBEAT_AT] ?: 0L
    }

    /**
     * The owner's persisted "capture location per chunk" toggle (gps-location-capture-per-chunk,
     * Settings row). **Defaults to `true`** when the key was never written — mirrors
     * [recordingShouldBeActive]'s default-true-on-absent-key rationale: the feature ships on by
     * default, an explicit toggle-off is what persists `false`, not the other way around.
     */
    val locationCaptureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.LOCATION_CAPTURE_ENABLED] ?: true
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setAppFont(font: AppFont) {
        dataStore.edit { it[Keys.APP_FONT] = font.name }
    }

    suspend fun setAccentColor(color: AccentColor) {
        dataStore.edit { it[Keys.ACCENT_COLOR] = color.name }
    }

    suspend fun setRecordingShouldBeActive(value: Boolean) {
        dataStore.edit { it[Keys.RECORDING_SHOULD_BE_ACTIVE] = value }
    }

    /**
     * Records "the service is alive right now" — see [lastHeartbeatAt]. [now] defaults to the
     * real monotonic clock in production; tests pass an explicit value to stay on plain-JVM
     * `src/test` without touching the unmocked `SystemClock` Android stub.
     */
    suspend fun recordHeartbeat(now: Long = SystemClock.elapsedRealtime()) {
        dataStore.edit { it[Keys.LAST_HEARTBEAT_AT] = now }
    }

    suspend fun setLocationCaptureEnabled(value: Boolean) {
        dataStore.edit { it[Keys.LOCATION_CAPTURE_ENABLED] = value }
    }
}

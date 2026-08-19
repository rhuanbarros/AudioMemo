package com.example.audiomemo.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Round-trip coverage for [AppPreferencesRepository.recordingShouldBeActive]/
 * [AppPreferencesRepository.lastHeartbeatAt] (am3-2, FR2) — the owner's persisted "recording
 * should be active" intent and the service-liveness heartbeat that
 * [com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker] reads to decide
 * whether to restart [com.example.audiomemo.features.transcript.service.AudioRecordingService]. A
 * bug here (e.g. a wrong default, or a write that doesn't round-trip) would make the watchdog
 * either never fire or fire on a perfectly healthy session. Mirrors the real-DataStore-backed-by-a-
 * temp-file pattern from `DataStoreSessionManagerTest` (am1-1) — no Robolectric/mocking needed for
 * a plain DataStore round trip.
 */
class AppPreferencesRepositoryTest : StringSpec({

    fun newRepository(): AppPreferencesRepository {
        val file = File.createTempFile("app_preferences_test", ".preferences_pb")
        file.deleteOnExit()
        return AppPreferencesRepository(
            PreferenceDataStoreFactory.create(produceFile = { file })
        )
    }

    "recordingShouldBeActive defaults to true when nothing was ever set (am-hotfix: always-record-by-default)" {
        val repository = newRepository()

        check(repository.recordingShouldBeActive.first() == true)
    }

    "setRecordingShouldBeActive(true) then recordingShouldBeActive reads back true" {
        val repository = newRepository()

        repository.setRecordingShouldBeActive(true)

        check(repository.recordingShouldBeActive.first() == true)
    }

    "setRecordingShouldBeActive(false) after true reads back false (explicit stop clears the intent)" {
        val repository = newRepository()

        repository.setRecordingShouldBeActive(true)
        repository.setRecordingShouldBeActive(false)

        check(repository.recordingShouldBeActive.first() == false)
    }

    "lastHeartbeatAt defaults to 0L when nothing was ever recorded (reads as infinitely stale)" {
        val repository = newRepository()

        check(repository.lastHeartbeatAt.first() == 0L)
    }

    "recordHeartbeat(now) then lastHeartbeatAt reads back exactly that value" {
        // Explicit `now` on purpose: the production default (SystemClock.elapsedRealtime())
        // is an unmocked Android stub in plain-JVM src/test and would throw "not mocked" here —
        // passing an explicit value keeps this test on plain-JVM without Robolectric while still
        // proving the write round-trips exactly.
        val repository = newRepository()
        val fakeNow = 123_456_789L

        repository.recordHeartbeat(now = fakeNow)

        check(repository.lastHeartbeatAt.first() == fakeNow)
    }

    // ── gps-location-capture-per-chunk ──────────────────────────────────────────

    "locationCaptureEnabled defaults to true when nothing was ever set (ships on by default)" {
        val repository = newRepository()

        check(repository.locationCaptureEnabled.first() == true)
    }

    "setLocationCaptureEnabled(false) then locationCaptureEnabled reads back false" {
        val repository = newRepository()

        repository.setLocationCaptureEnabled(false)

        check(repository.locationCaptureEnabled.first() == false)
    }

    "setLocationCaptureEnabled(true) after false reads back true" {
        val repository = newRepository()

        repository.setLocationCaptureEnabled(false)
        repository.setLocationCaptureEnabled(true)

        check(repository.locationCaptureEnabled.first() == true)
    }
})

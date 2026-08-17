package com.example.audiomemo

import android.app.Application
import com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AudioMemoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // am3-2 (FR2): always-on safety net. Idempotent (ExistingPeriodicWorkPolicy.KEEP) —
        // safe to call on every process start, whether that's the owner opening the app, the
        // system relaunching it, or the watchdog itself restarting AudioRecordingService.
        RecordingWatchdogWorker.enqueue(this)
    }
}

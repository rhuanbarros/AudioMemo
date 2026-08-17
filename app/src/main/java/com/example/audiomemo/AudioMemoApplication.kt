package com.example.audiomemo

import android.app.Application
import android.util.Log
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadEntryPoint
import com.example.audiomemo.features.transcript.data.worker.RecordingWatchdogWorker
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import io.github.jan.supabase.gotrue.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class AudioMemoApplication : Application() {

    companion object {
        private const val TAG = "AudioMemoApplication"
    }

    override fun onCreate() {
        super.onCreate()
        // am3-2 (FR2): always-on safety net. Idempotent (ExistingPeriodicWorkPolicy.KEEP) —
        // safe to call on every process start, whether that's the owner opening the app, the
        // system relaunching it, or the watchdog itself restarting AudioRecordingService.
        RecordingWatchdogWorker.enqueue(this)

        // am-hotfix (supabase session init): proactive warmup of gotrue-kt's async
        // session-from-disk load. Fire-and-forget — this is only an optimization that covers the
        // common case without blocking onCreate(); the decisive fix (closes the race
        // deterministically even if this warmup hasn't finished yet) is the
        // awaitInitialization() call in SupabaseUploadWorker.doWork().
        //
        // `Application` is not `@AndroidEntryPoint`-compatible the way an Activity/Service/Worker
        // is, so the SupabaseClient is pulled via EntryPointAccessors instead — reusing
        // SupabaseUploadEntryPoint (already declared in SupabaseUploadWorker.kt, which needs the
        // exact same accessor) rather than declaring a second, duplicated @EntryPoint interface
        // here. Same consolidation this project's own am3-3 code review already established for
        // RecordingWatchdogWorker/BootCompletedReceiver (see RecordingRestartEntryPoint).
        //
        // Wrapped in try/catch (code review, patch 1): awaitInitialization() is documented by the
        // SDK to never throw, but that guarantee lives only in a comment, not in the type system.
        // If a future SDK version or an unanticipated edge case ever violates it, this whole block
        // is a pure optimization — it must never be able to turn into a cold-start crash. A caught
        // failure is just logged; the decisive fix in SupabaseUploadWorker doesn't depend on this
        // warmup succeeding.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val supabaseClient = EntryPointAccessors.fromApplication(
                    this@AudioMemoApplication,
                    SupabaseUploadEntryPoint::class.java
                ).supabaseClient()
                supabaseClient.auth.awaitInitialization()
            } catch (e: Exception) {
                Log.w(TAG, "Supabase auth warmup failed — non-fatal, upload worker awaits its own init", e)
            }
        }
    }
}

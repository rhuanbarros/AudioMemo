package com.example.audiomemo.features.transcript.service

import androidx.work.NetworkType
import com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker
import io.kotest.core.spec.style.StringSpec

/**
 * Covers [AudioRecordingService.enqueueSupabaseUpload] (am1-2, code review item 11) — the worker
 * enqueued when a chunk finishes recording.
 *
 * **Why this doesn't use `WorkManagerTestInitHelper`** (despite the original placeholder importing
 * it): that API needs a real, or Robolectric-simulated, `android.content.Context` — internally
 * `WorkManager` runs its own Room database and OS scheduler integration, neither of which can be
 * meaningfully hand-faked the way a plain interface can. This project has **no Robolectric
 * dependency and no `androidTest` WorkManager infra** today (confirmed: `app/build.gradle.kts` has
 * no `androidTestImplementation(libs.work.testing)`/Robolectric entry, and there's no connected
 * device/emulator in this environment either), so a `WorkManagerTestInitHelper`-based test would
 * not actually run in this module's plain-JVM `src/test` — it would need a new Robolectric
 * dependency (out of scope to add silently) or a move to `androidTest` (needs a device/emulator,
 * not available here).
 *
 * Instead, [AudioRecordingService.buildSupabaseUploadWorkRequest] and
 * [AudioRecordingService.supabaseUploadWorkName] were extracted as pure, `Context`-free `internal`
 * functions — building a `OneTimeWorkRequest` needs no `WorkManager` instance at all, only the
 * final `WorkManager.getInstance(context).enqueueUniqueWork(...)` call does — and
 * `enqueueSupabaseUpload` itself now just delegates to them before making that one Context-needing
 * call. That lets this test assert exactly the properties a regression here would break, without
 * needing a real work queue:
 * - the FR5 network constraint (`CONNECTED`, i.e. *not* Wi-Fi-only) — the one-line mistake this
 *   guards against is copy-pasting `enqueueChunkUpload`'s Wi-Fi-preference-respecting constraints
 *   (`UploadPreferences.networkConstraints`) into the Supabase path, which FR5 explicitly forbids;
 * - the unique work name (`"supabase_upload_$chunkId"`), whose stability is what makes
 *   `ExistingWorkPolicy.KEEP` actually de-duplicate repeated enqueues for the same chunk (the "sem
 *   rede acumula chunks" edge case in the am1-2 story's I/O matrix);
 * - the input data round-trips the exact `chunkId`/`sessionId` [SupabaseUploadWorker] reads back
 *   via `KEY_CHUNK_ID`/`KEY_SESSION_ID`.
 */
class AudioRecordingServiceConflictResolutionTest : StringSpec({

    "buildSupabaseUploadWorkRequest constrains to CONNECTED, never Wi-Fi-only (FR5)" {
        val request = AudioRecordingService.buildSupabaseUploadWorkRequest(chunkId = 42L, sessionId = 7L)

        val networkType = request.workSpec.constraints.requiredNetworkType
        check(networkType == NetworkType.CONNECTED) {
            "expected NetworkType.CONNECTED (FR5: any network, never Wi-Fi-only), got $networkType"
        }
    }

    "buildSupabaseUploadWorkRequest carries the exact chunkId/sessionId SupabaseUploadWorker reads back" {
        val request = AudioRecordingService.buildSupabaseUploadWorkRequest(chunkId = 42L, sessionId = 7L)

        val chunkId = request.workSpec.input.getLong(SupabaseUploadWorker.KEY_CHUNK_ID, -1L)
        val sessionId = request.workSpec.input.getLong(SupabaseUploadWorker.KEY_SESSION_ID, -1L)
        check(chunkId == 42L) { "expected chunkId 42, got $chunkId" }
        check(sessionId == 7L) { "expected sessionId 7, got $sessionId" }
    }

    "supabaseUploadWorkName is stable per chunkId, distinct across chunks" {
        val nameFor42 = AudioRecordingService.supabaseUploadWorkName(chunkId = 42L)
        val nameFor42Again = AudioRecordingService.supabaseUploadWorkName(chunkId = 42L)
        val nameFor43 = AudioRecordingService.supabaseUploadWorkName(chunkId = 43L)

        check(nameFor42 == "supabase_upload_42") { "expected 'supabase_upload_42', got $nameFor42" }
        check(nameFor42 == nameFor42Again) {
            "the same chunkId must produce the same work name — ExistingWorkPolicy.KEEP relies on " +
                "this to de-duplicate repeated enqueue attempts for the same chunk"
        }
        check(nameFor42 != nameFor43) { "different chunks must never collide on the same work name" }
    }
})

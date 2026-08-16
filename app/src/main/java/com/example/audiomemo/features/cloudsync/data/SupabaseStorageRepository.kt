package com.example.audiomemo.features.cloudsync.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject

/**
 * Wraps the Supabase Storage (storage-kt) plugin: uploads a single finished audio chunk to the
 * `audiomemo-chunks` bucket (see `supabase/migrations/20260816000003_create_audiomemo_bucket.sql`).
 *
 * Path convention: `"$sessionId/$chunkIndex.m4a"` — reuses the Room-local session/chunk ids
 * (no new identifier generation, see am1-2 story Design Notes).
 *
 * **No unit test for [uploadChunk] (investigated — code review item 9, am1-2):** unlike
 * `SupabaseAuthRepositoryImpl` (am1-1), whose own exception-mapping is *also* untested for the
 * same underlying reason (only its consumer-facing interface got a fake, via
 * `CloudSyncSettingsViewModelSignInTest`'s `FakeSupabaseAuthRepository`), this class's only
 * dependency of substance is [SupabaseClient] itself. Confirmed by reading the pinned `2.2.3`
 * source directly: both [SupabaseClient] and the type returned by its `.storage` extension
 * property ([io.github.jan.supabase.storage.Storage]) are declared `sealed interface` in
 * supabase-kt's own module (`SupabaseClient.kt`, `Storage.kt`). Kotlin's sealed-interface rule
 * only allows implementations *within that same compilation module* — so no hand-written
 * `object : SupabaseClient { ... }` fake (the technique used for `RestException` alternatives
 * elsewhere, and the technique that unblocked `FakeSupabaseAuthRepository`) can be written from
 * this app's or this test's source, at any level: not `SupabaseClient` directly, and not
 * `BucketApi` either (obtained only via the sealed `Storage`, so unreachable without first faking
 * `SupabaseClient.storage`). This is the same class of blocker am1-1 hit with `RestException`
 * subclasses (there: `@InternalAPI`-gated constructors; here: sealed-interface module locality) —
 * genuinely unfakeable without a bytecode-level mocking library (e.g. mockk), which is not a
 * dependency of this project and is out of this story's approved scope to add. `uploadChunk`'s
 * actual SDK call is exercised only by the manual/device verification in the story's own
 * Verification section, same as `SupabaseAuthRepositoryImpl.signIn` before it.
 */
class SupabaseStorageRepository @Inject constructor(
    private val supabaseClient: SupabaseClient
) {

    companion object {
        const val BUCKET_ID = "audiomemo-chunks"
    }

    /**
     * Uploads [file] to `"$sessionId/$chunkIndex.m4a"`. Idempotent: `upsert = true` so a retry
     * that re-sends an already-uploaded chunk (e.g. after a partial failure) overwrites the same
     * object instead of erroring — safe because the caller only ever writes the exact same bytes
     * for a given (sessionId, chunkIndex) pair, and never re-triggers once the chunk is confirmed
     * DONE (see [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker]).
     */
    suspend fun uploadChunk(sessionId: Long, chunkIndex: Int, file: File): Result<Unit> = try {
        supabaseClient.storage.from(BUCKET_ID)
            .upload(path = "$sessionId/$chunkIndex.m4a", file = file, upsert = true)
        Result.success(Unit)
    } catch (e: CancellationException) {
        // Structured concurrency: a cancelled upload (e.g. WorkManager stopping the worker) must
        // propagate, never be swallowed into a Result.failure — same rule as SupabaseAuthRepositoryImpl.
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

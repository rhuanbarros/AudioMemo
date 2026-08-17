package com.example.audiomemo.features.cloudsync.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.features.cloudsync.data.SupabaseStorageRepository
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SupabaseUploadEntryPoint {
    fun chunkDao(): ChunkDao
    fun supabaseStorageRepository(): SupabaseStorageRepository
    fun supabaseClient(): SupabaseClient
    fun appEventLogger(): AppEventLogger
}

/**
 * Uploads a single finished audio chunk to Supabase Storage. Mirrors
 * [com.example.audiomemo.features.transcript.data.worker.WhisperUploadWorker]'s
 * `@EntryPoint`/`EntryPointAccessors` + `runAttemptCount < 3` retry pattern, but tracks progress
 * in the independent `supabaseUploadStatus` column (never the Whisper-only `status` column).
 *
 * Never constrained to Wi-Fi — always enqueued with `NetworkType.CONNECTED`
 * (see `AudioRecordingService.enqueueSupabaseUpload`, FR5).
 */
class SupabaseUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_CHUNK_ID = "chunk_id"
        const val KEY_SESSION_ID = "session_id"
        const val WORK_NAME_PREFIX = "supabase_upload_"
        private const val TAG = "SupabaseUploadWorker"

        /**
         * Budget for the `awaitInitialization()` call in [doWork] below (am-hotfix code review,
         * patch 2). The
         * underlying session load goes through `DataStoreSessionManager.loadSession()` (disk
         * I/O) — if that ever hangs (file lock contention, etc.), this worker would otherwise
         * suspend indefinitely and hold the WorkManager execution slot. Mirrors
         * `BootCompletedReceiver.PREFERENCE_READ_TIMEOUT_MS`, the established idiom in this
         * codebase for exactly this class of "bound a disk-I/O suspend call" risk. On timeout,
         * [doWork] falls through to the existing `currentSessionOrNull() == null` check exactly
         * as before this hotfix — same safe failure path, no new behavior on timeout.
         */
        private const val AUTH_INIT_TIMEOUT_MS = 5_000L

        /**
         * Deletes [file] after the Supabase upload was confirmed `DONE` (FR7/am1-3). Pure,
         * `Context`/`Log`-free `internal` function — the call site (only when [decideUploadOutcome]
         * says `shouldDeleteFile == true`, i.e. a confirmed success, never on failure/pending) is
         * what decides *when* this runs; this helper only needs to prove *that* an existing file
         * gets deleted (and that an already-missing file is treated as success, not a failure to
         * log). Kept free of `android.util.Log` on purpose so it's testable from plain-JVM
         * `src/test` — this project has no Robolectric/androidTest WorkManager infra (see
         * `AudioRecordingServiceConflictResolutionTest`, am1-2, for the full rationale), and a
         * direct `Log.w` call would throw ("not mocked") outside Robolectric.
         *
         * Wrapped in try/catch (am1-3 code review finding): `File.exists()`/`File.delete()` can
         * throw `SecurityException`. Without this, that exception would propagate out of
         * [doWork] *after* the chunk was already durably marked `DONE` in Room — contradicting
         * the "never fail the worker over a cleanup miss" contract. A caught failure is reported
         * back as `false`, same as an ordinary failed delete, so the caller logs and moves on.
         */
        internal fun deleteConfirmedUploadFile(file: File): Boolean = try {
            !file.exists() || file.delete()
        } catch (e: SecurityException) {
            false
        }

        /** Outcome of [decideUploadOutcome] — what [doWork] must persist/return/delete. */
        internal enum class UploadWorkerResultKind { SUCCESS, RETRY, FAILURE }

        internal data class UploadOutcomeDecision(
            val newStatus: ChunkStatus,
            val shouldDeleteFile: Boolean,
            val resultKind: UploadWorkerResultKind
        )

        /**
         * Latency (ms) between "enqueued" and "confirmed", for the am4-1, FR8 log message on a
         * successful upload. Pure and `Context`/`Log`-free (same rationale as
         * [decideUploadOutcome]/[deleteConfirmedUploadFile] above) so it's unit-testable from
         * plain-JVM `src/test`.
         *
         * [enqueuedAtMs] is an **approximation**: this project's `WorkRequest`/input `Data` has no
         * existing enqueue-time field to read (checked against [KEY_CHUNK_ID]/[KEY_SESSION_ID]
         * above), so [doWork] captures `System.currentTimeMillis()` at its own start and passes
         * that in as the stand-in for "enqueued" (per this story's Code Map). `coerceAtLeast(0L)`
         * guards against a negative value from any clock adjustment between the two reads.
         */
        internal fun computeUploadLatencyMs(enqueuedAtMs: Long, confirmedAtMs: Long): Long =
            (confirmedAtMs - enqueuedAtMs).coerceAtLeast(0L)

        /**
         * Pure decision for what happens after the Supabase upload attempt resolves (FR7/am1-3):
         * on success, the chunk becomes `DONE` and its local file is deleted; on failure, the
         * chunk becomes `FAILED` and the file is **never** touched, with the worker retrying up to
         * 3 attempts before giving up. Extracted so this exact "delete only on confirmed success,
         * never on failure/pending" invariant — previously only verifiable by reading [doWork]'s
         * control flow — is unit-testable from plain-JVM `src/test` (am1-3 code review finding;
         * same Context/Log-free rationale as [deleteConfirmedUploadFile] above).
         */
        internal fun decideUploadOutcome(
            uploadSucceeded: Boolean,
            runAttemptCount: Int
        ): UploadOutcomeDecision = if (uploadSucceeded) {
            UploadOutcomeDecision(
                newStatus = ChunkStatus.DONE,
                shouldDeleteFile = true,
                resultKind = UploadWorkerResultKind.SUCCESS
            )
        } else {
            UploadOutcomeDecision(
                newStatus = ChunkStatus.FAILED,
                shouldDeleteFile = false,
                resultKind = if (runAttemptCount < 3) {
                    UploadWorkerResultKind.RETRY
                } else {
                    UploadWorkerResultKind.FAILURE
                }
            )
        }
    }

    override suspend fun doWork(): Result {
        // am4-1 (FR8): stand-in for "enqueued at" — see computeUploadLatencyMs's KDoc for why
        // this is an approximation rather than the true enqueue timestamp.
        val enqueuedAtMs = System.currentTimeMillis()
        val chunkId = inputData.getLong(KEY_CHUNK_ID, -1L)
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        // Room's autogenerate ids start at 1 — 0 is never legitimate, same bound as
        // AudioRecordingService.enqueueSupabaseUpload's own <= 0L guard at enqueue time.
        if (chunkId <= 0L || sessionId <= 0L) return Result.failure()

        val ep = EntryPointAccessors.fromApplication(
            applicationContext,
            SupabaseUploadEntryPoint::class.java
        )
        val chunkDao = ep.chunkDao()
        val storageRepository = ep.supabaseStorageRepository()
        val supabaseClient = ep.supabaseClient()
        val appEventLogger = ep.appEventLogger()

        val chunk = chunkDao.getChunksForSessionOnce(sessionId)
            .firstOrNull { it.id == chunkId } ?: run {
                appEventLogger.log(LogCategory.UPLOAD, "Supabase upload failed: chunk $chunkId not found")
                return Result.failure()
            }

        // Idempotent: never re-upload a chunk already confirmed DONE.
        if (chunk.supabaseUploadStatus == ChunkStatus.DONE) return Result.success()

        // No Supabase login configured yet (am1-1 not done, or session expired/signed out): fail
        // safely without marking the chunk FAILED — it stays PENDING. Retrying here via
        // WorkManager backoff would just burn battery hitting the same wall every time.
        // NOTE (am1-3): this specific gap is NOT covered by am1-3's crash-recovery sweep — that
        // sweep only reverts chunks stuck UPLOADING (mid-flight when the process died), and
        // SupabaseRetryWorker only re-enqueues FAILED chunks. A chunk that never got a Supabase
        // session (so it never left PENDING, never reached UPLOADING/FAILED) stays PENDING with
        // nothing scheduled for it until some other event re-triggers the upload chain (e.g. the
        // next time that specific chunk's finish event fires again, which it won't) — out of
        // scope here per the am1-3 story; see PRD FR2 (login persists) for why this is expected
        // to be rare in practice once login succeeds once.
        // am-hotfix (supabase session init): gotrue-kt loads the persisted session from disk
        // ASYNCHRONOUSLY on SupabaseClient creation; currentSessionOrNull() is a synchronous read
        // of whatever is already in memory and never waits for that load. This worker is
        // frequently the first thing to touch Auth after a process restart (always-on, no UI) —
        // without this await, a chunk can catch the session mid-SessionStatus.LoadingFromStorage
        // and get stuck PENDING forever ("not signed in"), even though a valid session is already
        // on disk. awaitInitialization() resolves to `sessionStatus.first { it !is
        // SessionStatus.LoadingFromStorage }` and never throws (NetworkError/NotAuthenticated both
        // resolve normally), so no try/catch is needed here. This is the decisive fix — the
        // warmup in AudioMemoApplication.onCreate() is only an optimization that makes this a
        // no-op in the common case. Bounded by AUTH_INIT_TIMEOUT_MS (code review, patch 2): on the
        // rare chance the underlying disk read hangs, this falls through to the exact same
        // currentSessionOrNull() == null path as before this hotfix, instead of suspending forever
        // and holding the WorkManager execution slot.
        withTimeoutOrNull(AUTH_INIT_TIMEOUT_MS) { supabaseClient.auth.awaitInitialization() }

        if (supabaseClient.auth.currentSessionOrNull() == null) {
            appEventLogger.log(LogCategory.UPLOAD, "Supabase upload skipped: not signed in (chunk=$chunkId)")
            return Result.failure()
        }

        chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.UPLOADING)

        val file = File(chunk.filePath)
        if (!file.exists()) {
            chunkDao.updateSupabaseUploadStatus(chunkId, ChunkStatus.FAILED)
            appEventLogger.log(LogCategory.UPLOAD, "Supabase upload failed: local file missing (chunk=$chunkId)")
            return Result.failure()
        }

        val uploadResult = storageRepository.uploadChunk(sessionId, chunk.chunkIndex, file)
        // FR7: decideUploadOutcome is the single source of truth for "delete only on confirmed
        // success, never on failure/pending" — doWork just persists/executes what it decides.
        val decision = decideUploadOutcome(uploadResult.isSuccess, runAttemptCount)

        chunkDao.updateSupabaseUploadStatus(chunkId, decision.newStatus)

        if (decision.shouldDeleteFile && !deleteConfirmedUploadFile(file)) {
            // Rare (permissions, file locked, already-open handle): log and move on — never fail
            // the worker over a cleanup miss, the upload itself is confirmed.
            Log.w(
                TAG,
                "Failed to delete local file for chunk $chunkId after confirmed " +
                    "Supabase upload: ${file.path}"
            )
        }

        when (decision.resultKind) {
            UploadWorkerResultKind.SUCCESS -> {
                val latencyMs = computeUploadLatencyMs(enqueuedAtMs, System.currentTimeMillis())
                appEventLogger.log(
                    LogCategory.UPLOAD,
                    "Supabase upload succeeded (chunk=$chunkId, latencyMs=$latencyMs)"
                )
            }
            UploadWorkerResultKind.RETRY ->
                appEventLogger.log(
                    LogCategory.UPLOAD,
                    "Supabase upload failed, retrying (chunk=$chunkId, attempt=$runAttemptCount)"
                )
            UploadWorkerResultKind.FAILURE ->
                appEventLogger.log(LogCategory.UPLOAD, "Supabase upload failed permanently (chunk=$chunkId)")
        }

        return when (decision.resultKind) {
            UploadWorkerResultKind.SUCCESS -> Result.success()
            UploadWorkerResultKind.RETRY -> Result.retry()
            UploadWorkerResultKind.FAILURE -> Result.failure()
        }
    }
}

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
import kotlinx.coroutines.CancellationException
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
         * Budget for the recovery [io.github.jan.supabase.gotrue.Auth.loadFromStorage] call in
         * [doWork] (am-hotfix patch 2, mid-session recurrence). Same disk-I/O-hang rationale as
         * [AUTH_INIT_TIMEOUT_MS] — this is a second, independent disk read (not just the
         * cold-start one), so it gets its own bounded timeout rather than reusing the first
         * call's already-spent budget. Unlike `awaitInitialization()`, this call's default
         * `autoRefresh` parameter can also trigger a network-bound token refresh internally (code
         * review finding), so [doWork] wraps it in try/catch in addition to this timeout — a
         * thrown exception is treated the same as a timeout (ambiguous outcome, not a confirmed
         * disk-empty signal), never left to crash the worker.
         */
        private const val AUTH_RECOVERY_TIMEOUT_MS = 5_000L

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

        /** Outcome of [decideSessionRecoveryOutcome] — what [doWork] must log/return when it hit
         * `currentSessionOrNull() == null` even after `awaitInitialization()`. */
        internal enum class SessionRecoveryOutcome { PROCEED, FAIL_FAST, RETRY, FAIL_TERMINAL }

        /**
         * Pure decision for what [doWork] does after a `loadFromStorage()` recovery attempt
         * (am-hotfix patch 2, code review round). Extracted for the same reason as
         * [decideUploadOutcome] — plain-JVM-testable from `src/test`, `Context`/`Log`-free.
         *
         * [sessionRestored] is the authoritative signal — `currentSessionOrNull() != null`,
         * re-checked fresh *after* the recovery attempt, independent of [loadFromStorageResult].
         * This intentionally does not gate success on [loadFromStorageResult] being `true`: in a
         * narrow race, some other concurrent caller (another chunk's worker, the app's warmup
         * coroutine) can restore the session at the same instant this call's own attempt
         * timed out or reported no session, and the session is genuinely usable either way (code
         * review finding — gating on `recovered && sessionRestored` would wrongly retry/fail in
         * that race window).
         *
         * [loadFromStorageResult] is `true`/`false` from a completed `loadFromStorage()` call, or
         * `null` if the call itself timed out or threw. Per gotrue-kt 2.2.3's `AuthImpl` (verified
         * by decompilation, code review round): the boolean return only reflects whether a
         * session existed **on disk** — it does NOT confirm the in-memory session was actually
         * restored (e.g. an expired token can be found on disk, `false`... `true` even trigger an
         * internal refresh that itself fails). So `loadFromStorageResult == false` is a strong,
         * narrow signal — disk genuinely has no session (real sign-out / never logged in) — worth
         * failing fast without retry, exactly matching the pre-existing rationale in [doWork]'s
         * comment above this call site ("retrying would just burn battery hitting the same wall
         * every time"). Any other non-restored case (`true` but still not restored, or the call
         * itself failed/timed out) is treated as transient and gets the same bounded retry
         * [decideUploadOutcome] already uses for actual upload failures.
         */
        internal fun decideSessionRecoveryOutcome(
            sessionRestored: Boolean,
            loadFromStorageResult: Boolean?,
            runAttemptCount: Int
        ): SessionRecoveryOutcome = when {
            sessionRestored -> SessionRecoveryOutcome.PROCEED
            loadFromStorageResult == false -> SessionRecoveryOutcome.FAIL_FAST
            runAttemptCount < 3 -> SessionRecoveryOutcome.RETRY
            else -> SessionRecoveryOutcome.FAIL_TERMINAL
        }

        /**
         * Same basename as [audioFile], `.txt` extension — the location sidecar
         * [com.example.audiomemo.features.transcript.manager.LocationCaptureManager] may have
         * written next to it (gps-location-capture-per-chunk). Pure, filesystem-path-only,
         * deliberately duplicated from that class's own `sidecarFileFor` rather than
         * cross-package-coupled — this worker's only reason to know about the sidecar at all is
         * this trivial one-liner, not worth importing the `transcript.manager` package for.
         */
        internal fun sidecarFileFor(audioFile: File): File =
            File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.txt")

        /**
         * Pure decision (gps-location-capture-per-chunk): the sidecar upload is only ever
         * attempted once, right when the `.m4a` upload for the same chunk is first confirmed
         * `SUCCESS` — never on `RETRY` (would otherwise re-attempt the sidecar upload on every
         * WorkManager retry of the `.m4a`) and never on terminal `FAILURE` (the story's Never
         * clause: best-effort, at most one attempt, no dedicated retry mechanism). Extracted so
         * this "independent of, but gated by, the `.m4a` outcome" rule is unit-testable from
         * plain-JVM `src/test`, mirroring [decideUploadOutcome]/[decideSessionRecoveryOutcome]
         * above.
         */
        internal fun shouldAttemptSidecarUpload(resultKind: UploadWorkerResultKind): Boolean =
            resultKind == UploadWorkerResultKind.SUCCESS
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

        // No Supabase login configured yet (am1-1 not done, or session expired/signed out): if
        // recovery below can't restore a session from disk either, fail without marking the
        // chunk FAILED — it stays PENDING, and a *confirmed* disk-empty session (real sign-out)
        // does NOT get retried (see decideSessionRecoveryOutcome — retrying that case would just
        // burn battery hitting the same wall every time). A transient/ambiguous recovery outcome
        // DOES get a bounded retry now (am-hotfix patch 2) — see below.
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
        // resolve normally), so no try/catch is needed here. This closes the one-time cold-start
        // race — the warmup in AudioMemoApplication.onCreate() is only an optimization that makes
        // this a no-op in the common case. Bounded by AUTH_INIT_TIMEOUT_MS (code review, patch 2):
        // on the rare chance the underlying disk read hangs, this falls through to the
        // currentSessionOrNull() == null recovery path below, same as before this hotfix.
        withTimeoutOrNull(AUTH_INIT_TIMEOUT_MS) { supabaseClient.auth.awaitInitialization() }

        if (supabaseClient.auth.currentSessionOrNull() == null) {
            // am-hotfix patch 2 (mid-session recurrence, 3-reviewer code review round):
            // awaitInitialization() only guards the one-time cold-start LoadingFromStorage race —
            // it does NOT explain a session that was Authenticated earlier in this same process
            // and later reads back null here. Confirmed on device (2026-08-17, this app's own
            // persisted files/logs/app-events.log, chunks 46-53 in session id 8): recurs
            // mid-session, on a long-running process, every chunk failing "not signed in" with no
            // self-recovery — plausibly gotrue-kt's in-memory sessionStatus regressing to
            // NotAuthenticated after a failed background auto-refresh (AuthImpl.tryImportingSession
            // has a catch branch that does exactly this instead of NetworkError; verified by
            // decompiling gotrue-kt 2.2.3) while the persisted session on disk stays perfectly
            // good. loadFromStorage() is the same public primitive the SDK uses internally to
            // populate the session on cold start (decompiled AuthImpl.init) — calling it again
            // here forces a fresh disk re-read into memory. Its own boolean return only reflects
            // whether *disk* had a session (decompiled AuthImpl.loadFromStorage), not whether the
            // in-memory restore actually succeeded — decideSessionRecoveryOutcome re-checks
            // currentSessionOrNull() as the authoritative signal instead of trusting that return
            // value alone (also avoids a narrow race where a concurrent caller restores the
            // session between this call and the check). Bounded by AUTH_RECOVERY_TIMEOUT_MS for
            // the same disk-I/O-hang reason as AUTH_INIT_TIMEOUT_MS; any exception from the SDK
            // call itself (e.g. a network-bound auto-refresh attempt, unlike awaitInitialization()
            // this call is not documented "never throws") is caught and treated the same as a
            // timeout — ambiguous, not a confirmed disk-empty signal.
            val loadFromStorageResult = try {
                withTimeoutOrNull(AUTH_RECOVERY_TIMEOUT_MS) { supabaseClient.auth.loadFromStorage() }
            } catch (e: Exception) {
                null
            }
            val sessionRestored = supabaseClient.auth.currentSessionOrNull() != null

            when (decideSessionRecoveryOutcome(sessionRestored, loadFromStorageResult, runAttemptCount)) {
                SessionRecoveryOutcome.PROCEED -> appEventLogger.log(
                    LogCategory.UPLOAD,
                    "Supabase session recovered via loadFromStorage (chunk=$chunkId)"
                )
                SessionRecoveryOutcome.FAIL_FAST -> {
                    appEventLogger.log(
                        LogCategory.UPLOAD,
                        "Supabase upload skipped: not signed in, no session in storage (chunk=$chunkId)"
                    )
                    return Result.failure()
                }
                SessionRecoveryOutcome.RETRY -> {
                    appEventLogger.log(
                        LogCategory.UPLOAD,
                        "Supabase upload skipped: not signed in, retrying (chunk=$chunkId, attempt=$runAttemptCount)"
                    )
                    return Result.retry()
                }
                SessionRecoveryOutcome.FAIL_TERMINAL -> {
                    appEventLogger.log(
                        LogCategory.UPLOAD,
                        "Supabase upload skipped: not signed in, giving up (chunk=$chunkId, attempt=$runAttemptCount)"
                    )
                    return Result.failure()
                }
            }
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

        // gps-location-capture-per-chunk: best-effort, independent of the .m4a's own
        // status/deletion above — own try/catch (own runCatching, per the story's boundary), own
        // local-delete, never touches `decision`/newStatus/the .m4a file, and never re-attempted
        // on a WorkManager retry of this same chunk (see shouldAttemptSidecarUpload's KDoc).
        if (shouldAttemptSidecarUpload(decision.resultKind)) {
            try {
                attemptSidecarUpload(
                    storageRepository = storageRepository,
                    appEventLogger = appEventLogger,
                    sessionId = sessionId,
                    chunkIndex = chunk.chunkIndex,
                    chunkId = chunkId,
                    audioFile = file
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sidecar upload attempt threw unexpectedly for chunk $chunkId", e)
            }
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

    /**
     * Uploads [audioFile]'s `.txt` sidecar, if one exists, then deletes it locally on confirmed
     * success. No-op (not even an upload attempt) when no sidecar file is present — the common
     * case whenever location capture was off, ungranted, or had no fix for this chunk (see
     * [com.example.audiomemo.features.transcript.manager.LocationCaptureManager]'s own I/O
     * matrix). Never throws anything but `CancellationException` — [doWork]'s call site still
     * wraps this in try/catch as defense-in-depth, matching every other isolated-failure call
     * site in this codebase (e.g. `AudioRecordingService.onChunkCompleted`'s enqueue calls).
     */
    private suspend fun attemptSidecarUpload(
        storageRepository: SupabaseStorageRepository,
        appEventLogger: AppEventLogger,
        sessionId: Long,
        chunkIndex: Int,
        chunkId: Long,
        audioFile: File
    ) {
        val sidecarFile = sidecarFileFor(audioFile)
        if (!sidecarFile.exists()) return

        val result = storageRepository.uploadSidecar(sessionId, chunkIndex, sidecarFile)
        if (result.isSuccess) {
            if (!deleteConfirmedUploadFile(sidecarFile)) {
                Log.w(
                    TAG,
                    "Failed to delete local sidecar file for chunk $chunkId: ${sidecarFile.path}"
                )
            }
            appEventLogger.log(LogCategory.UPLOAD, "Location sidecar uploaded (chunk=$chunkId)")
        } else {
            Log.w(
                TAG,
                "Failed to upload location sidecar for chunk $chunkId",
                result.exceptionOrNull()
            )
            appEventLogger.log(
                LogCategory.UPLOAD,
                "Location sidecar upload failed, left local (chunk=$chunkId)"
            )
        }
    }
}

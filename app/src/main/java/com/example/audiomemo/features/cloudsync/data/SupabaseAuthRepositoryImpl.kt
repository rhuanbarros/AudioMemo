package com.example.audiomemo.features.cloudsync.data

import com.example.audiomemo.features.cloudsync.domain.SupabaseAuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Wraps the Supabase Auth (gotrue-kt) plugin: sign-in with e-mail/password and the current
 * authenticated/not-authenticated state, driven by [SupabaseClient.auth]'s own session status
 * (which already reflects a session restored from local storage on app start).
 */
class SupabaseAuthRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient
) : SupabaseAuthRepository {

    override suspend fun signIn(email: String, password: String): Result<Unit> = try {
        supabaseClient.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        // Structured concurrency: a cancelled sign-in (e.g. user navigated away mid-login) must
        // propagate, never be swallowed into a Result.failure.
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    // am-hotfix (supabase session init) code review, patch 5: this maps `sessionStatus`
    // reactively without awaiting initialization first, so on a cold start the emitted `Flow` can
    // transiently show `false` ("not signed in") for a moment while gotrue-kt is still loading the
    // persisted session from disk (SessionStatus.LoadingFromStorage), before naturally settling to
    // the correct value once that load resolves. Unlike SupabaseUploadWorker's bug, this is
    // harmless and self-correcting — a reactive Flow collector (CloudSyncSettingsViewModel's
    // `isAuthenticated`, today) just sees a brief transient, not a permanent stuck state, so no
    // functional fix is needed here. If a future caller ever adds a one-shot (non-Flow) auth check
    // instead of observing this Flow, follow the SupabaseUploadWorker.doWork() pattern
    // (`awaitInitialization()` before reading state) rather than assuming this self-heals.
    override fun currentSessionFlow(): Flow<Boolean> =
        supabaseClient.auth.sessionStatus.map { status -> status is SessionStatus.Authenticated }
}

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

    override fun currentSessionFlow(): Flow<Boolean> =
        supabaseClient.auth.sessionStatus.map { status -> status is SessionStatus.Authenticated }
}

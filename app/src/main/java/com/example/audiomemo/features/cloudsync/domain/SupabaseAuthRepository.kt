package com.example.audiomemo.features.cloudsync.domain

import kotlinx.coroutines.flow.Flow

/**
 * E-mail/password authentication against the app's single Supabase project, plus the current
 * authenticated/not-authenticated state.
 */
interface SupabaseAuthRepository {

    suspend fun signIn(email: String, password: String): Result<Unit>

    fun currentSessionFlow(): Flow<Boolean>
}

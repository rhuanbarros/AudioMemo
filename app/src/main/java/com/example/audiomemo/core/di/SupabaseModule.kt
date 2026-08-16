package com.example.audiomemo.core.di

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.audiomemo.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SessionManager
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Singleton

private const val TAG = "SupabaseModule"

/**
 * Provides the single [SupabaseClient] instance for the app, with the Auth plugin installed.
 * Session persistence is backed by the same [DataStore] already provided by [PreferencesModule],
 * so login survives app restarts without a dedicated DataStore instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(dataStore: DataStore<Preferences>): SupabaseClient {
        check(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "SUPABASE_URL is missing — add it to AudioMemo/local.properties (see am1-1 story / README)"
        }
        check(BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "SUPABASE_ANON_KEY is missing — add it to AudioMemo/local.properties (see am1-1 story / README)"
        }

        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                sessionManager = DataStoreSessionManager(dataStore)
            }
        }
    }
}

/**
 * [SessionManager] implementation backed by the app's [DataStore], so gotrue-kt can persist and
 * restore the Supabase session (with automatic token refresh) across app restarts.
 *
 * `internal` (not `private`) so [DataStoreSessionManagerTest] can construct it directly against a
 * throwaway [DataStore] instead of the app's real one.
 */
internal class DataStoreSessionManager(
    private val dataStore: DataStore<Preferences>
) : SessionManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionKey = stringPreferencesKey("supabase_session")

    override suspend fun saveSession(session: UserSession) {
        try {
            dataStore.edit { prefs ->
                prefs[sessionKey] = json.encodeToString(UserSession.serializer(), session)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to persist Supabase session", e)
        }
    }

    override suspend fun loadSession(): UserSession? {
        val raw = try {
            dataStore.data.first()[sessionKey]
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read Supabase session", e)
            null
        } ?: return null
        return runCatching { json.decodeFromString(UserSession.serializer(), raw) }.getOrNull()
    }

    override suspend fun deleteSession() {
        try {
            dataStore.edit { prefs -> prefs.remove(sessionKey) }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to delete Supabase session", e)
        }
    }
}

package com.example.audiomemo.core.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.jan.supabase.gotrue.user.UserSession
import io.kotest.core.spec.style.StringSpec
import kotlinx.datetime.Instant
import java.io.File

/**
 * Round-trip coverage for the session-persistence mechanism that is the whole point of am1-1: a
 * bug in [DataStoreSessionManager]'s serialization would make "persistent login" silently degrade
 * into "logged out on every restart" without any test catching it — see the story's I/O matrix,
 * "Reabertura com sessão válida".
 */
class DataStoreSessionManagerTest : StringSpec({

    fun newManager(): DataStoreSessionManager {
        val file = File.createTempFile("supabase_session_test", ".preferences_pb")
        file.deleteOnExit()
        return DataStoreSessionManager(
            PreferenceDataStoreFactory.create(produceFile = { file })
        )
    }

    fun sampleSession() = UserSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        expiresIn = 3600,
        tokenType = "bearer",
        user = null,
        expiresAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    "loadSession returns null when nothing was ever saved" {
        val manager = newManager()

        check(manager.loadSession() == null)
    }

    "saveSession then loadSession returns an equal session" {
        val manager = newManager()
        val session = sampleSession()

        manager.saveSession(session)
        val loaded = manager.loadSession()

        check(loaded == session) { "expected $session, got $loaded" }
    }

    "loadSession after deleteSession returns null" {
        val manager = newManager()
        val session = sampleSession()

        manager.saveSession(session)
        manager.deleteSession()

        check(manager.loadSession() == null)
    }
})

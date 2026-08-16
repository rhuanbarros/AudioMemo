package com.example.audiomemo.features.cloudsync.ui

import com.example.audiomemo.features.cloudsync.domain.SupabaseAuthRepository
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Drives [CloudSyncSettingsViewModel.signIn] against a fake [SupabaseAuthRepository] to confirm
 * the `uiState` transition sequence — the story's own AC2/AC3 ("mostra confirmação de sucesso" /
 * "vejo erro claro").
 *
 * Uses [StandardTestDispatcher] (not [kotlinx.coroutines.test.UnconfinedTestDispatcher]) with an
 * explicit `runCurrent()` right after subscribing to `uiState`, so the collector is genuinely
 * suspended-and-waiting *before* `signIn()` flips the state — otherwise `StateFlow`'s conflation
 * could let a late subscriber skip straight past `Loading` to the final state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSyncSettingsViewModelSignInTest : StringSpec({

    val testDispatcher = StandardTestDispatcher()

    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    "signIn transitions Idle -> Loading -> Success on a successful repository call" {
        runTest(testDispatcher) {
            val repository = FakeSupabaseAuthRepository(signInResult = Result.success(Unit))
            val viewModel = CloudSyncSettingsViewModel(repository)
            val observedStates = mutableListOf<SignInUiState>()
            val collectJob = launch { viewModel.uiState.toList(observedStates) }
            runCurrent() // let the collector subscribe and record the current value (Idle)

            viewModel.signIn("owner@kabbalah.app", "correct-password")
            advanceUntilIdle()

            check(observedStates == listOf(SignInUiState.Idle, SignInUiState.Loading, SignInUiState.Success)) {
                "expected [Idle, Loading, Success], got $observedStates"
            }
            check(repository.signInCallCount == 1)

            collectJob.cancel()
        }
    }

    "signIn transitions Idle -> Loading -> Error on a failed repository call" {
        runTest(testDispatcher) {
            val repository = FakeSupabaseAuthRepository(
                signInResult = Result.failure(IllegalStateException("boom"))
            )
            val viewModel = CloudSyncSettingsViewModel(repository)
            val observedStates = mutableListOf<SignInUiState>()
            val collectJob = launch { viewModel.uiState.toList(observedStates) }
            runCurrent() // let the collector subscribe and record the current value (Idle)

            viewModel.signIn("owner@kabbalah.app", "wrong-password")
            advanceUntilIdle()

            check(observedStates.size == 3) { "expected 3 states, got $observedStates" }
            check(observedStates[0] == SignInUiState.Idle && observedStates[1] == SignInUiState.Loading) {
                "expected [Idle, Loading, ...], got $observedStates"
            }
            check(observedStates[2] is SignInUiState.Error) {
                "expected the final state to be Error, got ${observedStates[2]}"
            }
            check(repository.signInCallCount == 1)

            collectJob.cancel()
        }
    }

    "signIn with a blank field never calls the repository" {
        runTest(testDispatcher) {
            val repository = FakeSupabaseAuthRepository(signInResult = Result.success(Unit))
            val viewModel = CloudSyncSettingsViewModel(repository)

            viewModel.signIn("owner@kabbalah.app", "")
            advanceUntilIdle()

            check(repository.signInCallCount == 0)
            check(viewModel.uiState.value is SignInUiState.Error)
        }
    }
})

private class FakeSupabaseAuthRepository(
    private val signInResult: Result<Unit>,
    private val sessionFlow: Flow<Boolean> = flowOf(false)
) : SupabaseAuthRepository {

    var signInCallCount: Int = 0
        private set

    override suspend fun signIn(email: String, password: String): Result<Unit> {
        signInCallCount++
        return signInResult
    }

    override fun currentSessionFlow(): Flow<Boolean> = sessionFlow
}

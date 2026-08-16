package com.example.audiomemo.features.cloudsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiomemo.features.cloudsync.domain.SupabaseAuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.exceptions.BadRequestRestException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Sign-in form state — independent from [isAuthenticated], which reflects the persisted session. */
sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Loading : SignInUiState
    data object Success : SignInUiState
    data class Error(val messageRes: SignInErrorType) : SignInUiState
}

enum class SignInErrorType {
    InvalidCredentials,
    Network,
    Unknown
}

@HiltViewModel
class CloudSyncSettingsViewModel @Inject constructor(
    private val repository: SupabaseAuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignInUiState>(SignInUiState.Idle)
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    val isAuthenticated: StateFlow<Boolean> = repository.currentSessionFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    fun signIn(email: String, password: String) {
        if (_uiState.value == SignInUiState.Loading) return
        // Defensive: the Save button is only enabled once both fields are non-blank, but signIn()
        // is a public entry point — guard here too so a future caller (test, deep link, ...) can't
        // trigger a request with an empty email/password.
        if (email.isBlank() || password.isBlank()) {
            _uiState.value = SignInUiState.Error(SignInErrorType.InvalidCredentials)
            return
        }

        _uiState.value = SignInUiState.Loading
        viewModelScope.launch {
            repository.signIn(email.trim(), password)
                .onSuccess {
                    _uiState.value = SignInUiState.Success
                }
                .onFailure { error ->
                    _uiState.value = SignInUiState.Error(mapSignInError(error))
                }
        }
    }
}

/**
 * Classifies a [signIn] failure into a user-facing [SignInErrorType].
 *
 * Only `BadRequestRestException` (400) and `UnauthorizedRestException` (401) — the status codes
 * gotrue-kt actually raises for a rejected email/password grant — map to [SignInErrorType.InvalidCredentials].
 * Other `RestException` subtypes (`NotFoundRestException`, or `UnknownRestException` — which is
 * also what a rate-limit/5xx response decodes to) fall through to [SignInErrorType.Unknown]
 * instead: telling the user "wrong password" when the real cause is a 429/5xx would be misleading.
 *
 * Network-level failures are distinguished so the user isn't told their credentials are wrong when
 * the real cause is connectivity: `HttpRequestException` is what supabase-kt's own HTTP client
 * wraps around *any* exception raised while a request is in flight, and plain [IOException] is
 * matched too as a defensive fallback for a raw connectivity failure (e.g. `UnknownHostException`)
 * that reaches this classifier from anywhere else. `io.ktor.client.plugins.HttpRequestTimeoutException`
 * is itself a `java.io.IOException` (ktor's `IOException` is a JVM typealias for it), so the
 * `IOException` branch already covers request timeouts without a separate case.
 *
 * Top-level (not a private ViewModel member) so it is unit-testable without instantiating the
 * ViewModel — see [CloudSyncSettingsViewModelTest].
 */
internal fun mapSignInError(error: Throwable): SignInErrorType = when (error) {
    is BadRequestRestException, is UnauthorizedRestException -> SignInErrorType.InvalidCredentials
    is HttpRequestException -> SignInErrorType.Network
    is IOException -> SignInErrorType.Network
    is RestException -> SignInErrorType.Unknown
    else -> SignInErrorType.Unknown
}

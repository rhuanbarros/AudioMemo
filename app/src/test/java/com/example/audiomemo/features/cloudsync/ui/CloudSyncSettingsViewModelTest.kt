package com.example.audiomemo.features.cloudsync.ui

import io.github.jan.supabase.exceptions.HttpRequestException
import io.kotest.core.spec.style.StringSpec
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import java.net.UnknownHostException

/**
 * Covers [mapSignInError] — the sign-in error classification used by the Cloud Sync settings
 * screen (am1-1), i.e. the "Credenciais inválidas" / "Sem rede ao logar" rows of the story's I/O &
 * Edge-Case Matrix.
 *
 * **The `BadRequestRestException`/`UnauthorizedRestException` branch (invalid credentials) is not
 * exercised here — genuinely investigated, not skipped for convenience:**
 * - Each `RestException` subclass (`BadRequestRestException`, `UnauthorizedRestException`,
 *   `NotFoundRestException`, `UnknownRestException`) exposes exactly one public constructor:
 *   `(error: String, response: HttpResponse, message: String? = null)` — there is no constructor
 *   that takes a plain status code or skips `HttpResponse`.
 * - `io.ktor.client.statement.HttpResponse` is `abstract`; a real instance is normally produced by
 *   `HttpClientCall`'s `@InternalAPI`-annotated constructor
 *   `HttpClientCall(client: HttpClient, requestData: HttpRequestData, responseData: HttpResponseData)`,
 *   which itself needs a working `HttpClientEngine` and ktor-internal `HttpRequestData`/
 *   `HttpResponseData` wiring (coroutine call-context, `ByteReadChannel` body, etc.).
 * - Hand-building that chain in test code means depending on multiple `@InternalAPI`-marked ktor
 *   internals with no cross-version stability guarantee — exactly what `io.ktor:ktor-client-mock`
 *   (`MockEngine`) exists to do safely, and that dependency is not part of this story's approved
 *   scope (am1-1 Code Map only lists `ktor-client-android`).
 *
 * That branch is instead covered by: (1) `mapSignInError`'s `when` matching the concrete
 * `BadRequestRestException`/`UnauthorizedRestException` types directly (code-reviewable, no runtime
 * ambiguity), and (2) the manual device sign-in check in the story's Verification section (real
 * invalid-credentials attempt against the live Supabase project).
 */
class CloudSyncSettingsViewModelTest : StringSpec({

    "a network-level HttpRequestException maps to Network" {
        val error = HttpRequestException("Unable to resolve host", HttpRequestBuilder())

        check(mapSignInError(error) == SignInErrorType.Network) {
            "expected Network, got ${mapSignInError(error)}"
        }
    }

    "a request timeout maps to Network" {
        val error = HttpRequestTimeoutException(HttpRequestBuilder())

        check(mapSignInError(error) == SignInErrorType.Network) {
            "expected Network, got ${mapSignInError(error)}"
        }
    }

    "a raw connectivity IOException (e.g. unresolved host) maps to Network" {
        val error = UnknownHostException("api.example.com")

        check(mapSignInError(error) == SignInErrorType.Network) {
            "expected Network, got ${mapSignInError(error)}"
        }
    }

    "an unexpected exception maps to Unknown" {
        val error = IllegalStateException("boom")

        check(mapSignInError(error) == SignInErrorType.Unknown) {
            "expected Unknown, got ${mapSignInError(error)}"
        }
    }
})

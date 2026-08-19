package com.example.audiomemo.features.transcript.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Best-effort GPS + reverse-geocode capture for a just-finished audio chunk
 * (TCK-20260819203531-d43b, gps-location-capture-per-chunk). Plain, manually-instantiated class —
 * not Hilt — mirroring [AudioRecorderManager]/[AudioInterruptionManager]'s pattern;
 * [com.example.audiomemo.features.transcript.service.AudioRecordingService] owns its single
 * instance and calls this from the already-async `onChunkCompleted` path, never the blocking
 * `onChunkStarted` one (per `wiki/ledger/decisao-de-produto/
 * audiomemo-gravacao-nunca-para-por-escolha-propria.md` in the kabbalah repo).
 *
 * Every Android SDK call in here (last-known-location fix, reverse geocode) is individually
 * guarded so a missing permission / no fix / geocode failure / thrown exception never propagates
 * out of [captureLocationSidecar] — it always resolves to `true`/`false`, never throws. The
 * caller (`AudioRecordingService`) additionally wraps its own call site in `runCatching` as
 * defense-in-depth, per the story's "never crash the service or block Whisper/Supabase enqueue"
 * boundary.
 *
 * The three functions in the companion object are the "small seam" the story's Code Map asks
 * for: pure, `Context`/SDK-free, and unit-testable from plain-JVM `src/test` — unlike
 * [captureLocationSidecar] itself, which touches `LocationManager`/`Geocoder` (both unmocked
 * Android stubs in this project's plain-JVM test setup, same class of limitation documented on
 * [com.example.audiomemo.features.cloudsync.data.SupabaseStorageRepository.uploadChunk]).
 */
class LocationCaptureManager {

    companion object {
        private const val TAG = "LocationCaptureManager"

        /**
         * Budget for the reverse-geocode call in [captureLocationSidecar]. Not specced by the
         * story (implementer discretion, per its Ask-First resolution) — 5s mirrors this
         * codebase's other disk/network-bound SDK-call budgets (e.g.
         * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker]'s
         * `AUTH_INIT_TIMEOUT_MS`) rather than inventing an unrelated number. On timeout, the
         * sidecar is still written with coordinates only, `address=unavailable` — same as any
         * other geocode failure (see the story's I/O matrix).
         */
        internal const val GEOCODE_TIMEOUT_MS = 5_000L

        /**
         * Review patch (Blind Hunter, review_loop_iteration 1): a cached fix from `LocationManager`
         * can be arbitrarily old if nothing else on the device has requested a fresh one recently
         * — without this check the sidecar's `capturedAt` (the chunk's finish time) could silently
         * pair with coordinates from hours/days earlier. 30 minutes is a judgment call (not specced
         * by the story), chosen to comfortably cover "still roughly where the chunk was recorded"
         * for a device that moves at ordinary human pace, without being so tight that a normal
         * multi-minute gap between fixes (e.g. GPS disabled, indoors) needlessly discards an
         * otherwise-good reading. A stale fix is treated exactly like "no fix" (see
         * [isLocationFresh]'s call site) — same skip behavior, not an error.
         */
        internal const val MAX_FIX_AGE_MS = 30 * 60 * 1_000L

        /**
         * Same basename as [audioFile], `.txt` extension. Pure, filesystem-path-only — no I/O.
         */
        internal fun sidecarFileFor(audioFile: File): File =
            File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.txt")

        /**
         * Review patch (Blind Hunter, review_loop_iteration 1): pure staleness check, extracted so
         * it's testable without a real `Location`/`LocationManager`. [fixTimeMillis] is
         * `Location.getTime()` (wall-clock UTC millis of the fix); [nowMillis] defaults to the real
         * clock. A negative age (clock skew, e.g. the fix's timestamp is ahead of "now") is treated
         * as fresh rather than stale — clock skew is not evidence of an old fix.
         */
        internal fun isLocationFresh(
            fixTimeMillis: Long,
            nowMillis: Long = System.currentTimeMillis(),
            maxAgeMillis: Long = MAX_FIX_AGE_MS
        ): Boolean {
            val age = nowMillis - fixTimeMillis
            return age <= maxAgeMillis
        }

        /**
         * Review patch (Blind Hunter, review_loop_iteration 1): `Geocoder.getAddressLine(0)` can
         * contain an embedded `\n`/`\r` depending on locale/provider — left unsanitized, that would
         * silently split into extra, unparseable `key=value`-looking lines in the sidecar. Replaces
         * any run of newline/carriage-return/tab characters with a single space; every other
         * character (including commas, which are part of a normal address) is left untouched.
         */
        internal fun sanitizeAddress(address: String): String =
            address.replace(Regex("[\\r\\n\\t]+"), " ").trim()

        /**
         * Sidecar body — plain text, one `key=value` per line, exact shape from the story's
         * Design Notes. [address] reads literally `unavailable` when geocoding didn't resolve
         * (failed, timed out, or was never attempted because there was no fix). Pure/testable.
         * [address] is expected pre-sanitized by the caller (see [sanitizeAddress]) — this function
         * does not sanitize on its own so its output stays a direct, predictable function of its
         * input for the existing formatting tests.
         */
        internal fun formatSidecarContent(
            latitude: Double,
            longitude: Double,
            address: String?,
            capturedAtIso: String
        ): String = buildString {
            append("latitude=").append(latitude).append('\n')
            append("longitude=").append(longitude).append('\n')
            append("address=").append(address ?: "unavailable").append('\n')
            append("capturedAt=").append(capturedAtIso)
        }

        /**
         * UTC ISO-8601 with a literal `Z` suffix (e.g. `2026-08-19T20:35:31Z`), matching the
         * story's Design Notes example exactly. [now] defaults to the real clock in production;
         * tests pass an explicit value for a deterministic assertion — same seam pattern as
         * [com.example.audiomemo.core.preferences.AppPreferencesRepository.recordHeartbeat].
         * `java.time.Instant` is unavailable here (minSdk 24, no core-library desugaring
         * configured in this module), hence `SimpleDateFormat` + an explicit UTC [TimeZone]
         * rather than the JSR-310 API.
         */
        internal fun currentTimestampIso(now: Long = System.currentTimeMillis()): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter.format(Date(now))
        }
    }

    /**
     * Best-effort capture: writes a `.txt` sidecar next to [audioFile] when a last-known fix is
     * available, does nothing (returns `false`, writes no file) otherwise — permission missing,
     * no cached fix on either provider, or any thrown exception along the way. A geocode
     * failure/timeout is NOT one of those skip cases: the sidecar is still written with
     * coordinates only, per the story's I/O matrix.
     */
    suspend fun captureLocationSidecar(context: Context, audioFile: File): Boolean = try {
        if (!hasLocationPermission(context)) {
            false
        } else {
            val location = lastKnownLocation(context)
            if (location == null || !isLocationFresh(location.time)) {
                // A stale fix (see isLocationFresh's KDoc for the threshold rationale) is treated
                // exactly like "no fix" — review patch, Blind Hunter, review_loop_iteration 1.
                false
            } else {
                val rawAddress = withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        reverseGeocode(context, location.latitude, location.longitude)
                    }
                }
                val address = rawAddress?.let { sanitizeAddress(it) }
                val content = formatSidecarContent(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    address = address,
                    capturedAtIso = currentTimestampIso()
                )
                val sidecarFile = sidecarFileFor(audioFile)
                try {
                    sidecarFile.writeText(content)
                    true
                } catch (e: Exception) {
                    // Review patch (Edge Case Hunter, review_loop_iteration 1): writeText can throw
                    // mid-write (disk full, IO error) and leave a partial/corrupt file that would
                    // otherwise still pass sidecarFile.exists() and get uploaded as-is by
                    // SupabaseUploadWorker. Best-effort delete of whatever partial content landed,
                    // never worth crashing the capture over if the delete itself also fails.
                    runCatching { sidecarFile.delete() }
                    throw e
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Location capture failed for ${audioFile.name}", e)
        false
    }

    /**
     * Review patch (Blind Hunter + Edge Case Hunter, review_loop_iteration 1): originally checked
     * only `ACCESS_FINE_LOCATION`, which meant a user who picked Android's "Use approximate
     * location" option (grants COARSE, denies FINE) had the whole feature gated off — even though
     * `NETWORK_PROVIDER` (see [lastKnownLocation]) only needs COARSE. Now true if either is
     * granted; [lastKnownLocation] itself still gates the GPS-specific provider on FINE alone,
     * since `GPS_PROVIDER` requires it regardless of what this check returns.
     */
    private fun hasLocationPermission(context: Context): Boolean =
        hasFineLocationPermission(context) || hasCoarseLocationPermission(context)

    private fun hasFineLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * GPS provider first (only attempted when FINE is granted — `GPS_PROVIDER` requires it, a
     * COARSE-only grant would otherwise throw `SecurityException` on every call and rely on the
     * catch-all in [safeLastKnownLocation] to mask it), Network provider as fallback (usable with
     * either FINE or COARSE). No active fix request (per the story's Never clause: no
     * `ACCESS_BACKGROUND_LOCATION`, only whatever the system already has cached). Each provider
     * read is individually guarded: a device without a GPS chip (or with the provider disabled)
     * must still fall through to Network, not abort the whole lookup.
     */
    private fun lastKnownLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val gpsFix = if (hasFineLocationPermission(context)) {
            safeLastKnownLocation(locationManager, LocationManager.GPS_PROVIDER)
        } else {
            null
        }
        return gpsFix ?: safeLastKnownLocation(locationManager, LocationManager.NETWORK_PROVIDER)
    }

    private fun safeLastKnownLocation(locationManager: LocationManager, provider: String): Location? =
        try {
            locationManager.getLastKnownLocation(provider)
        } catch (e: Exception) {
            // SecurityException (permission revoked between the check above and this call) or
            // IllegalArgumentException (provider doesn't exist on this device) both mean "no fix
            // from this provider" — never worth crashing the capture over.
            null
        }

    @Suppress("DEPRECATION") // The synchronous Geocoder.getFromLocation is deprecated on API 33+
    // in favor of a listener-based overload; minSdk 24 still needs the synchronous form, and it
    // remains functional (not removed) on every API level this app supports.
    private fun reverseGeocode(context: Context, latitude: Double, longitude: Double): String? =
        try {
            Geocoder(context, Locale.getDefault())
                .getFromLocation(latitude, longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        } catch (e: Exception) {
            null
        }
}

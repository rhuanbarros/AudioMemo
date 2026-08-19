package com.example.audiomemo.features.transcript.manager

import io.kotest.core.spec.style.StringSpec
import java.io.File

/**
 * Covers the pure/testable "seam" the story's Code Map asks for — [LocationCaptureManager]'s
 * file-path, formatting, and timestamp logic — without touching `LocationManager`/`Geocoder`
 * (unmocked Android stubs in this project's plain-JVM `src/test`, same class of limitation
 * documented on [com.example.audiomemo.features.cloudsync.data.SupabaseStorageRepository.
 * uploadChunk]'s KDoc). [LocationCaptureManager.captureLocationSidecar] itself is exercised only
 * by manual/device verification, same as that class's `uploadChunk`.
 */
class LocationCaptureManagerTest : StringSpec({

    "sidecarFileFor swaps the .m4a extension for .txt, same basename, same parent dir" {
        val audioFile = File("/data/user/0/com.example.audiomemo/files/chunk_20260819_120000.m4a")

        val sidecar = LocationCaptureManager.sidecarFileFor(audioFile)

        check(sidecar.parentFile == audioFile.parentFile)
        check(sidecar.name == "chunk_20260819_120000.txt")
    }

    "formatSidecarContent renders latitude/longitude/address/capturedAt, one key=value per line" {
        val content = LocationCaptureManager.formatSidecarContent(
            latitude = -23.5505,
            longitude = -46.6333,
            address = "Av. Paulista, 1000, São Paulo",
            capturedAtIso = "2026-08-19T20:35:31Z"
        )

        check(
            content == "latitude=-23.5505\n" +
                "longitude=-46.6333\n" +
                "address=Av. Paulista, 1000, São Paulo\n" +
                "capturedAt=2026-08-19T20:35:31Z"
        ) { "unexpected sidecar content:\n$content" }
    }

    "formatSidecarContent writes address=unavailable when address is null (geocode failed/timed out)" {
        val content = LocationCaptureManager.formatSidecarContent(
            latitude = -23.5505,
            longitude = -46.6333,
            address = null,
            capturedAtIso = "2026-08-19T20:35:31Z"
        )

        check(content.contains("address=unavailable")) {
            "expected 'address=unavailable' for a null address, got:\n$content"
        }
    }

    "currentTimestampIso formats a fixed instant as UTC ISO-8601 with a literal Z suffix" {
        // 2026-08-19T20:35:31Z in epoch millis.
        val fixedNow = 1787171731000L

        val iso = LocationCaptureManager.currentTimestampIso(now = fixedNow)

        check(iso == "2026-08-19T20:35:31Z") { "expected 2026-08-19T20:35:31Z, got $iso" }
    }

    // ── review_loop_iteration 1 patches (Blind Hunter + Edge Case Hunter) ────────────────

    "isLocationFresh is true for a fix within the max age" {
        val now = 1_000_000L
        val fixTime = now - LocationCaptureManager.MAX_FIX_AGE_MS + 1

        check(LocationCaptureManager.isLocationFresh(fixTime, now))
    }

    "isLocationFresh is false for a fix older than the max age" {
        val now = 1_000_000L
        val fixTime = now - LocationCaptureManager.MAX_FIX_AGE_MS - 1

        check(!LocationCaptureManager.isLocationFresh(fixTime, now))
    }

    "isLocationFresh is true for a fix exactly at the max age boundary" {
        val now = 1_000_000L
        val fixTime = now - LocationCaptureManager.MAX_FIX_AGE_MS

        check(LocationCaptureManager.isLocationFresh(fixTime, now))
    }

    "isLocationFresh treats a fix timestamped ahead of now (clock skew) as fresh" {
        val now = 1_000_000L
        val fixTime = now + 60_000L

        check(LocationCaptureManager.isLocationFresh(fixTime, now))
    }

    "sanitizeAddress collapses embedded newlines/carriage-returns/tabs into a single space" {
        val sanitized = LocationCaptureManager.sanitizeAddress("Av. Paulista, 1000\nSão Paulo\r\n-\tSP")

        check(!sanitized.contains('\n'))
        check(!sanitized.contains('\r'))
        check(!sanitized.contains('\t'))
        check(sanitized == "Av. Paulista, 1000 São Paulo - SP") { "got: $sanitized" }
    }

    "sanitizeAddress leaves a normal comma-containing address untouched" {
        val address = "Av. Paulista, 1000, São Paulo, SP"

        check(LocationCaptureManager.sanitizeAddress(address) == address)
    }

    "sanitizeAddress trims leading/trailing whitespace produced by a leading/trailing newline" {
        val sanitized = LocationCaptureManager.sanitizeAddress("\nSão Paulo\n")

        check(sanitized == "São Paulo") { "got: '$sanitized'" }
    }
})

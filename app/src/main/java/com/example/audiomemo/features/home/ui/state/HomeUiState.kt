package com.example.audiomemo.features.home.ui.state

import com.example.audiomemo.features.home.domain.model.HealthSegment

/** Home's "status ao vivo" block (am-hotfix-home-status-redesign) — derived purely from the most
 *  recent [com.example.audiomemo.features.transcript.domain.model.Session], never from a manual
 *  control. [sinceMs] is the owning session's `startTime`, used to compute a live-ticking elapsed
 *  duration in the UI layer. */
sealed interface LiveRecordingStatus {
    data class Recording(val sinceMs: Long) : LiveRecordingStatus
    data class Paused(val sinceMs: Long) : LiveRecordingStatus

    /** No session yet, or the most recent session is `STOPPED` (e.g. right after an explicit
     *  stop, or briefly before an always-on mechanism resumes it) — genuinely unknown/transient,
     *  not one of the two "normal" live states. */
    data object Unknown : LiveRecordingStatus
}

/**
 * Home's health banner (block 5) — a **sealed** decision instead of a plain `String` so the
 * actual user-facing text stays in `strings.xml` (resolved by the Composable via
 * [androidx.compose.ui.res.stringResource]) while the decision of *which* variant to show stays
 * a pure, testable function ([com.example.audiomemo.features.home.ui.HomeViewModel.computeHealthBanner]).
 * Three variants, not two — [NoData] is deliberately distinct from [Normal]: a fresh install or a
 * rotated-away log has no classifiable history at all, which is not the same claim as "confirmed
 * everything is fine" (code review finding, patch C).
 */
sealed interface HealthBanner {
    data object Normal : HealthBanner
    data object NoData : HealthBanner
    data class Error(val detail: String) : HealthBanner
}

data class HomeUiState(
    val liveStatus: LiveRecordingStatus = LiveRecordingStatus.Unknown,
    /** Always [com.example.audiomemo.features.home.ui.HomeViewModel.HEALTH_STRIP_HOURS] items,
     *  oldest hour first. */
    val healthSegments: List<HealthSegment> = emptyList(),
    val pendingUploadCount: Int = 0,
    val pendingUploadBytes: Long = 0L,
    /** Best-effort inference (Design Notes) — never a direct `ConnectivityManager` read. */
    val isLikelyOffline: Boolean = false,
    val uploadedLocalCount: Int = 0,
    val uploadedLocalBytes: Long = 0L,
    val healthBanner: HealthBanner = HealthBanner.NoData
)

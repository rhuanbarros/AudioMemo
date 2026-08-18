package com.example.audiomemo.features.home.ui

import com.example.audiomemo.features.summary.domain.model.SummaryStatus
import com.example.audiomemo.features.transcript.domain.model.SessionState

/**
 * Moved here from `features.meetings.ui` (am-hotfix-home-status-redesign) — after the Meetings
 * tab/dashboard was removed, this list item type is exclusive to Home's "Recent" sessions list.
 */
data class MeetingListItem(
    val sessionId: Long,
    val title: String,
    val startTime: Long,
    val durationMs: Long,
    val sessionState: SessionState,
    val summaryStatus: SummaryStatus?
)

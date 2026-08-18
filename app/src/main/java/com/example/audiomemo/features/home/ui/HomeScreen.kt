package com.example.audiomemo.features.home.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audiomemo.R
import com.example.audiomemo.features.home.domain.model.HealthSegment
import com.example.audiomemo.features.home.domain.model.HealthState
import com.example.audiomemo.features.home.ui.state.HealthBanner
import com.example.audiomemo.features.home.ui.state.HomeUiState
import com.example.audiomemo.features.home.ui.state.LiveRecordingStatus
import com.example.audiomemo.features.summary.domain.model.SummaryStatus
import com.example.audiomemo.features.transcript.domain.model.SessionState
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import com.example.audiomemo.ui.theme.AudioMemoTheme
import com.example.audiomemo.ui.theme.RecordingRed
import com.example.audiomemo.ui.theme.SuccessGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val TAG = "HomeScreen"

/**
 * Home as a pure status/health panel (am-hotfix-home-status-redesign) — no manual recording
 * control anywhere on this screen (no start/pause/stop). Five blocks, top to bottom: live status,
 * 24h health strip, cloud sync, local storage, health banner — followed by the unchanged "Recent"
 * sessions list.
 *
 * **`RECORD_AUDIO` (+ `POST_NOTIFICATIONS` on API 33+) permission request** (code review, patch
 * 0 — CRITICAL): this is the app's only remaining entry point for it. The pre-redesign Home had a
 * "Tap to Record" button that requested the permission on tap, right before navigating to the
 * transcript screen; that button is gone (this story's whole point), and no onboarding screen
 * exists anywhere else in the app. Without an auto-request here, a fresh install could never grant
 * the permission — [com.example.audiomemo.AudioMemoApplication.onCreate]'s always-on autostart is
 * gated on it already being granted and silently no-ops otherwise, so recording could never start,
 * ever. [LaunchedEffect] re-checks the live permission state every time this composable enters
 * composition (e.g. navigating back from Settings) but only launches the system dialog when it's
 * still not granted — once granted, this is a no-op forever after, so it stays exactly as
 * hands-off as every other part of this screen (an OS consent gate is not a recording control).
 */
@Composable
fun HomeScreen(
    onNavigateToMeetingDetails: (Long) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val recentMeetings by viewModel.recentMeetings.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            startRecordingServiceAfterPermissionGranted(context)
        }
        // Denied: no in-app UI reacts to this (same "zero manual controls" boundary as the rest
        // of this screen) — the OS's own re-prompt/rationale policy applies, same as before.
    }

    LaunchedEffect(Unit) {
        val hasRecordAudio = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasRecordAudio) {
            val permissions = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            permissionLauncher.launch(permissions)
        }
    }

    HomeContent(
        uiState = uiState,
        recentMeetings = recentMeetings,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToLogs = onNavigateToLogs,
        onMeetingClick = onNavigateToMeetingDetails,
        onDeleteMeeting = { viewModel.deleteSession(it) },
        onClearUploadedLocalFiles = { viewModel.clearUploadedLocalFiles() }
    )
}

/**
 * Starts the always-on recording service right after the owner grants `RECORD_AUDIO` from the
 * [HomeScreen] auto-prompt — otherwise recording would only actually begin on the *next* process
 * start (`AudioMemoApplication.onCreate()` already re-checked the permission and bailed out before
 * this grant happened). Same `Intent` + `ContextCompat.startForegroundService` + defensive
 * `IllegalStateException`/`SecurityException` handling already established by
 * `AudioMemoApplication.onCreate()` / `TranscriptScreen.kt` / `RecordingWatchdogWorker` /
 * `BootCompletedReceiver` — this is the 5th call site sharing that exact idiom, not a new one.
 */
private fun startRecordingServiceAfterPermissionGranted(context: android.content.Context) {
    try {
        ContextCompat.startForegroundService(
            context, Intent(context, AudioRecordingService::class.java)
        )
    } catch (e: IllegalStateException) {
        // API 31+: a background-initiated foreground-service start can be rejected. Degrades
        // safely — the watchdog (~15min) or the next process start still covers the gap.
        Log.w(TAG, "Failed to start recording right after permission grant", e)
    } catch (e: SecurityException) {
        // E.g. the permission was revoked again between the grant callback and this call.
        Log.w(TAG, "Failed to start recording right after permission grant", e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    uiState: HomeUiState,
    recentMeetings: List<MeetingListItem>,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onMeetingClick: (Long) -> Unit,
    onDeleteMeeting: (Long) -> Unit = {},
    onClearUploadedLocalFiles: () -> Unit = {}
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            item { LiveStatusCard(uiState.liveStatus) }
            item { HealthStripCard(uiState.healthSegments) }
            item {
                CloudSyncCard(
                    pendingCount = uiState.pendingUploadCount,
                    pendingBytes = uiState.pendingUploadBytes,
                    isLikelyOffline = uiState.isLikelyOffline
                )
            }
            item {
                LocalStorageCard(
                    uploadedCount = uiState.uploadedLocalCount,
                    uploadedBytes = uiState.uploadedLocalBytes,
                    onClearClick = onClearUploadedLocalFiles
                )
            }
            item {
                HealthBannerCard(
                    banner = uiState.healthBanner,
                    onClick = onNavigateToLogs
                )
            }

            // Recent recordings header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_recent),
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // Recording cards or empty state
            if (recentMeetings.isEmpty()) {
                item { EmptyRecordingsState() }
            } else {
                items(recentMeetings, key = { it.sessionId }) { meeting ->
                    SwipeToDeleteWrapper(
                        onDelete = { onDeleteMeeting(meeting.sessionId) },
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        RecordingCard(
                            meeting = meeting,
                            onClick = { onMeetingClick(meeting.sessionId) }
                        )
                    }
                }
            }
        }
    }
}

// ── Block 1: Live status ────────────────────────────────────────────────────

@Composable
private fun LiveStatusCard(status: LiveRecordingStatus) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isTicking = status is LiveRecordingStatus.Recording || status is LiveRecordingStatus.Paused

    LaunchedEffect(isTicking) {
        if (isTicking) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    val (label, color, sinceMs) = when (status) {
        is LiveRecordingStatus.Recording ->
            Triple(stringResource(R.string.home_status_recording), RecordingRed, status.sinceMs)
        is LiveRecordingStatus.Paused ->
            Triple(stringResource(R.string.home_status_paused), MaterialTheme.colorScheme.tertiary, status.sinceMs)
        LiveRecordingStatus.Unknown ->
            Triple(stringResource(R.string.home_status_unknown), MaterialTheme.colorScheme.outline, null)
    }

    val blink by rememberInfiniteTransition(label = "liveStatusBlink").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(animation = tween(700), repeatMode = RepeatMode.Reverse),
        label = "liveStatusBlinkAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = if (status is LiveRecordingStatus.Unknown) 1f else blink))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            if (sinceMs != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatElapsed(nowMs - sinceMs),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ── Block 2: 24h health strip ───────────────────────────────────────────────

@Composable
private fun HealthStripCard(segments: List<HealthSegment>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_health_strip_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                segments.forEach { segment ->
                    val segmentDescription = stringResource(
                        R.string.home_health_segment_cd,
                        formatHour(segment.hourStart),
                        stringResource(segment.state.toLabelRes())
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(3.dp))
                            .background(segment.state.toColor())
                            .semantics { contentDescription = segmentDescription }
                    )
                }
            }
        }
    }
}

@Composable
private fun HealthState.toColor(): Color = when (this) {
    HealthState.RECORDING -> SuccessGreen
    HealthState.PAUSED -> MaterialTheme.colorScheme.tertiary
    HealthState.ERROR -> MaterialTheme.colorScheme.error
    HealthState.NO_DATA -> MaterialTheme.colorScheme.surfaceVariant
}

/** Accessibility label for a health-strip segment (code review, patch F). */
private fun HealthState.toLabelRes(): Int = when (this) {
    HealthState.RECORDING -> R.string.home_health_state_recording
    HealthState.PAUSED -> R.string.home_health_state_paused
    HealthState.ERROR -> R.string.home_health_state_error
    HealthState.NO_DATA -> R.string.home_health_state_no_data
}

// ── Block 3: Cloud sync ─────────────────────────────────────────────────────

@Composable
private fun CloudSyncCard(pendingCount: Int, pendingBytes: Long, isLikelyOffline: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_sync_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (pendingCount == 0) {
                    stringResource(R.string.home_sync_up_to_date)
                } else {
                    stringResource(R.string.home_sync_pending, pendingCount, formatBytes(pendingBytes))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (isLikelyOffline) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.home_sync_offline_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ── Block 4: Local storage ──────────────────────────────────────────────────

@Composable
private fun LocalStorageCard(uploadedCount: Int, uploadedBytes: Long, onClearClick: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_storage_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (uploadedCount == 0) {
                    stringResource(R.string.home_storage_empty)
                } else {
                    stringResource(R.string.home_storage_count, uploadedCount, formatBytes(uploadedBytes))
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (uploadedCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { showConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_storage_clear_button))
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.home_storage_clear_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.home_storage_clear_confirm_body,
                        uploadedCount,
                        formatBytes(uploadedBytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onClearClick()
                }) { Text(stringResource(R.string.home_storage_clear_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

// ── Block 5: Health banner ──────────────────────────────────────────────────

/**
 * Three distinct visual variants (code review, patch C) — [HealthBanner.NoData] gets its own
 * neutral styling, never the success (green-ish) or error (red-ish) treatment: "no history yet"
 * is not the same claim as "confirmed everything is fine".
 */
@Composable
private fun HealthBannerCard(banner: HealthBanner, onClick: () -> Unit) {
    val text = when (banner) {
        HealthBanner.Normal -> stringResource(R.string.home_banner_normal)
        HealthBanner.NoData -> stringResource(R.string.home_banner_no_data)
        is HealthBanner.Error -> stringResource(R.string.home_banner_error, banner.detail)
    }
    val containerColor = when (banner) {
        is HealthBanner.Error -> MaterialTheme.colorScheme.errorContainer
        HealthBanner.Normal -> MaterialTheme.colorScheme.primaryContainer
        HealthBanner.NoData -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (banner) {
        is HealthBanner.Error -> MaterialTheme.colorScheme.onErrorContainer
        HealthBanner.Normal -> MaterialTheme.colorScheme.onPrimaryContainer
        HealthBanner.NoData -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Recording card (unchanged from the pre-redesign Home) ──────────────────

@Composable
private fun RecordingCard(
    meeting: MeetingListItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Mic icon badge
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Title + meta
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meeting.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formatDate(meeting.startTime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = formatDuration(meeting.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Status badge + chevron
            Column(horizontalAlignment = Alignment.End) {
                SummaryStatusBadge(status = meeting.summaryStatus)
                Spacer(modifier = Modifier.height(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryStatusBadge(status: SummaryStatus?) {
    val (labelRes, color) = when (status) {
        SummaryStatus.DONE -> R.string.home_status_summarized to MaterialTheme.colorScheme.primary
        SummaryStatus.GENERATING -> R.string.home_status_processing to MaterialTheme.colorScheme.tertiary
        SummaryStatus.FAILED -> R.string.home_status_failed to MaterialTheme.colorScheme.error
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyRecordingsState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
        Text(
            text = stringResource(R.string.home_no_recordings_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.home_no_recordings_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

// ── Swipe-to-delete wrapper ───────────────────────────────────────────────────
// private (code review, patch H): its only external consumer, MeetingsDashboardScreen, was
// deleted in this same story — this is now used exclusively from within this file.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteWrapper(
    onDelete: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val bgColor by animateColorAsState(
                targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0f),
                label = "swipeDeleteBg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(bgColor)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete recording",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        content()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDate(millis: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(millis))

/** Hour-of-day label for a health-strip segment's accessibility description (patch F) — e.g. "14:00". */
private fun formatHour(millis: Long): String =
    SimpleDateFormat("HH:00", Locale.getDefault()).format(Date(millis))

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Ticking elapsed-time display for the live status card — "H:MM:SS" once past an hour (an
 *  always-on session routinely runs for many hours), "M:SS" before that. Negative input (a clock
 *  skew edge case) is clamped to zero rather than showing a nonsensical negative timer. */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** KB below 1MB (rounded up so a non-empty file never displays as "0 KB"), MB with one decimal
 *  from 1MB up — matches the scale of a single 2-minute audio chunk (tens/hundreds of KB) through
 *  a whole day of unsynced chunks (tens of MB). */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = (bytes + 1023) / 1024
    return if (kb < 1024) {
        "$kb KB"
    } else {
        "%.1f MB".format(kb / 1024.0)
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    AudioMemoTheme {
        HomeContent(
            uiState = HomeUiState(
                liveStatus = LiveRecordingStatus.Recording(System.currentTimeMillis() - 3_723_000),
                healthSegments = previewHealthSegments(),
                pendingUploadCount = 0,
                pendingUploadBytes = 0L,
                isLikelyOffline = false,
                uploadedLocalCount = 0,
                uploadedLocalBytes = 0L,
                healthBanner = HealthBanner.Normal
            ),
            recentMeetings = listOf(
                MeetingListItem(
                    sessionId = 1L,
                    title = "Team Standup – Sprint Review",
                    startTime = System.currentTimeMillis() - 3_600_000,
                    durationMs = 15 * 60 * 1000L,
                    sessionState = SessionState.STOPPED,
                    summaryStatus = SummaryStatus.DONE
                ),
                MeetingListItem(
                    sessionId = 2L,
                    title = "Product Roadmap Discussion",
                    startTime = System.currentTimeMillis() - 86_400_000,
                    durationMs = 45 * 60 * 1000L,
                    sessionState = SessionState.STOPPED,
                    summaryStatus = SummaryStatus.GENERATING
                )
            ),
            onMeetingClick = {}
        )
    }
}

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeScreenErrorPreview() {
    AudioMemoTheme {
        HomeContent(
            uiState = HomeUiState(
                liveStatus = LiveRecordingStatus.Unknown,
                healthSegments = previewHealthSegments(errorTail = true),
                pendingUploadCount = 3,
                pendingUploadBytes = 512_000L,
                isLikelyOffline = true,
                uploadedLocalCount = 1,
                uploadedLocalBytes = 128_000L,
                healthBanner = HealthBanner.Error("Hardware error — recording stopped")
            ),
            recentMeetings = emptyList(),
            onMeetingClick = {}
        )
    }
}

private fun previewHealthSegments(errorTail: Boolean = false): List<HealthSegment> =
    (0 until 24).map { i ->
        val state = when {
            errorTail && i >= 20 -> HealthState.ERROR
            i < 2 -> HealthState.NO_DATA
            i in 10..12 -> HealthState.PAUSED
            else -> HealthState.RECORDING
        }
        HealthSegment(0L, 0L, state)
    }

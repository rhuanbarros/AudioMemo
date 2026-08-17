package com.example.audiomemo.features.transcript.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseInOut
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.audiomemo.R
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.audiomemo.features.transcript.service.AudioRecordingService
import com.example.audiomemo.features.transcript.ui.state.TranscriptUiState
import com.example.audiomemo.ui.theme.AudioMemoTheme
import com.example.audiomemo.ui.theme.RecordingRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun TranscriptScreen(
    onNavigateToSummary: (Long) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: TranscriptViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var recordingService by remember { mutableStateOf<AudioRecordingService?>(null) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                recordingService = (binder as? AudioRecordingService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                recordingService = null
            }
        }
    }

    // Track whether RECORD_AUDIO permission has been granted.
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasRecordPermission = granted }

    // Request the permission immediately if it is not already held.
    LaunchedEffect(Unit) {
        if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Re-evaluate the permission on every resume so that one-time grants revoked while
    // the app was backgrounded (Android 11+) are detected before the service restarts.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (current != hasRecordPermission) hasRecordPermission = current
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Start and bind the recording service only once permission is confirmed.
    DisposableEffect(hasRecordPermission) {
        var bound = false
        if (hasRecordPermission) {
            val intent = Intent(context, AudioRecordingService::class.java)
            ContextCompat.startForegroundService(context, intent)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            bound = true
        }
        onDispose {
            if (bound) {
                try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            }
        }
    }

    val amplitude by (recordingService?.amplitude ?: MutableStateFlow(0))
        .collectAsState(initial = 0)
    val serviceIsStopped by (recordingService?.isStopped ?: MutableStateFlow(false))
        .collectAsState(initial = false)

    // When the service connects, wait for the first valid session ID and forward it
    // to the ViewModel so the live-transcript Flow starts querying the right session.
    LaunchedEffect(recordingService) {
        val service = recordingService ?: return@LaunchedEffect
        val sessionId = service.currentSessionIdFlow.first { it > 0L }
        viewModel.onSessionStarted(sessionId)
    }

    LaunchedEffect(serviceIsStopped) {
        if (serviceIsStopped) {
            viewModel.onRecordingStopped(recordingService?.currentSessionId ?: -1L)
        }
    }

    val uiState by viewModel.uiState.collectAsState()
    val isRecording = uiState is TranscriptUiState.Recording
    val postState = uiState as? TranscriptUiState.PostRecording

    var selectedTab by remember { mutableIntStateOf(2) } // default: Transcript tab
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (true) {
                delay(1_000L)
                elapsedSeconds++
            }
        }
    }

    TranscriptContent(
        uiState = uiState,
        isRecording = isRecording,
        postState = postState,
        amplitude = amplitude,
        elapsedSeconds = elapsedSeconds,
        selectedTab = selectedTab,
        onTabSelected = { index ->
            if (index == 1 && postState != null && postState.sessionId > 0L) {
                onNavigateToSummary(postState.sessionId)
            } else {
                selectedTab = index
            }
        },
        onStop = {
            val sessionId = recordingService?.currentSessionId ?: -1L
            context.startService(
                Intent(context, AudioRecordingService::class.java).apply {
                    action = AudioRecordingService.ACTION_STOP
                }
            )
            viewModel.onRecordingStopped(sessionId)
        },
        onViewSummary = { id -> onNavigateToSummary(id) },
        onNavigateBack = onNavigateBack
    )
}

// ── Main layout ───────────────────────────────────────────────────────────────

@Composable
private fun TranscriptContent(
    uiState: TranscriptUiState,
    isRecording: Boolean,
    postState: TranscriptUiState.PostRecording?,
    amplitude: Int,
    elapsedSeconds: Int,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onStop: () -> Unit,
    onViewSummary: (Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar
        TranscriptTopBar(
            isRecording = isRecording,
            elapsedSeconds = elapsedSeconds,
            onNavigateBack = onNavigateBack,
            modifier = Modifier.statusBarsPadding()
        )

        // Title / status row
        TranscriptStatusRow(isRecording = isRecording)

        Spacer(Modifier.height(4.dp))

        // Tab row
        TranscriptTabRow(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

        // Tab content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                0 -> ComingSoonTab(stringResource(R.string.transcript_tab_questions))
                1 -> NotesTab(isRecording)
                else -> TranscriptTab(uiState)
            }
        }

        // Bottom bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding()
            ) {
                if (isRecording) {
                    RecordingBottomBar(
                        amplitude = amplitude,
                        elapsedSeconds = elapsedSeconds,
                        onStop = onStop
                    )
                } else {
                    PostRecordingBottomBar(
                        sessionId = postState?.sessionId ?: -1L,
                        onViewSummary = onViewSummary,
                        onDone = onNavigateBack
                    )
                }
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun TranscriptTopBar(
    isRecording: Boolean,
    elapsedSeconds: Int,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        IconButton(onClick = onNavigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        // Timer with live recording dot
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(end = 16.dp)
        ) {
            if (isRecording) {
                RecordingDot()
            }
            Text(
                text = formatTime(elapsedSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = if (isRecording) MaterialTheme.colorScheme.onBackground
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecordingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "recDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(RecordingRed.copy(alpha = alpha), CircleShape)
    )
}

// ── Status row ────────────────────────────────────────────────────────────────

@Composable
private fun TranscriptStatusRow(isRecording: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (isRecording) stringResource(R.string.transcript_listening)
                   else stringResource(R.string.transcript_recording_stopped),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

// ── Tab row ───────────────────────────────────────────────────────────────────

private val TAB_LABEL_RES = listOf(
    R.string.transcript_tab_questions,
    R.string.transcript_tab_notes,
    R.string.transcript_tab_transcript
)

@Composable
private fun TranscriptTabRow(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        TAB_LABEL_RES.forEachIndexed { index, labelRes ->
            val label = stringResource(labelRes)
            val isSelected = selectedTab == index
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(2.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

// ── Tab content composables ───────────────────────────────────────────────────

@Composable
private fun ComingSoonTab(featureName: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.coming_soon_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.coming_soon_feature_body, featureName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun NotesTab(isRecording: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(60.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Text(
                text = if (isRecording)
                    stringResource(R.string.transcript_notes_recording)
                else
                    stringResource(R.string.transcript_notes_stopped),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TranscriptTab(uiState: TranscriptUiState) {
    when (uiState) {
        is TranscriptUiState.Recording -> {
            if (uiState.liveTranscript.isBlank()) {
                TranscriptWaitingState()
            } else {
                TranscriptLiveContent(uiState.liveTranscript)
            }
        }
        is TranscriptUiState.PostRecording -> TranscriptPostRecordingContent(uiState)
    }
}

@Composable
private fun TranscriptWaitingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Text(
                text = stringResource(R.string.transcript_live_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.transcript_live_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TranscriptLiveContent(transcript: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RecordingDot()
                Text(
                    text = stringResource(R.string.transcript_live_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = transcript,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun TranscriptPostRecordingContent(uiState: TranscriptUiState.PostRecording) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Status banner
        TranscriptionStatusBanner(isComplete = uiState.isTranscriptionComplete)
        Spacer(Modifier.height(12.dp))

        if (uiState.transcript.isBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (uiState.isTranscriptionComplete)
                        stringResource(R.string.transcript_no_speech_detected)
                    else
                        stringResource(R.string.transcript_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text(
                text = uiState.transcript,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun TranscriptionStatusBanner(isComplete: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isComplete) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!isComplete) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
            Text(
                text = if (isComplete) stringResource(R.string.transcript_transcription_complete)
                   else stringResource(R.string.transcript_transcribing),
                style = MaterialTheme.typography.labelMedium,
                color = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Bottom bars ───────────────────────────────────────────────────────────────

@Composable
private fun RecordingBottomBar(
    amplitude: Int,
    elapsedSeconds: Int,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Chat pill
        OutlinedButton(
            onClick = {},
            shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier.height(52.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.transcript_chat),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        // Waveform + timer + stop pill
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Waveform + timer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WaveformBars(amplitude = amplitude, barColor = MaterialTheme.colorScheme.primary)
                    Text(
                        text = formatTime(elapsedSeconds),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                // Vertical divider
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )

                // Stop
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { onStop() }
                ) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .background(RecordingRed, RoundedCornerShape(3.dp))
                    )
                    Text(
                        text = stringResource(R.string.transcript_stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }
    }
}

@Composable
private fun PostRecordingBottomBar(
    sessionId: Long,
    onViewSummary: (Long) -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onDone,
            shape = RoundedCornerShape(50),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.height(52.dp)
        ) {
            Text(
                text = stringResource(R.string.transcript_done),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Button(
            onClick = { if (sessionId > 0L) onViewSummary(sessionId) },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            enabled = sessionId > 0L,
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
        ) {
            Text(
                text = stringResource(R.string.transcript_view_summary),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

// ── Waveform canvas ───────────────────────────────────────────────────────────

@Composable
private fun WaveformBars(amplitude: Int, barColor: Color) {
    val norm = (amplitude / 32767f).coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(width = 24.dp, height = 18.dp)) {
        val bw = size.width / 9f
        val maxH = size.height
        val heights = listOf(
            maxH * (0.35f + norm * 0.15f),
            maxH * (0.65f + norm * 0.25f),
            maxH,
            maxH * (0.80f + norm * 0.20f),
            maxH * (0.45f + norm * 0.10f)
        )
        heights.forEachIndexed { i, h ->
            val x = bw * (i * 1.6f + 0.5f)
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, (maxH - h) / 2f),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw / 2f)
            )
        }
    }
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun formatTime(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
fun TranscriptRecordingPreview() {
    AudioMemoTheme {
        TranscriptContent(
            uiState = TranscriptUiState.Recording(),
            isRecording = true,
            postState = null,
            amplitude = 8000,
            elapsedSeconds = 154,
            selectedTab = 2,
            onTabSelected = {},
            onStop = {},
            onViewSummary = {},
            onNavigateBack = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun TranscriptPostRecordingPreview() {
    AudioMemoTheme {
        TranscriptContent(
            uiState = TranscriptUiState.PostRecording(
                sessionId = 1L,
                transcript = "Good morning everyone. Let's kick off our weekly standup. " +
                    "Can everyone share what they worked on yesterday and any blockers?\n\n" +
                    "Sure, I finished the API integration for the upload endpoint and " +
                    "today I'm moving on to the summary generation pipeline.",
                isTranscriptionComplete = true
            ),
            isRecording = false,
            postState = TranscriptUiState.PostRecording(
                sessionId = 1L,
                transcript = "",
                isTranscriptionComplete = true
            ),
            amplitude = 0,
            elapsedSeconds = 323,
            selectedTab = 2,
            onTabSelected = {},
            onStop = {},
            onViewSummary = {},
            onNavigateBack = {}
        )
    }
}

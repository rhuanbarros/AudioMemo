package com.example.audiomemo.features.logs.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audiomemo.R
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.logging.LogEvent
import com.example.audiomemo.ui.theme.AudioMemoTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsStateWithLifecycle()

    LogsContent(
        events = events,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsContent(
    events: List<LogEvent>,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }
                // events is already most-recent-first (AppEventLogger.log prepends), so the list
                // renders in chronological order top-to-bottom without any extra sorting here.
                // The key includes the list index: timestamp+category+message alone can collide
                // (two identical log() calls fired in the same millisecond are plausible), and a
                // duplicate LazyColumn key throws IllegalArgumentException, crashing this screen.
                itemsIndexed(
                    items = events,
                    key = { index, event -> "${event.timestamp}-${event.category}-${event.message}-$index" }
                ) { _, event ->
                    LogEventRow(event)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LogEventRow(event: LogEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        CategoryBadge(category = event.category)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = formatTimestamp(event.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CategoryBadge(category: LogCategory) {
    val label = when (category) {
        LogCategory.RECORDING -> stringResource(R.string.logs_category_recording)
        LogCategory.UPLOAD -> stringResource(R.string.logs_category_upload)
        LogCategory.INTERRUPTION -> stringResource(R.string.logs_category_interruption)
    }
    val color = when (category) {
        LogCategory.RECORDING -> MaterialTheme.colorScheme.primary
        LogCategory.UPLOAD -> MaterialTheme.colorScheme.tertiary
        LogCategory.INTERRUPTION -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .background(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// Includes the date (not just the time): a 24/7 recording session crosses midnight routinely,
// and the dono may review this list days later — HH:mm:ss alone would leave which day an event
// happened on ambiguous.
private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

// ── Previews ──────────────────────────────────────────────────────────────────

private val previewEvents = listOf(
    // am4-1: sizeKb/latencyMs reflected here so the preview stays representative of what
    // LogsScreen actually renders (code review, am4-1, patch 6).
    LogEvent(System.currentTimeMillis(), LogCategory.UPLOAD, "Supabase upload succeeded (chunk=42, latencyMs=843)"),
    LogEvent(System.currentTimeMillis() - 5_000, LogCategory.RECORDING, "Chunk finalized (id=42, sizeKb=118)"),
    LogEvent(System.currentTimeMillis() - 12_000, LogCategory.INTERRUPTION, "Recording resumed"),
    LogEvent(System.currentTimeMillis() - 60_000, LogCategory.INTERRUPTION, "Recording paused: PHONE_CALL"),
    LogEvent(System.currentTimeMillis() - 120_000, LogCategory.RECORDING, "Recording started")
)

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark theme")
@Composable
private fun LogsScreenDarkPreview() {
    AudioMemoTheme {
        LogsContent(events = previewEvents, onNavigateBack = {})
    }
}

@Preview(showBackground = true, name = "Light theme")
@Composable
private fun LogsScreenLightPreview() {
    AudioMemoTheme(darkTheme = false) {
        LogsContent(events = previewEvents, onNavigateBack = {})
    }
}

@Preview(showBackground = true, name = "Empty state")
@Composable
private fun LogsScreenEmptyPreview() {
    AudioMemoTheme(darkTheme = false) {
        LogsContent(events = emptyList(), onNavigateBack = {})
    }
}

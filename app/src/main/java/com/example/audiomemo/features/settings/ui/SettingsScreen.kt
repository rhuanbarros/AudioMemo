package com.example.audiomemo.features.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import android.content.res.Configuration
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.audiomemo.BuildConfig
import com.example.audiomemo.R
import com.example.audiomemo.ui.theme.AudioMemoTheme

/**
 * Reached only from Home's top-bar gear icon since am-hotfix-home-status-redesign (Settings left
 * the bottom nav — the bottom nav itself is gone, along with the Meetings tab it used to share
 * with Home). [onNavigateBack] mirrors the same back-arrow pattern already used by
 * [com.example.audiomemo.features.logs.ui.LogsScreen] / `CloudSyncSettingsScreen` for every other
 * non-tab screen in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToAppearances: () -> Unit = {},
    onNavigateToCloudSync: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {}
) {
    val context = LocalContext.current

    // am3-4 (FR4): battery-optimization exemption state, checked synchronously via
    // PowerManager. No dedicated ViewModel — the same stateless/direct pattern already
    // used elsewhere in this screen (e.g. the "Rate app" row).
    // Defensive: POWER_SERVICE is guaranteed present on real Android devices, but the cast
    // is kept safe (as?) so a non-standard ROM/test environment can't crash this screen —
    // a null PowerManager just means "can't determine state", so the row acts as if the
    // exemption is not granted (still safe: worst case the user is offered a redundant tap).
    val powerManager = remember(context) {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
    }

    // Re-evaluate on every resume: the user grants/denies the exemption in the system
    // Settings app (a separate Activity), so there is no in-process callback — only
    // returning to this screen (ON_RESUME) tells us the outcome. Same pattern already
    // used for RECORD_AUDIO in TranscriptScreen.kt.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                if (current != isIgnoringBatteryOptimizations) {
                    isIgnoringBatteryOptimizations = current
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineLarge,
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                SettingsSectionHeader(text = stringResource(R.string.settings_section_appearance))
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Default.ColorLens,
                        label = stringResource(R.string.settings_appearance),
                        onClick = onNavigateToAppearances
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                SettingsSectionHeader(text = stringResource(R.string.settings_section_general))
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        icon = Icons.Default.CloudSync,
                        label = stringResource(R.string.settings_cloud_sync),
                        onClick = onNavigateToCloudSync
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 52.dp)
                    )
                    SettingsRow(
                        icon = Icons.AutoMirrored.Filled.Article,
                        label = stringResource(R.string.settings_logs),
                        onClick = onNavigateToLogs
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 52.dp)
                    )
                    SettingsRow(
                        icon = Icons.Default.BatterySaver,
                        label = stringResource(R.string.settings_battery_optimization),
                        subtitle = if (isIgnoringBatteryOptimizations) {
                            stringResource(R.string.settings_battery_optimization_granted_body)
                        } else {
                            stringResource(R.string.settings_battery_optimization_explanation)
                        },
                        trailingText = if (isIgnoringBatteryOptimizations) {
                            stringResource(R.string.settings_battery_optimization_status_granted)
                        } else {
                            null
                        },
                        onClick = if (isIgnoringBatteryOptimizations) {
                            null
                        } else {
                            {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                // Catches both ActivityNotFoundException (no resolver for the
                                // intent) and SecurityException (several OEM ROMs — MIUI, EMUI,
                                // ColorOS, etc. — throw this instead when a restricted/customized
                                // settings screen is blocked). Either way, best effort: denying/
                                // being unable to grant the exemption must never block any other
                                // flow in the app.
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        )
                                    } catch (_: ActivityNotFoundException) {
                                        // No battery-optimization settings screen available on
                                        // this device/ROM.
                                    } catch (_: SecurityException) {
                                        // OEM ROM blocked the fallback settings screen.
                                    }
                                } catch (_: SecurityException) {
                                    // OEM ROM blocked the primary settings screen.
                                }
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 52.dp)
                    )
                    SettingsRow(
                        icon = Icons.Default.Star,
                        label = stringResource(R.string.settings_rate_app),
                        onClick = {
                            val packageName = context.packageName
                            try {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=$packageName")
                                    )
                                )
                            } catch (_: ActivityNotFoundException) {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                                    )
                                )
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 52.dp)
                    )
                    SettingsRow(
                        icon = Icons.Default.Lock,
                        label = stringResource(R.string.settings_privacy_policy),
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://audiomemo.app/privacy")
                                )
                            )
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = 52.dp)
                    )
                    SettingsRow(
                        icon = Icons.Default.Info,
                        label = stringResource(R.string.settings_version),
                        trailingText = BuildConfig.VERSION_NAME,
                        onClick = null
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    trailingText: String? = null,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Dark theme")
@Composable
private fun SettingsScreenDarkPreview() {
    AudioMemoTheme {
        SettingsScreen()
    }
}

@Preview(showBackground = true, name = "Light theme")
@Composable
private fun SettingsScreenLightPreview() {
    AudioMemoTheme(darkTheme = false) {
        SettingsScreen()
    }
}

package com.example.audiomemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.audiomemo.core.preferences.AccentColor
import com.example.audiomemo.core.preferences.ThemeMode
import com.example.audiomemo.features.cloudsync.ui.CloudSyncSettingsScreen
import com.example.audiomemo.features.home.ui.HomeScreen
import com.example.audiomemo.features.meetings.ui.MeetingDetailsScreen
import com.example.audiomemo.features.meetings.ui.MeetingsDashboardScreen
import com.example.audiomemo.features.settings.ui.AppearanceScreen
import com.example.audiomemo.features.settings.ui.AppearanceViewModel
import com.example.audiomemo.features.settings.ui.SettingsScreen
import com.example.audiomemo.features.summary.ui.SummaryScreen
import com.example.audiomemo.features.transcript.ui.TranscriptScreen
import com.example.audiomemo.ui.theme.AudioMemoTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioMemoApp()
        }
    }
}

@Composable
private fun AudioMemoApp(
    appearanceViewModel: AppearanceViewModel = hiltViewModel()
) {
    val themeMode by appearanceViewModel.themeMode.collectAsStateWithLifecycle()
    val appFont by appearanceViewModel.appFont.collectAsStateWithLifecycle()
    val accentColor by appearanceViewModel.accentColor.collectAsStateWithLifecycle()

    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    AudioMemoTheme(
        darkTheme = isDark,
        fontFamily = appFont.toFontFamily(),
        accentColor = accentColor
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "home"
            ) {
                composable("home") {
                    HomeScreen(
                        onNavigateToTranscript = {
                            navController.navigate("transcript")
                        },
                        onNavigateToMeetings = {
                            navController.navigate("meetings") {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToMeetingDetails = { sessionId ->
                            navController.navigate("meeting-details/$sessionId")
                        },
                        onNavigateToSettings = {
                            navController.navigate("settings") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onNavigateToHome = {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToMeetings = {
                            navController.navigate("meetings") {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToAppearances = {
                            navController.navigate("appearances")
                        },
                        onNavigateToCloudSync = {
                            navController.navigate("cloud_sync_settings")
                        }
                    )
                }
                composable("cloud_sync_settings") {
                    CloudSyncSettingsScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("meetings") {
                    MeetingsDashboardScreen(
                        onNavigateToHome = {
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate("settings") {
                                launchSingleTop = true
                            }
                        },
                        onMeetingClick = { sessionId ->
                            navController.navigate("meeting-details/$sessionId")
                        }
                    )
                }
                composable("appearances") {
                    AppearanceScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("transcript") {
                    TranscriptScreen(
                        onNavigateToSummary = { sessionId ->
                            navController.navigate("summary/$sessionId")
                        },
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
                composable(
                    route = "summary/{sessionId}",
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.LongType }
                    )
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
                    SummaryScreen(
                        sessionId = sessionId,
                        onNavigateBack = {
                            navController.popBackStack("home", inclusive = false)
                        },
                        onViewTranscript = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(
                    route = "meeting-details/{sessionId}",
                    arguments = listOf(
                        navArgument("sessionId") { type = NavType.LongType }
                    )
                ) { backStackEntry ->
                    val sessionId = backStackEntry.arguments?.getLong("sessionId") ?: -1L
                    MeetingDetailsScreen(
                        sessionId = sessionId,
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}

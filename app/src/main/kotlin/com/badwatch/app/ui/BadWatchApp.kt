package com.badwatch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.badwatch.app.R
import com.badwatch.app.data.StoredSession
import com.badwatch.app.domain.SessionState
import com.badwatch.app.ui.theme.AccentCritical
import com.badwatch.app.ui.theme.AccentPositive
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.core.model.Handedness
import com.badwatch.app.viewmodel.BadWatchViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Root of the watch UI.
 *
 * Screen selection is driven primarily by [SessionState] rather than by a navigation stack:
 * during play there is exactly one screen that matters, and the app should land on it
 * whenever the player raises their wrist, regardless of where they were before.
 */
@Composable
fun BadWatchApp(
    viewModel: BadWatchViewModel,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Home) }

    BadWatchTheme {
        when {
            onboarded == null -> LoadingScreen()

            onboarded == false -> OnboardingScreen(
                onConfirm = { handedness -> viewModel.completeOnboarding(handedness) }
            )

            sessionState is SessionState.Recording -> LiveScreen(
                state = sessionState as SessionState.Recording,
                onStop = onStopSession,
                onDiscard = viewModel::discardSession
            )

            sessionState is SessionState.Completed -> SummaryScreen(
                stored = (sessionState as SessionState.Completed).export,
                onDone = {
                    viewModel.acknowledge()
                    screen = Screen.Home
                }
            )

            sessionState is SessionState.Failed -> ErrorScreen(
                message = (sessionState as SessionState.Failed).message,
                onDismiss = viewModel::acknowledge
            )

            screen == Screen.History -> HistoryScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Home }
            )

            screen == Screen.Settings -> SettingsScreen(
                viewModel = viewModel,
                onBack = { screen = Screen.Home }
            )

            else -> HomeScreen(
                viewModel = viewModel,
                onStart = onStartSession,
                onOpenHistory = { screen = Screen.History },
                onOpenSettings = { screen = Screen.Settings }
            )
        }
    }
}

private enum class Screen { Home, History, Settings }

@Composable
private fun LoadingScreen() {
    WatchScaffold {
        item {
            Text(
                text = "Bad Watch",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HomeScreen(
    viewModel: BadWatchViewModel,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val last = history.firstOrNull()

    WatchScaffold {
        item {
            Text(
                text = "Bad Watch",
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
        }
        item {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start session")
            }
        }
        if (last != null) {
            item {
                SectionCard(title = "Last session") {
                    Text(
                        text = formatSessionDate(last.export.session.startedAtMillis),
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    MetricRow(
                        first = "Shots" to last.export.session.summary.totalShots.toString(),
                        second = "Rallies" to last.export.rallyProfile.rallyCount.toString(),
                        third = "Time" to formatDuration(last.export.session.summary.durationMillis)
                    )
                }
            }
        } else {
            item {
                SectionCard {
                    Text(
                        text = "No sessions yet. Start one and play — the watch does the rest.",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item {
            CompactChip(
                onClick = onOpenHistory,
                label = { Text("History (${history.size})") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            CompactChip(
                onClick = onOpenSettings,
                label = { Text("Settings") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OnboardingScreen(onConfirm: (Handedness) -> Unit) {
    var leftHanded by remember { mutableStateOf(false) }

    WatchScaffold {
        item {
            Text(
                text = stringResource(R.string.onboarding_wrist_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
        }
        item {
            // Stated plainly and up front: this is a hard requirement, not a preference.
            // Bad Watch reads the swing itself, so on the other wrist there is nothing to read.
            Text(
                text = stringResource(R.string.onboarding_wrist_body),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                textAlign = TextAlign.Center
            )
        }
        item {
            ToggleChip(
                checked = leftHanded,
                onCheckedChange = { leftHanded = it },
                label = { Text(if (leftHanded) "Left handed" else "Right handed") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(leftHanded),
                        contentDescription = null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(
                onClick = { onConfirm(if (leftHanded) Handedness.Left else Handedness.Right) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Got it")
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: BadWatchViewModel, onBack: () -> Unit) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    WatchScaffold {
        item {
            Text(
                text = "History",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
        }
        if (history.isEmpty()) {
            item {
                Text(
                    text = "Nothing recorded yet.",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center
                )
            }
        }
        items(history) { stored -> HistoryRow(stored) }
        item {
            CompactChip(
                onClick = onBack,
                label = { Text("Back") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HistoryRow(stored: StoredSession) {
    val summary = stored.export.session.summary
    SectionCard {
        Text(
            text = formatSessionDate(stored.export.session.startedAtMillis),
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
        MetricRow(
            first = "Shots" to summary.totalShots.toString(),
            second = "Rallies" to stored.export.rallyProfile.rallyCount.toString(),
            third = "Time" to formatDuration(summary.durationMillis)
        )
        Text(
            text = if (stored.synced) "Synced to dashboard" else "On watch only",
            style = MaterialTheme.typography.caption2,
            color = if (stored.synced) AccentPositive else
                MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SettingsScreen(viewModel: BadWatchViewModel, onBack: () -> Unit) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val dashboardUrl by viewModel.dashboardUrl.collectAsStateWithLifecycle()

    WatchScaffold {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
        }
        item {
            ToggleChip(
                checked = profile.handedness == Handedness.Left,
                onCheckedChange = { left ->
                    viewModel.setHandedness(if (left) Handedness.Left else Handedness.Right)
                },
                label = { Text(if (profile.handedness == Handedness.Left) "Left handed" else "Right handed") },
                toggleControl = {
                    Icon(
                        imageVector = ToggleChipDefaults.switchIcon(profile.handedness == Handedness.Left),
                        contentDescription = null
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SectionCard(title = "Dashboard") {
                Text(
                    text = dashboardUrl ?: "Not configured",
                    style = MaterialTheme.typography.caption1
                )
                Text(
                    text = "Set the server URL from the paired phone or with adb. Sessions " +
                        "stay on the watch until it is reachable.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        item {
            SectionCard(title = "Wrist") {
                Text(
                    text = "Racket hand only. Bad Watch reads the swing, so the other wrist " +
                        "cannot produce shot data.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        item {
            CompactChip(
                onClick = onBack,
                label = { Text("Back") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onDismiss: () -> Unit) {
    WatchScaffold {
        item {
            Text(
                text = "Session stopped",
                style = MaterialTheme.typography.title3,
                color = AccentCritical,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                text = message,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )
        }
        item {
            Chip(
                onClick = onDismiss,
                colors = ChipDefaults.primaryChipColors(),
                label = { Text("Dismiss") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Shared round-display scaffold: time at top, position indicator, scaling list. */
@Composable
fun WatchScaffold(content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}

fun formatSessionDate(epochMillis: Long): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(epochMillis))

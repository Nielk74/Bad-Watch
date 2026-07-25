package com.badwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.data.StoredSession
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.Sparkline
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatSessionDate
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.viewmodel.BadWatchViewModel
import java.util.concurrent.TimeUnit

/**
 * The ready screen.
 *
 * One job: get the player on court with a single tap. The primary action is an ordinary,
 * full-width button near the top because Wear edge buttons do not appear until the user has
 * scrolled to the end of a list. Training history and maintenance tools follow below it.
 */
@Composable
fun HomeScreen(
    viewModel: BadWatchViewModel,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenSession: (StoredSession) -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    HomeContent(
        history = history,
        onStart = onStart,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onOpenCapture = onOpenCapture,
        onOpenSession = onOpenSession
    )
}

@Composable
private fun HomeContent(
    history: List<StoredSession>,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenSession: (StoredSession) -> Unit
) {
    val last = history.firstOrNull()

    WatchScreen(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { BrandHeader() }

        item {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 72.dp),
                label = {
                    Text(
                        text = "Start session",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                secondaryLabel = {
                    Text(
                        text = "Detected hits · heart rate",
                        style = MaterialTheme.typography.bodyExtraSmall
                    )
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.LargeIconSize)
                        )
                    }
                }
            )
        }

        item { ThisWeekCard(history) }

        if (last != null) {
            item {
                TitleCard(
                    onClick = { onOpenSession(last) },
                    title = { Text("Last session") },
                    time = { Text(formatSessionDate(last.export.session.startedAtMillis)) }
                ) {
                    StatRow(
                        Stat("Detected hits", last.export.session.summary.totalShots.toString()),
                        Stat("Bursts", last.export.rallyProfile.rallyCount.toString()),
                        Stat("Time", formatDuration(last.export.session.summary.durationMillis))
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    space = 28.dp,
                    alignment = Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickAction(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "History") },
                    label = "History",
                    onClick = onOpenHistory
                )
                QuickAction(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = "Settings",
                    onClick = onOpenSettings
                )
            }
        }

        // This trains the provisional classifier, so it belongs in a deliberately secondary
        // lab card rather than beside the everyday History and Settings destinations.
        item {
            TitleCard(
                onClick = onOpenCapture,
                title = { Text("Detection lab") },
                time = { Text("Optional") }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Label practice swings to improve detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun BrandHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = "BAD WATCH",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = "Ready on your racket wrist",
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Rolling seven-day activity, computed from sessions actually on the watch. Detected hits
 * and explicitly labelled estimated active time avoid implying whole-rally or court coverage.
 */
@Composable
private fun ThisWeekCard(history: List<StoredSession>) {
    val weekStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    val week = history.filter { it.export.session.startedAtMillis >= weekStart }

    InfoCard(title = "This week") {
        if (week.isEmpty()) {
            Text(
                text = "Your first recap will appear here after you save a session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val totalShots = week.sumOf { it.export.session.summary.totalShots }
            val estimatedActiveMillis = week.sumOf { it.export.rallyProfile.totalWorkMillis }
            StatRow(
                Stat("Sessions", week.size.toString()),
                Stat("Detected hits", totalShots.toString()),
                Stat("Est active", formatDuration(estimatedActiveMillis))
            )
            val recentShots = history.take(8).reversed()
                .map { it.export.session.summary.totalShots.toFloat() }
            if (recentShots.size >= 2) {
                Spacer(modifier = Modifier.height(2.dp))
                Sparkline(
                    values = recentShots,
                    color = MaterialTheme.colorScheme.primary
                )
                DetailRow(label = "Detected hits · last ${recentShots.size}", value = "")
            }
        }
    }
}

@Preview(widthDp = 240, heightDp = 240, showBackground = true)
@Composable
private fun EmptyHomePreview() {
    BadWatchTheme {
        HomeContent(
            history = emptyList(),
            onStart = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onOpenCapture = {},
            onOpenSession = {}
        )
    }
}

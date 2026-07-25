package com.badwatch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.EdgeButtonSize
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
import com.badwatch.app.viewmodel.BadWatchViewModel
import java.util.concurrent.TimeUnit

/**
 * The ready screen.
 *
 * One job: get the player on court with a single tap. Everything else — this week's load,
 * the last session, secondary navigation — sits below the fold. The primary action lives in
 * the edge button, where Wear users expect the main action to be.
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
    val last = history.firstOrNull()

    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = onStart, buttonSize = EdgeButtonSize.Medium) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("Start session")
            }
        }
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "BAD WATCH",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Badminton, from your racket wrist",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
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
                        Stat("Shots", last.export.session.summary.totalShots.toString()),
                        Stat("Rallies", last.export.rallyProfile.rallyCount.toString()),
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
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickAction(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = "History") },
                    label = "History",
                    onClick = onOpenHistory
                )
                QuickAction(
                    icon = { Icon(Icons.Default.Add, contentDescription = "Collect data") },
                    label = "Collect",
                    onClick = onOpenCapture
                )
                QuickAction(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = "Settings",
                    onClick = onOpenSettings
                )
            }
        }

        item { Spacer(modifier = Modifier.height(48.dp)) }
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
 * Rolling seven-day load, computed from the sessions actually on the watch. For an interval
 * sport, shots and active minutes say far more than "sessions: 3".
 */
@Composable
private fun ThisWeekCard(history: List<StoredSession>) {
    val weekStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    val week = history.filter { it.export.session.startedAtMillis >= weekStart }

    InfoCard(title = "This week") {
        if (week.isEmpty()) {
            Text(
                text = "No sessions yet. Tap Start and play — the watch does the rest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val totalShots = week.sumOf { it.export.session.summary.totalShots }
            val totalMillis = week.sumOf { it.export.session.summary.durationMillis }
            StatRow(
                Stat("Sessions", week.size.toString()),
                Stat("Shots", totalShots.toString()),
                Stat("Active", formatDuration(totalMillis))
            )
            val recentShots = history.take(8).reversed()
                .map { it.export.session.summary.totalShots.toFloat() }
            if (recentShots.size >= 2) {
                Spacer(modifier = Modifier.height(2.dp))
                Sparkline(
                    values = recentShots,
                    color = MaterialTheme.colorScheme.primary
                )
                DetailRow(label = "Shots per session, last ${recentShots.size}", value = "")
            }
        }
    }
}

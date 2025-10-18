package com.badwatch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.viewmodel.TrainingSessionUiState
import com.badwatch.app.viewmodel.TrainingSessionViewModel
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSessionSnapshot
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun TrainingRoute(
    viewModel: TrainingSessionViewModel,
    onStartSession: () -> Unit,
    onStopSession: (Boolean) -> Unit,
    onAbortSession: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    BadWatchTheme {
        TrainingScreen(
            state = uiState,
            history = history,
            onStartSession = onStartSession,
            onStopSession = onStopSession,
            onAbortSession = onAbortSession,
            onClearHistory = onClearHistory
        )
    }
}

@Composable
fun TrainingScreen(
    state: TrainingSessionUiState,
    history: List<TrainingSession>,
    onStartSession: () -> Unit,
    onStopSession: (Boolean) -> Unit,
    onAbortSession: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Scaffold(
        timeText = { TimeText() }
    ) {
        when (state) {
            is TrainingSessionUiState.Idle -> IdleState(
                recentSession = state.recentSession,
                history = history,
                onStartSession = onStartSession,
                onClearHistory = onClearHistory
            )

            is TrainingSessionUiState.Running -> RunningState(
                snapshot = state.snapshot,
                onStopSession = onStopSession,
                onAbortSession = onAbortSession
            )

            is TrainingSessionUiState.Finished -> FinishedState(
                session = state.session,
                onStartAgain = onStartSession
            )

            is TrainingSessionUiState.Error -> ErrorState(
                message = state.message,
                onRetry = onStartSession,
                onAbort = onAbortSession
            )
        }
    }
}

@Composable
private fun IdleState(
    recentSession: TrainingSession?,
    history: List<TrainingSession>,
    onStartSession: () -> Unit,
    onClearHistory: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Ready to Train",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Track smashes, effort, and fatigue in real time.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStartSession) {
            Text("Start Session")
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (recentSession != null) {
            SessionSummaryCard(
                session = recentSession,
                title = "Last Session"
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        HistorySection(history = history, onClearHistory = onClearHistory)
    }
}

@Composable
private fun RunningState(
    snapshot: TrainingSessionSnapshot,
    onStopSession: (Boolean) -> Unit,
    onAbortSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Training Active",
            style = MaterialTheme.typography.displayMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        LiveMetrics(snapshot = snapshot)
        Spacer(modifier = Modifier.height(12.dp))
        Chip(
            onClick = { onStopSession(true) },
            label = { Text("Stop & Save") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Chip(
            onClick = { onStopSession(false) },
            label = { Text("Stop & Discard") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Chip(
            onClick = onAbortSession,
            label = { Text("Abort") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FinishedState(
    session: TrainingSession,
    onStartAgain: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Session Complete",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        SessionSummaryCard(
            session = session,
            title = "Summary"
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onStartAgain) {
            Text("Start New Session")
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onAbort: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Sensor Error",
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Chip(
            onClick = onRetry,
            label = { Text("Retry") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        Chip(
            onClick = onAbort,
            label = { Text("Abort") },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LiveMetrics(snapshot: TrainingSessionSnapshot) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(snapshot.durationMillis)
            Text(
                text = "Duration ${durationSeconds}s",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = "Heart",
                    value = formatBpm(snapshot.currentHeartRate)
                )
                MetricColumn(
                    label = "Avg",
                    value = formatBpm(snapshot.averageHeartRate)
                )
                MetricColumn(
                    label = "Max",
                    value = formatBpm(snapshot.maxHeartRate)
                )
            }
            ShotStreakRow(snapshot.lastShot, snapshot.totalShots)
            InsightRow(
                fatigue = snapshot.fatigueScore,
                effort = snapshot.effortScore,
                recovery = snapshot.recoveryScore,
                zone = snapshot.dominantZone
            )
        }
    }
}

@Composable
private fun ShotStreakRow(lastShot: ShotEvent?, totalShots: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Shots $totalShots",
            style = MaterialTheme.typography.bodyMedium
        )
        if (lastShot != null) {
            Text(
                text = "Last ${formatShotType(lastShot.type)}",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                text = "No shots yet",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun InsightRow(
    fatigue: Float,
    effort: Float,
    recovery: Float,
    zone: HeartRateZone
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularScore(label = "Fatigue", value = fatigue)
        CircularScore(label = "Effort", value = effort)
        CircularScore(label = "Recovery", value = recovery)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Zone", style = MaterialTheme.typography.labelMedium)
            Text(text = zone.displayName(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CircularScore(label: String, value: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            progress = value.coerceIn(0f, 1f),
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun SessionSummaryCard(
    session: TrainingSession,
    title: String
) {
    Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Duration ${TimeUnit.MILLISECONDS.toMinutes(session.summary.durationMillis)} min",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Shots ${session.summary.totalShots}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Avg HR ${formatBpm(session.summary.averageHeartRate)}",
                style = MaterialTheme.typography.bodyMedium
            )
            ShotDistributionRow(session.summary.shotCounts)
            Text(
                text = "Dominant Zone ${session.summary.heartRateZoneHistogram.maxByOrNull { it.value }?.key?.displayName() ?: "Warm-up"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Fatigue ${(session.summary.fatigueScore * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Text("Effort ${(session.summary.effortScore * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Text("Recovery ${(session.summary.recoveryScore * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ShotDistributionRow(counts: Map<ShotType, Int>) {
    Column {
        Text("Shot Mix", style = MaterialTheme.typography.bodyMedium)
        var total = 0
        ShotType.values().forEach { type ->
            val count = counts[type] ?: 0
            if (count > 0) {
                total += count
                Text(
                    text = "${formatShotType(type)} $count",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (total == 0) {
            Text(
                text = "No shots captured",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun HistorySection(
    history: List<TrainingSession>,
    onClearHistory: () -> Unit
) {
    if (history.isEmpty()) {
        Text(
            text = "No sessions yet. Smash start to begin!",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        return
    }

    Text(
        text = "History",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Start
    )
    Spacer(modifier = Modifier.height(4.dp))
    history.take(3).forEach { session ->
        Card(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Shots ${session.summary.totalShots}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Avg HR ${formatBpm(session.summary.averageHeartRate)}",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
    Chip(
        onClick = onClearHistory,
        label = { Text("Clear history") },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBpm(value: Float): String =
    if (value.isNaN() || value <= 0f) "--" else "${value.roundToInt()} bpm"

private fun formatShotType(type: ShotType): String =
    when (type) {
        ShotType.Smash -> "Smash"
        ShotType.Clear -> "Clear"
        ShotType.Drop -> "Drop"
        ShotType.Drive -> "Drive"
        ShotType.BackhandDrive -> "Backhand"
        ShotType.Unknown -> "Unknown"
    }

private fun HeartRateZone.displayName(): String =
    when (this) {
        HeartRateZone.WarmUp -> "Warm-up"
        HeartRateZone.Endurance -> "Endurance"
        HeartRateZone.Tempo -> "Tempo"
        HeartRateZone.Threshold -> "Threshold"
        HeartRateZone.VO2Max -> "VO₂ Max"
    }

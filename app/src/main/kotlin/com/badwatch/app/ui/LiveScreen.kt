package com.badwatch.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.badwatch.app.domain.SessionState
import com.badwatch.app.ui.theme.AccentCritical
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.SessionExport
import java.util.Locale

/**
 * The in-play screen.
 *
 * Designed for roughly half a second of attention: one very large number (shots), then
 * supporting metrics, then actions. Anything requiring reading a sentence belongs in the
 * post-session recap, not here.
 */
@Composable
fun LiveScreen(
    state: SessionState.Recording,
    onStop: () -> Unit,
    onDiscard: () -> Unit
) {
    val snapshot = state.snapshot
    val rallies = state.rallyProfile

    WatchScaffold {
        item {
            Text(
                text = snapshot.totalShots.toString(),
                style = MaterialTheme.typography.display1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                text = "SHOTS · ${formatDuration(snapshot.durationMillis)}",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
        item {
            MetricRow(
                first = "HR" to formatHeartRate(snapshot.currentHeartRate),
                second = "Rallies" to rallies.rallyCount.toString(),
                third = "Work" to formatRestRatio(rallies.restRatio)
            )
        }
        item {
            SectionCard(title = "Last shot") {
                val last = snapshot.lastShot
                Text(
                    text = last?.type?.displayName() ?: "Waiting for first swing",
                    style = MaterialTheme.typography.title3
                )
                if (last != null) {
                    DetailRow("Confidence", "${(last.confidence * 100).toInt()}%")
                    DetailRow("Peak", String.format(Locale.US, "%.1f rad/s", last.peakAngularVelocity))
                }
            }
        }
        item {
            SectionCard(title = "Rally") {
                DetailRow("Avg shots", String.format(Locale.US, "%.1f", rallies.averageShotsPerRally))
                DetailRow("Longest", "${rallies.longestRally?.shotCount ?: 0} shots")
                DetailRow("Playing", "${(rallies.workDensity * 100).toInt()}% of session")
            }
        }
        item {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop & save")
            }
        }
        item {
            CompactChip(
                onClick = onDiscard,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Discard", color = AccentCritical) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Post-session recap.
 *
 * The recap deliberately leads with rally structure rather than shot count, because for an
 * interval sport that is the number that actually characterises the session.
 */
@Composable
fun SummaryScreen(stored: SessionExport, onDone: () -> Unit) {
    val summary = stored.session.summary
    val rallies = stored.rallyProfile

    WatchScaffold {
        item {
            Text(
                text = "Session saved",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
        }
        item {
            MetricRow(
                first = "Shots" to summary.totalShots.toString(),
                second = "Rallies" to rallies.rallyCount.toString(),
                third = "Time" to formatDuration(summary.durationMillis)
            )
        }
        item {
            SectionCard(title = "Rally structure") {
                DetailRow("Avg rally", "${rallies.averageShotsPerRally.toInt()} shots")
                DetailRow("Longest", "${rallies.longestRally?.shotCount ?: 0} shots")
                DetailRow("Work : rest", formatRestRatio(rallies.restRatio))
                DetailRow("Playing time", "${(rallies.workDensity * 100).toInt()}%")
            }
        }
        item {
            SectionCard(title = "Heart rate") {
                DetailRow("Average", formatHeartRate(summary.averageHeartRate))
                DetailRow("Peak", formatHeartRate(summary.maxHeartRate))
            }
        }
        if (summary.shotCounts.isNotEmpty()) {
            item {
                SectionCard(title = "Shots") {
                    summary.shotCounts.entries
                        .sortedByDescending { it.value }
                        .forEach { (type, count) ->
                            DetailRow(type.displayName(), count.toString())
                        }
                }
            }
        }
        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

fun ShotType.displayName(): String = when (this) {
    ShotType.Smash -> "Smash"
    ShotType.Clear -> "Clear"
    ShotType.Drop -> "Drop"
    ShotType.Drive -> "Drive"
    ShotType.BackhandDrive -> "Backhand drive"
    ShotType.Unknown -> "Unclassified"
}

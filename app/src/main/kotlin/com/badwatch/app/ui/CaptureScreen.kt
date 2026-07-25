package com.badwatch.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.ui.theme.AccentCritical
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.CaptureExport

/** Stroke types offered for labelled drills. `Unknown` is not a thing anyone can hit. */
private val DRILL_STROKES = listOf(
    ShotType.Smash,
    ShotType.Clear,
    ShotType.Drop,
    ShotType.Drive,
    ShotType.BackhandDrive
)

/**
 * Stroke picker for a labelled data-collection drill.
 */
@Composable
fun CapturePickerScreen(
    totalSwings: Int,
    onPick: (ShotType) -> Unit,
    onBack: () -> Unit
) {
    WatchScaffold {
        item {
            Text(
                text = "Collect training data",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                text = if (totalSwings == 0) {
                    "Pick a stroke, then hit twenty of them. Each swing is saved with your label."
                } else {
                    "$totalSwings labelled swings on this watch."
                },
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        items(DRILL_STROKES) { stroke ->
            CompactChip(
                onClick = { onPick(stroke) },
                label = { Text(stroke.displayName()) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            CompactChip(
                onClick = onBack,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Back") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * The live drill screen.
 *
 * Shows only the rep count, because during a drill that is the single thing worth reading.
 * "Drop last" exists because a mishit or a stumble produces a window that would otherwise
 * teach the model the wrong thing — mislabelled data is worse than no data.
 */
@Composable
fun CaptureScreen(
    state: CaptureState.Capturing,
    onDiscardLast: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    WatchScaffold {
        item {
            Text(
                text = state.keptCount.toString(),
                style = MaterialTheme.typography.display1,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                text = state.label.displayName().uppercase(),
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        item {
            SectionCard(title = "Last swing") {
                val last = state.swings.lastOrNull { !it.discarded }
                if (last == null) {
                    Text(
                        text = "Waiting for your first swing",
                        style = MaterialTheme.typography.caption1
                    )
                } else {
                    DetailRow("Peak", String.format(Locale.US, "%.1f rad/s", last.peakAngularVelocity))
                    DetailRow("Samples", last.samples.size.toString())
                }
            }
        }
        item {
            CompactChip(
                onClick = onDiscardLast,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Drop last") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save drill")
            }
        }
        item {
            CompactChip(
                onClick = onCancel,
                colors = ChipDefaults.secondaryChipColors(),
                label = { Text("Cancel", color = AccentCritical) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CaptureSavedScreen(export: CaptureExport, onDone: () -> Unit) {
    WatchScaffold {
        item {
            Text(
                text = "Drill saved",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center
            )
        }
        item {
            MetricRow(
                first = "Swings" to export.capture.swingCount.toString(),
                second = "Stroke" to export.capture.label.displayName(),
                third = "Rate" to "${export.samplingRateHz} Hz"
            )
        }
        item {
            Text(
                text = "Uploads to your dashboard with the next sync.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
        item {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
    }
}

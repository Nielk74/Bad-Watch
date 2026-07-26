package com.badwatch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.R
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.color
import com.badwatch.app.ui.components.displayName
import com.badwatch.app.ui.theme.CourtColors
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureDataUse
import java.util.Locale

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
 *
 * Drills build the training set the classifier does not have yet: one stroke, many
 * repetitions, every window stored under the player's own label. The only decision on
 * this screen is which stroke to hit, so the strokes get the screen — each in the shot
 * color the rest of the app already uses for it.
 */
@Composable
fun CapturePickerScreen(
    totalSwings: Int,
    onPick: (ShotType) -> Unit,
    onBack: () -> Unit
) {
    WatchScreen {
        item { ScreenHeader(stringResource(R.string.capture_title)) }

        item {
            InfoCard {
                Text(
                    text = if (totalSwings == 0) {
                        stringResource(R.string.capture_intro_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.capture_intro_existing,
                            totalSwings,
                            totalSwings
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(DRILL_STROKES) { stroke ->
            TitleCard(
                onClick = { onPick(stroke) },
                title = {
                    Text(
                        text = stroke.displayName(),
                        color = stroke.color()
                    )
                }
            ) {
                Text(
                    text = stringResource(R.string.capture_reps_recommended),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CompactButton(
                onClick = onBack,
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.action_back)) }
            )
        }
    }
}

/**
 * The live drill.
 *
 * During a drill the only thing worth reading is the rep count, so the count gets the
 * whole screen; the label sits above it as a color cue, and the last kept swing's peak
 * is a one-line confirmation that the sensor saw something real.
 *
 * "Drop last" exists because a mishit or a stumble produces a window that would otherwise
 * teach the model the wrong thing — mislabelled data is worse than no data. "Cancel"
 * throws the whole drill away, so once at least one swing is kept it asks first.
 */
@Composable
fun CaptureScreen(
    state: CaptureState.Capturing,
    onDiscardLast: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    var confirmCancel by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = state.label.displayName().uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = state.label.color()
            )
            Text(
                text = state.keptCount.toString(),
                style = MaterialTheme.typography.numeralExtraLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(
                    if (state.keptCount == 1) {
                        R.string.capture_swing_unit_one
                    } else {
                        R.string.capture_swing_unit_other
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val lastKept = state.swings.lastOrNull { !it.discarded }
            Text(
                text = if (lastKept == null) {
                    stringResource(R.string.capture_waiting_swing)
                } else {
                    stringResource(R.string.capture_last_peak, lastKept.peakAngularVelocity)
                },
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactButton(
                    onClick = onDiscardLast,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(stringResource(R.string.capture_drop_last)) }
                )
                CompactButton(
                    onClick = {
                        if (state.keptCount == 0) onCancel() else confirmCancel = true
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    label = { Text(stringResource(R.string.action_cancel)) }
                )
            }
        }

        EdgeButton(
            onClick = onFinish,
            modifier = Modifier.align(Alignment.BottomCenter),
            buttonSize = EdgeButtonSize.Small
        ) {
            Text(stringResource(R.string.capture_save_drill))
        }
    }

    AlertDialog(
        visible = confirmCancel,
        onDismissRequest = { confirmCancel = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmCancel = false
                    onCancel()
                }
            )
        },
        title = { Text(stringResource(R.string.capture_cancel_question)) },
        text = { Text(stringResource(R.string.capture_cancel_body)) }
    )
}

/**
 * The saved-drill receipt.
 *
 * A drill ends in one glance — how many swings made it, under which label, at what rate —
 * and then the player is done. The export itself uploads with the next sync; the watch
 * only needs to say that much.
 */
@Composable
fun CaptureSavedScreen(export: CaptureExport, onDone: () -> Unit) {
    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = onDone) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_done))
            }
        }
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CourtColors.Success,
                    modifier = Modifier.size(36.dp)
                )
                Text(
                    text = stringResource(R.string.capture_saved),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            StatRow(
                Stat(stringResource(R.string.label_swings), export.capture.swingCount.toString()),
                Stat(stringResource(R.string.label_stroke), export.capture.label.displayName()),
                Stat(
                    stringResource(R.string.capture_rate),
                    stringResource(R.string.format_hertz, export.samplingRateHz)
                )
            )
        }

        item {
            InfoCard {
                Text(
                    text = if (export.dataUse == CaptureDataUse.SelfHostedModelTraining) {
                        stringResource(R.string.capture_sync_eligible)
                    } else {
                        stringResource(R.string.capture_saved_local)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

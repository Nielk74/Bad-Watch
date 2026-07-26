package com.badwatch.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.R
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.provisionalDisplayName
import com.badwatch.core.sync.SessionExport

/** Player review of detector events and recording edges; raw output is never overwritten. */
@Composable
fun SessionCorrectionScreen(
    export: SessionExport,
    onSave: (
        falseHitIds: Set<String>,
        missedHitCount: Int,
        trimFromStartMillis: Long,
        trimFromEndMillis: Long
    ) -> Unit,
    onBack: () -> Unit,
    isSaving: Boolean = false,
    saveError: String? = null
) {
    val currentHits = export.corrections.currentHitRevision
    val currentTrim = export.corrections.currentTrimRevision
    var falseHitIds by remember {
        mutableStateOf(currentHits?.falseHitIds.orEmpty().toSet())
    }
    var missedHitCount by remember { mutableIntStateOf(currentHits?.missedHitCount ?: 0) }
    var trimFromStartMillis by remember {
        mutableLongStateOf(currentTrim?.trimFromStartMillis ?: 0L)
    }
    var trimFromEndMillis by remember {
        mutableLongStateOf(currentTrim?.trimFromEndMillis ?: 0L)
    }

    val duration = export.session.summary.durationMillis.coerceAtLeast(0L)
    fun changeStart(delta: Long) {
        trimFromStartMillis = (trimFromStartMillis + delta)
            .coerceIn(0L, (duration - trimFromEndMillis).coerceAtLeast(0L))
    }
    fun changeEnd(delta: Long) {
        trimFromEndMillis = (trimFromEndMillis + delta)
            .coerceIn(0L, (duration - trimFromStartMillis).coerceAtLeast(0L))
    }

    BackHandler(enabled = !isSaving, onBack = onBack)

    WatchScreen(
        edgeButton = {
            EdgeButton(
                enabled = !isSaving,
                onClick = {
                    onSave(
                        falseHitIds,
                        missedHitCount,
                        trimFromStartMillis,
                        trimFromEndMillis
                    )
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(4.dp))
                Text(stringResource(R.string.action_save))
            }
        }
    ) {
        item { ScreenHeader(stringResource(R.string.correction_title)) }

        if (isSaving || saveError != null) {
            item {
                InfoCard {
                    Text(
                        text = saveError ?: stringResource(R.string.correction_saving),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saveError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        item {
            InfoCard(title = stringResource(R.string.correction_card_title)) {
                Text(
                    text = stringResource(R.string.correction_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CorrectionStepper(
                label = stringResource(R.string.correction_missed_hits),
                value = missedHitCount.toString(),
                enabled = !isSaving,
                onDecrease = { missedHitCount = (missedHitCount - 1).coerceAtLeast(0) },
                onIncrease = { missedHitCount = (missedHitCount + 1).coerceAtMost(999) }
            )
        }

        item {
            CorrectionStepper(
                label = stringResource(R.string.correction_trim_start),
                value = formatDuration(trimFromStartMillis),
                enabled = !isSaving,
                onDecrease = { changeStart(-TRIM_STEP_MILLIS) },
                onIncrease = { changeStart(TRIM_STEP_MILLIS) }
            )
        }

        item {
            CorrectionStepper(
                label = stringResource(R.string.correction_trim_end),
                value = formatDuration(trimFromEndMillis),
                enabled = !isSaving,
                onDecrease = { changeEnd(-TRIM_STEP_MILLIS) },
                onIncrease = { changeEnd(TRIM_STEP_MILLIS) }
            )
        }

        val recent = export.session.shots.takeLast(MAX_REVIEWABLE_HITS).asReversed()
        if (recent.isNotEmpty()) {
            item {
                InfoCard(title = stringResource(R.string.correction_recent_hits)) {
                    Text(
                        text = stringResource(R.string.correction_recent_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (export.session.shots.size > recent.size) {
                        Text(
                            text = stringResource(
                                R.string.correction_showing_latest,
                                recent.size,
                                export.session.shots.size
                            ),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(recent.size, key = { index -> recent[index].id }) { index ->
                val hit = recent[index]
                val markedFalse = hit.id in falseHitIds
                val offset = (hit.timestampMillis - export.session.startedAtMillis).coerceAtLeast(0L)
                val hitState = stringResource(
                    if (markedFalse) {
                        R.string.correction_state_false
                    } else {
                        R.string.correction_state_detected
                    }
                )
                TitleCard(
                    enabled = !isSaving,
                    onClick = {
                        falseHitIds = if (markedFalse) {
                            falseHitIds - hit.id
                        } else {
                            falseHitIds + hit.id
                        }
                    },
                    modifier = Modifier.semantics {
                        selected = markedFalse
                        stateDescription = hitState
                    },
                    title = {
                        Text(
                            if (markedFalse) {
                                stringResource(R.string.correction_marked_false)
                            } else {
                                hit.type.provisionalDisplayName()
                            }
                        )
                    },
                    time = {
                        Text(stringResource(R.string.correction_offset, formatDuration(offset)))
                    }
                ) {
                    Text(
                        text = if (markedFalse) {
                            stringResource(R.string.correction_restore)
                        } else {
                            stringResource(R.string.correction_mark_false)
                        },
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = if (markedFalse) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.size(40.dp)) }
    }
}

@Composable
private fun CorrectionStepper(
    label: String,
    value: String,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    val decreaseDescription = stringResource(R.string.a11y_decrease, label)
    val increaseDescription = stringResource(R.string.a11y_increase, label)
    InfoCard(title = label) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactButton(
                onClick = onDecrease,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = decreaseDescription },
                label = { Text("−") }
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            CompactButton(
                onClick = onIncrease,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = increaseDescription },
                label = { Text("+") }
            )
        }
    }
}

private const val MAX_REVIEWABLE_HITS = 30
private val TRIM_STEP_MILLIS = 15_000L

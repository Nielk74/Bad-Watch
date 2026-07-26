package com.badwatch.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CheckboxButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
            val label = stringResource(R.string.correction_missed_hits)
            InfoCard(title = label) {
                Stepper(
                    value = missedHitCount,
                    onValueChange = { missedHitCount = it },
                    valueProgression = 0..999,
                    decreaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_decrease, label)
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_increase, label)
                        )
                    },
                    enabled = !isSaving
                ) {
                    Text(
                        text = missedHitCount.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item {
            val label = stringResource(R.string.correction_trim_start)
            val maxSteps = ((duration - trimFromEndMillis).coerceAtLeast(0L) / TRIM_STEP_MILLIS).toInt()
            InfoCard(title = label) {
                Stepper(
                    value = (trimFromStartMillis / TRIM_STEP_MILLIS).toInt(),
                    onValueChange = { steps ->
                        changeStart(steps * TRIM_STEP_MILLIS - trimFromStartMillis)
                    },
                    valueProgression = 0..maxSteps,
                    decreaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_decrease, label)
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_increase, label)
                        )
                    },
                    enabled = !isSaving
                ) {
                    Text(
                        text = formatDuration(trimFromStartMillis),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item {
            val label = stringResource(R.string.correction_trim_end)
            val maxSteps = ((duration - trimFromStartMillis).coerceAtLeast(0L) / TRIM_STEP_MILLIS).toInt()
            InfoCard(title = label) {
                Stepper(
                    value = (trimFromEndMillis / TRIM_STEP_MILLIS).toInt(),
                    onValueChange = { steps ->
                        changeEnd(steps * TRIM_STEP_MILLIS - trimFromEndMillis)
                    },
                    valueProgression = 0..maxSteps,
                    decreaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_decrease, label)
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_increase, label)
                        )
                    },
                    enabled = !isSaving
                ) {
                    Text(
                        text = formatDuration(trimFromEndMillis),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
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
                CheckboxButton(
                    checked = markedFalse,
                    onCheckedChange = { checked ->
                        falseHitIds = if (checked) {
                            falseHitIds + hit.id
                        } else {
                            falseHitIds - hit.id
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = hitState },
                    enabled = !isSaving,
                    secondaryLabel = {
                        Column {
                            Text(stringResource(R.string.correction_offset, formatDuration(offset)))
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
                    },
                    label = {
                        Text(
                            if (markedFalse) {
                                stringResource(R.string.correction_marked_false)
                            } else {
                                hit.type.provisionalDisplayName()
                            }
                        )
                    }
                )
            }
        }
    }
}

private const val MAX_REVIEWABLE_HITS = 30
private val TRIM_STEP_MILLIS = 15_000L

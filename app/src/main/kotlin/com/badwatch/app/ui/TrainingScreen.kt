package com.badwatch.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.R
import com.badwatch.app.domain.ShadowControllerState
import com.badwatch.app.domain.ShadowCueHaptics
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.localization.localizedUiMessage
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.core.training.BwfPracticeLibrary
import com.badwatch.core.training.CourtCorner
import com.badwatch.core.training.PracticeDrill
import com.badwatch.core.training.ShadowRoutineState
import com.badwatch.core.training.ShadowStatus
import com.badwatch.core.training.ShadowTrainer
import java.util.Locale

/**
 * Practice hub and honest watch-guided shadow trainer.
 *
 * Technique cards are sourced general instruction. Shadow mode records only cue timestamps and
 * explicit player confirmations; it never claims a corner arrival, base recovery, or movement
 * quality from sensors that are not being used here.
 */
@Composable
fun TrainingScreen(
    controllerState: ShadowControllerState,
    onStartShadow: (Int) -> Unit,
    onConfirmShadow: () -> Unit,
    onPauseShadow: () -> Unit,
    onResumeShadow: () -> Unit,
    onFinishShadowEarly: () -> Unit,
    onClearShadow: () -> Unit,
    isAmbient: Boolean
) {
    var selectedDrill by remember { mutableStateOf<PracticeDrill?>(null) }
    BackHandler(enabled = selectedDrill != null) { selectedDrill = null }

    when (controllerState) {
        ShadowControllerState.Loading -> TrainingLoading()
        is ShadowControllerState.Idle -> {
            if (selectedDrill == null) {
                TrainingHub(
                    storageWarning = controllerState.storageWarning?.let { warning ->
                        localizedUiMessage(warning)
                    },
                    onStartShadow = onStartShadow,
                    onOpenDrill = { selectedDrill = it }
                )
            } else {
                PracticeDrillDetail(
                    drill = requireNotNull(selectedDrill),
                    onBack = { selectedDrill = null }
                )
            }
        }

        is ShadowControllerState.Failed -> ShadowFailure(
            message = localizedUiMessage(controllerState.message),
            onClear = onClearShadow
        )

        is ShadowControllerState.Active -> {
            val storageWarning = if (controllerState.storageWarning == null) {
                null
            } else {
                localizedUiMessage(controllerState.storageWarning)
            }
            ShadowRoutine(
                controllerState = controllerState.copy(storageWarning = storageWarning),
                onConfirm = onConfirmShadow,
                onPause = onPauseShadow,
                onResume = onResumeShadow,
                onFinishEarly = onFinishShadowEarly,
                onDone = onClearShadow,
                isAmbient = isAmbient
            )
        }
    }
}

@Composable
private fun TrainingLoading() {
    val loadingDescription = stringResource(R.string.a11y_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = loadingDescription }
        )
    }
}

@Composable
private fun TrainingHub(
    storageWarning: String?,
    onStartShadow: (Int) -> Unit,
    onOpenDrill: (PracticeDrill) -> Unit
) {
    var target by remember { mutableIntStateOf(18) }
    val generalDrills = BwfPracticeLibrary.drills.filterNot { it.id == SHADOW_DRILL_ID }

    WatchScreen(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { ScreenHeader(stringResource(R.string.training_title)) }
        storageWarning?.let { warning ->
            item {
                Text(
                    text = warning,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.training_shadow_title)) {
                Text(
                    text = stringResource(R.string.training_shadow_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.training_shadow_measured),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                TargetPicker(selected = target, onSelect = { target = it })
                Button(
                    onClick = { onStartShadow(target) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(pluralStringResource(R.plurals.training_start_cues, target, target))
                    },
                    secondaryLabel = { Text(stringResource(R.string.training_balanced_sequence)) }
                )
                Text(
                    text = stringResource(R.string.training_haptics),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.training_bwf_cues),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.training_general_not_assessed),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        generalDrills.forEach { drill ->
            item(key = drill.id) {
                val localized = drill.localizedText()
                TitleCard(
                    onClick = { onOpenDrill(drill) },
                    title = { Text(localized.title) },
                    time = { Text(stringResource(R.string.format_minutes, drill.durationMinutes)) }
                ) {
                    Text(
                        text = localized.focus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.training_general_cue),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun TargetPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = stringResource(R.string.training_routine_length),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            SHADOW_TARGETS.forEach { count ->
                val isSelected = selected == count
                CompactButton(
                    onClick = { onSelect(count) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { this.selected = isSelected },
                    colors = if (isSelected) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        ButtonDefaults.filledTonalButtonColors()
                    },
                    label = { Text(count.toString()) }
                )
            }
        }
    }
}

@Composable
private fun PracticeDrillDetail(drill: PracticeDrill, onBack: () -> Unit) {
    val localized = drill.localizedText()
    WatchScreen(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { ScreenHeader(localized.title) }
        item {
            InfoCard(title = stringResource(R.string.training_general_practice_cue)) {
                DetailRow(
                    stringResource(R.string.training_suggested_time),
                    stringResource(R.string.format_minutes, drill.durationMinutes)
                )
                Text(
                    text = localized.focus,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.training_not_diagnosis),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        localized.steps.forEachIndexed { index, step ->
            item(key = "${drill.id}-$index") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = step,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        item {
            InfoCard(title = stringResource(R.string.training_watch_knows)) {
                Text(
                    text = localized.measurementNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Text(
                text = stringResource(
                    R.string.training_source,
                    localized.sourceTitle,
                    drill.sourceUrl
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        item {
            CompactButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.training_back_to_practice)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ShadowRoutine(
    controllerState: ShadowControllerState.Active,
    onConfirm: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinishEarly: () -> Unit,
    onDone: () -> Unit,
    isAmbient: Boolean
) {
    val routine = controllerState.routine
    val context = LocalContext.current
    LaunchedEffect(routine.seed, routine.nextCueIndex, routine.status, isAmbient) {
        if (!isAmbient && routine.status == ShadowStatus.Active) {
            routine.currentCorner?.let { ShadowCueHaptics.play(context, it) }
        }
    }

    if (isAmbient) {
        AmbientShadowRoutine(routine)
        return
    }

    when (routine.status) {
        ShadowStatus.Active -> ActiveShadowCue(
            routine = routine,
            storageWarning = controllerState.storageWarning,
            onConfirm = onConfirm,
            onPause = onPause
        )

        ShadowStatus.Paused -> PausedShadowRoutine(
            routine = routine,
            restored = controllerState.restored,
            storageWarning = controllerState.storageWarning,
            onResume = onResume,
            onFinishEarly = onFinishEarly
        )

        ShadowStatus.Complete -> CompletedShadowRoutine(
            routine = routine,
            storageWarning = controllerState.storageWarning,
            onDone = onDone
        )
    }
}

@Composable
private fun ActiveShadowCue(
    routine: ShadowRoutineState,
    storageWarning: String?,
    onConfirm: () -> Unit,
    onPause: () -> Unit
) {
    val corner = requireNotNull(routine.currentCorner)
    val cueNumber = routine.completedRepetitions + 1
    val accent = corner.accentColor()
    val usesLargeTextLayout = shadowUsesLargeTextLayout(LocalDensity.current.fontScale)
    val warningReflow = usesLargeTextLayout && storageWarning != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = 22.dp,
                vertical = if (usesLargeTextLayout) 4.dp else 21.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!warningReflow) {
            val progressLabel = stringResource(
                R.string.training_cue_progress,
                cueNumber,
                routine.targetRepetitions
            )
            if (usesLargeTextLayout) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CourtCueGrid(corner = corner, accent = accent, compact = true)
                    Text(
                        text = "$cueNumber/${routine.targetRepetitions}",
                        modifier = Modifier.semantics { contentDescription = progressLabel },
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(3.dp))
                CourtCueGrid(corner = corner, accent = accent)
            }
            Spacer(modifier = Modifier.height(if (usesLargeTextLayout) 1.dp else 3.dp))
        }
        Text(
            text = stringResource(corner.displayNameResource).uppercase(Locale.getDefault()),
            modifier = Modifier.fillMaxWidth(),
            style = if (usesLargeTextLayout) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = accent,
            textAlign = TextAlign.Center,
            maxLines = if (usesLargeTextLayout) 2 else 1
        )
        if (!usesLargeTextLayout) {
            Text(
                text = stringResource(R.string.training_racket_side_reference),
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        storageWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(if (usesLargeTextLayout) 2.dp else 5.dp))
        if (usesLargeTextLayout) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.training_confirm_base)) }
            )
        } else {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.training_confirm_base)) },
                secondaryLabel = { Text(stringResource(R.string.training_confirm_next)) }
            )
        }
        val pauseLabel = stringResource(R.string.training_pause_routine)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = pauseLabel,
                    onClick = onPause
                )
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.action_pause),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CourtCueGrid(corner: CourtCorner, accent: Color, compact: Boolean = false) {
    val outline = MaterialTheme.colorScheme.outline
    val gridLine = MaterialTheme.colorScheme.outlineVariant
    val cueDescription = stringResource(
        R.string.training_court_cue,
        stringResource(corner.displayNameResource)
    )
    Canvas(
        modifier = Modifier
            .width(if (compact) 80.dp else 112.dp)
            .height(if (compact) 38.dp else 57.dp)
            .semantics { contentDescription = cueDescription }
    ) {
        val cellWidth = size.width / 2f
        val cellHeight = size.height / 3f
        val column = if (corner.isForehand()) 0 else 1
        val row = corner.depthIndex()
        drawRoundRect(
            color = accent.copy(alpha = 0.24f),
            topLeft = Offset(column * cellWidth + 2.dp.toPx(), row * cellHeight + 2.dp.toPx()),
            size = Size(cellWidth - 4.dp.toPx(), cellHeight - 4.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
        )
        drawRoundRect(
            color = outline,
            style = Stroke(width = 1.5.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
        )
        drawLine(
            color = gridLine,
            start = Offset(cellWidth, 0f),
            end = Offset(cellWidth, size.height),
            strokeWidth = 1.dp.toPx()
        )
        repeat(2) { divider ->
            val y = cellHeight * (divider + 1)
            drawLine(
                color = gridLine,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawCircle(
            color = accent,
            radius = 4.dp.toPx(),
            center = Offset(
                x = column * cellWidth + cellWidth / 2f,
                y = row * cellHeight + cellHeight / 2f
            )
        )
    }
}

@Composable
private fun PausedShadowRoutine(
    routine: ShadowRoutineState,
    restored: Boolean,
    storageWarning: String?,
    onResume: () -> Unit,
    onFinishEarly: () -> Unit
) {
    var confirmFinish by remember { mutableStateOf(false) }
    WatchScreen(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.training_paused),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${routine.completedRepetitions}/${routine.targetRepetitions}",
                    style = MaterialTheme.typography.numeralLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (restored) {
                        stringResource(R.string.training_restored)
                    } else {
                        stringResource(R.string.training_timing_stopped)
                    },
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        storageWarning?.let { warning ->
            item {
                Text(
                    text = warning,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.training_resume)) }
            )
        }
        item {
            Button(
                onClick = { confirmFinish = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.training_finish_early)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    AlertDialog(
        visible = confirmFinish,
        onDismissRequest = { confirmFinish = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmFinish = false
                    onFinishEarly()
                }
            )
        },
        title = { Text(stringResource(R.string.training_finish_question)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.training_finish_body,
                    routine.completedRepetitions,
                    routine.completedRepetitions
                )
            )
        }
    )
}

internal fun shadowUsesLargeTextLayout(fontScale: Float): Boolean = fontScale >= 1.2f

@Composable
private fun CompletedShadowRoutine(
    routine: ShadowRoutineState,
    storageWarning: String?,
    onDone: () -> Unit
) {
    val summary = ShadowTrainer.summary(routine)
    WatchScreen(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.training_complete),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = summary.completedRepetitions.toString(),
                    style = MaterialTheme.typography.numeralExtraLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.training_confirmed_of, summary.targetRepetitions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.training_cue_tap_delay)) {
                DetailRow(stringResource(R.string.label_median), formatResponse(summary.medianResponseMillis))
                DetailRow(stringResource(R.string.label_fastest), formatResponse(summary.fastestResponseMillis))
                Text(
                    text = stringResource(R.string.training_measurement_boundary),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        storageWarning?.let { warning ->
            item {
                Text(
                    text = warning,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.action_done)) },
                secondaryLabel = { Text(stringResource(R.string.training_back_to_practice)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun AmbientShadowRoutine(routine: ShadowRoutineState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val heading = when (routine.status) {
            ShadowStatus.Active ->
                stringResource(
                    R.string.training_cue_progress,
                    routine.completedRepetitions + 1,
                    routine.targetRepetitions
                )
            ShadowStatus.Paused -> stringResource(R.string.training_paused)
            ShadowStatus.Complete -> stringResource(R.string.training_complete)
        }
        Text(
            text = heading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (routine.status == ShadowStatus.Complete) {
            Text(
                text = routine.completedRepetitions.toString(),
                style = MaterialTheme.typography.numeralExtraLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.training_confirmed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            routine.currentCorner?.let { corner ->
                Text(
                    text = stringResource(corner.displayNameResource).uppercase(Locale.getDefault()),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = if (routine.status == ShadowStatus.Active) {
                    stringResource(R.string.training_tap_at_base)
                } else {
                    stringResource(R.string.training_resume_ready)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ShadowFailure(message: String, onClear: () -> Unit) {
    WatchScreen {
        item { ScreenHeader(stringResource(R.string.training_saved)) }
        item {
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        item {
            Button(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.training_remove_damaged)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun CourtCorner.accentColor(): Color = if (isForehand()) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.secondary
}

private fun CourtCorner.isForehand(): Boolean = when (this) {
    CourtCorner.ForehandFront, CourtCorner.ForehandMid, CourtCorner.ForehandRear -> true
    CourtCorner.BackhandFront, CourtCorner.BackhandMid, CourtCorner.BackhandRear -> false
}

private fun CourtCorner.depthIndex(): Int = when (this) {
    CourtCorner.ForehandFront, CourtCorner.BackhandFront -> 0
    CourtCorner.ForehandMid, CourtCorner.BackhandMid -> 1
    CourtCorner.ForehandRear, CourtCorner.BackhandRear -> 2
}

@Composable
private fun formatResponse(millis: Long?): String = millis?.let {
    stringResource(R.string.format_seconds_decimal, it / 1_000f)
} ?: "--"

private data class LocalizedPracticeDrill(
    val title: String,
    val focus: String,
    val steps: List<String>,
    val measurementNote: String,
    val sourceTitle: String
)

@Composable
private fun PracticeDrill.localizedText(): LocalizedPracticeDrill {
    val resources = when (id) {
        "six-corner-shadow" -> listOf(
            R.string.drill_six_corner_title,
            R.string.drill_six_corner_focus,
            R.string.drill_six_corner_step_1,
            R.string.drill_six_corner_step_2,
            R.string.drill_six_corner_step_3,
            R.string.drill_six_corner_step_4,
            R.string.drill_six_corner_measurement
        )
        "split-step-rhythm" -> listOf(
            R.string.drill_split_step_title,
            R.string.drill_split_step_focus,
            R.string.drill_split_step_step_1,
            R.string.drill_split_step_step_2,
            R.string.drill_split_step_step_3,
            R.string.drill_split_step_step_4,
            R.string.drill_split_step_measurement
        )
        "balanced-lunge" -> listOf(
            R.string.drill_balanced_lunge_title,
            R.string.drill_balanced_lunge_focus,
            R.string.drill_balanced_lunge_step_1,
            R.string.drill_balanced_lunge_step_2,
            R.string.drill_balanced_lunge_step_3,
            R.string.drill_balanced_lunge_step_4,
            R.string.drill_balanced_lunge_measurement
        )
        "overhead-preparation" -> listOf(
            R.string.drill_overhead_title,
            R.string.drill_overhead_focus,
            R.string.drill_overhead_step_1,
            R.string.drill_overhead_step_2,
            R.string.drill_overhead_step_3,
            R.string.drill_overhead_step_4,
            R.string.drill_overhead_measurement
        )
        "grip-change" -> listOf(
            R.string.drill_grip_change_title,
            R.string.drill_grip_change_focus,
            R.string.drill_grip_change_step_1,
            R.string.drill_grip_change_step_2,
            R.string.drill_grip_change_step_3,
            R.string.drill_grip_change_step_4,
            R.string.drill_grip_change_measurement
        )
        else -> null
    }
    return if (resources == null) {
        LocalizedPracticeDrill(title, focus, steps, measurementNote, sourceTitle)
    } else {
        LocalizedPracticeDrill(
            title = stringResource(resources[0]),
            focus = stringResource(resources[1]),
            steps = resources.subList(2, 6).map { stringResource(it) },
            measurementNote = stringResource(resources[6]),
            sourceTitle = stringResource(R.string.drill_bwf_source)
        )
    }
}

private const val SHADOW_DRILL_ID = "six-corner-shadow"
private val SHADOW_TARGETS = listOf(12, 18, 30)

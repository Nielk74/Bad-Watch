package com.badwatch.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.badwatch.app.R
import com.badwatch.app.domain.MatchControllerState
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.localization.shortNameResource
import com.badwatch.app.localization.localizedUiMessage
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.core.match.MatchFormat
import com.badwatch.core.match.MatchPrompt
import com.badwatch.core.match.MatchSide
import com.badwatch.core.match.MatchState
import com.badwatch.core.match.ServiceCourt
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.ceil

/**
 * Manual-first match scoring. Every point comes from a deliberate tap; motion detection never
 * picks a winner. Singles tracks the server and court. Doubles intentionally stops at the
 * serving side and left/right court because a watch cannot know the four-player rotation.
 */
@Composable
fun MatchScreen(
    controllerState: MatchControllerState,
    onStart: (MatchFormat, MatchSide) -> Unit,
    onAwardPoint: (MatchSide) -> Unit,
    onUndo: () -> Unit,
    onAcknowledgePrompt: () -> Unit,
    onClear: () -> Unit,
    isAmbient: Boolean
) {
    when (controllerState) {
        MatchControllerState.Loading -> MatchLoading()
        is MatchControllerState.Idle -> MatchSetup(
            storageWarning = controllerState.storageWarning?.let { warning ->
                localizedUiMessage(warning)
            },
            onStart = onStart
        )
        is MatchControllerState.Failed -> MatchFailure(
            message = localizedUiMessage(controllerState.message),
            onClear = onClear
        )

        is MatchControllerState.Active -> {
            val match = controllerState.match
            val storageWarning = if (controllerState.storageWarning == null) {
                null
            } else {
                localizedUiMessage(controllerState.storageWarning)
            }
            val promptHaptics = LocalHapticFeedback.current
            LaunchedEffect(match.prompt) {
                if (!isAmbient && match.prompt != null) {
                    promptHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    delay(140L)
                    promptHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            when {
                isAmbient -> AmbientMatchScore(match)
                match.isComplete -> MatchComplete(
                    match = match,
                    canUndo = controllerState.canUndo,
                    storageWarning = storageWarning,
                    onUndo = onUndo,
                    onDone = onClear
                )

                match.prompt != null -> MatchInterval(
                    match = match,
                    canUndo = controllerState.canUndo,
                    storageWarning = storageWarning,
                    onUndo = onUndo,
                    onResume = onAcknowledgePrompt
                )

                else -> ActiveMatch(
                    match = match,
                    canUndo = controllerState.canUndo,
                    storageWarning = storageWarning,
                    onAwardPoint = onAwardPoint,
                    onUndo = onUndo,
                    onAbandon = onClear
                )
            }
        }
    }
}

@Composable
private fun MatchLoading() {
    val loadingDescription = stringResource(R.string.a11y_loading)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = loadingDescription }
        )
    }
}

@Composable
private fun MatchSetup(
    storageWarning: String?,
    onStart: (MatchFormat, MatchSide) -> Unit
) {
    var format by remember { mutableStateOf(MatchFormat.Singles) }
    var server by remember { mutableStateOf(MatchSide.Player) }

    WatchScreen(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ScreenHeader(stringResource(R.string.match_title)) }
        item {
            Text(
                text = stringResource(R.string.match_setup_intro),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
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
            ChoiceRow(
                title = stringResource(R.string.match_format_label),
                left = stringResource(R.string.match_format_singles),
                right = stringResource(R.string.match_format_doubles),
                leftSelected = format == MatchFormat.Singles,
                onLeft = { format = MatchFormat.Singles },
                onRight = { format = MatchFormat.Doubles }
            )
        }
        item {
            ChoiceRow(
                title = stringResource(R.string.match_first_serve),
                left = stringResource(R.string.match_side_you),
                right = stringResource(R.string.match_side_opponent),
                leftSelected = server == MatchSide.Player,
                onLeft = { server = MatchSide.Player },
                onRight = { server = MatchSide.Opponent }
            )
        }
        item {
            Button(
                onClick = { onStart(format, server) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.match_start)) },
                secondaryLabel = { Text(stringResource(R.string.match_rules_short)) }
            )
        }
        if (format == MatchFormat.Doubles) {
            item {
                Text(
                    text = stringResource(R.string.match_doubles_note),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    left: String,
    right: String,
    leftSelected: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactButton(
                onClick = onLeft,
                modifier = Modifier
                    .weight(1f)
                    .semantics { selected = leftSelected },
                colors = choiceColors(selected = leftSelected),
                label = { Text(left) }
            )
            CompactButton(
                onClick = onRight,
                modifier = Modifier
                    .weight(1f)
                    .semantics { selected = !leftSelected },
                colors = choiceColors(selected = !leftSelected),
                label = { Text(right) }
            )
        }
    }
}

@Composable
private fun choiceColors(selected: Boolean) = if (selected) {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    )
} else {
    ButtonDefaults.filledTonalButtonColors()
}

@Composable
private fun ActiveMatch(
    match: MatchState,
    canUndo: Boolean,
    storageWarning: String?,
    onAwardPoint: (MatchSide) -> Unit,
    onUndo: () -> Unit,
    onAbandon: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    var confirmAbandon by remember { mutableStateOf(false) }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> ScorePage(
                    match = match,
                    canUndo = canUndo,
                    storageWarning = storageWarning,
                    onAwardPoint = onAwardPoint,
                    onUndo = onUndo
                )

                else -> MatchToolsPage(
                    match = match,
                    storageWarning = storageWarning,
                    onAbandonRequest = { confirmAbandon = true }
                )
            }
        }
    }

    AlertDialog(
        visible = confirmAbandon,
        onDismissRequest = { confirmAbandon = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmAbandon = false
                    onAbandon()
                }
            )
        },
        title = { Text(stringResource(R.string.match_abandon_question)) },
        text = { Text(stringResource(R.string.match_abandon_body)) }
    )
}

@Composable
private fun ScorePage(
    match: MatchState,
    canUndo: Boolean,
    storageWarning: String?,
    onAwardPoint: (MatchSide) -> Unit,
    onUndo: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 9.dp, vertical = 25.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ScoreSide(
                label = stringResource(R.string.match_side_you).uppercase(Locale.getDefault()),
                points = match.playerPoints,
                serving = match.server == MatchSide.Player,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAwardPoint(MatchSide.Player)
                }
            )
            ScoreSide(
                label = stringResource(R.string.match_side_them).uppercase(Locale.getDefault()),
                points = match.opponentPoints,
                serving = match.server == MatchSide.Opponent,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onAwardPoint(MatchSide.Opponent)
                }
            )
        }

        Text(
            text = stringResource(
                R.string.match_game_score,
                match.currentGameNumber,
                match.playerGames,
                match.opponentGames
            ),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ServicePill(
            match = match,
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(x = 0, y = 36.dp.roundToPx()) }
        )

        CompactButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(92.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            icon = {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.SmallIconSize)
                )
            },
            label = { Text(stringResource(R.string.action_undo)) }
        )

        storageWarning?.let { warning ->
            Text(
                text = warning,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 35.dp, start = 18.dp, end = 18.dp),
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ScoreSide(
    label: String,
    points: Int,
    serving: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pointsText = pluralStringResource(R.plurals.common_points_count, points, points)
    val scoreState = if (serving) {
        stringResource(R.string.match_score_state_serving, pointsText)
    } else {
        pointsText
    }
    val awardPointLabel = stringResource(R.string.match_award_point, label)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(44.dp))
            .background(tint.copy(alpha = if (serving) 0.18f else 0.08f))
            .semantics { stateDescription = scoreState }
            .clickable(
                role = Role.Button,
                onClickLabel = awardPointLabel,
                onClick = onClick
            )
            .padding(top = 32.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (serving) tint else MaterialTheme.colorScheme.onSurfaceVariant
        )
        AnimatedScore(points = points, color = tint)
    }
}

@Composable
private fun AnimatedScore(points: Int, color: Color) {
    AnimatedContent(
        targetState = points,
        transitionSpec = {
            (slideInVertically { height -> -height / 2 } + fadeIn()) togetherWith
                (slideOutVertically { height -> height / 2 } + fadeOut())
        },
        label = "matchScore"
    ) { score ->
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.numeralExtraLarge,
            color = color,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ServicePill(match: MatchState, modifier: Modifier = Modifier) {
    val side = when {
        match.format == MatchFormat.Doubles && match.server == MatchSide.Player ->
            stringResource(R.string.match_side_your_side)
        match.format == MatchFormat.Doubles -> stringResource(R.string.match_side_their_side)
        match.server == MatchSide.Player -> stringResource(R.string.match_side_you)
        else -> stringResource(R.string.match_side_them)
    }
    val court = stringResource(match.servingCourt.displayNameResource)
    val tint = if (match.server == MatchSide.Player) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(
                R.string.match_serve_pill,
                side.uppercase(Locale.getDefault()),
                court.uppercase(Locale.getDefault())
            ),
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

@Composable
private fun MatchToolsPage(
    match: MatchState,
    storageWarning: String?,
    onAbandonRequest: () -> Unit
) {
    WatchScreen {
        item { ScreenHeader(stringResource(R.string.match_details)) }
        item {
            InfoCard(title = stringResource(R.string.match_score)) {
                DetailRow(stringResource(R.string.match_current_game), "${match.playerPoints}–${match.opponentPoints}")
                DetailRow(stringResource(R.string.match_games), "${match.playerGames}–${match.opponentGames}")
                DetailRow(stringResource(R.string.label_activity), stringResource(match.format.displayNameResource))
                DetailRow(
                    stringResource(R.string.match_serving_court),
                    "${stringResource(match.server.shortNameResource)} · ${stringResource(match.servingCourt.displayNameResource)}"
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.match_scoring_title)) {
                Text(
                    text = stringResource(R.string.match_scoring_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (match.format == MatchFormat.Doubles) {
                    Text(
                        text = stringResource(R.string.match_scoring_doubles),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            CompactButton(
                onClick = onAbandonRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.SmallIconSize)
                    )
                },
                label = { Text(stringResource(R.string.match_abandon)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun MatchInterval(
    match: MatchState,
    canUndo: Boolean,
    storageWarning: String?,
    onUndo: () -> Unit,
    onResume: () -> Unit
) {
    val endMillis = match.intervalEndsAtMillis
    var nowMillis by remember(endMillis) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMillis) {
        while (endMillis != null && nowMillis < endMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val remainingSeconds = endMillis?.let { end ->
        ceil(((end - nowMillis).coerceAtLeast(0L)) / 1_000.0).toInt()
    }
    val heading = when (match.prompt) {
        MatchPrompt.IntervalAtEleven -> stringResource(R.string.match_interval_at_eleven)
        MatchPrompt.GameInterval, MatchPrompt.ChangeEnds -> stringResource(R.string.match_change_ends)
        else -> stringResource(R.string.match_interval)
    }
    val detail = when (match.prompt) {
        MatchPrompt.GameInterval -> stringResource(R.string.match_interval_game_next, match.currentGameNumber)
        MatchPrompt.ChangeEnds -> stringResource(R.string.match_interval_deciding)
        else -> stringResource(R.string.match_interval_ready_body)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Text(
            text = remainingSeconds?.let(::formatCountdown) ?: stringResource(R.string.match_ready),
            style = MaterialTheme.typography.numeralLarge,
            color = if (remainingSeconds == 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        storageWarning?.let { warning ->
            Text(
                text = warning,
                style = MaterialTheme.typography.bodyExtraSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.match_resume)) }
        )
        val undoIntervalLabel = stringResource(R.string.match_undo_interval_point)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    enabled = canUndo,
                    role = Role.Button,
                    onClickLabel = undoIntervalLabel,
                    onClick = onUndo
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (canUndo) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
            Text(
                text = stringResource(R.string.match_undo_point),
                style = MaterialTheme.typography.labelMedium,
                color = if (canUndo) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
    }
}

@Composable
private fun MatchComplete(
    match: MatchState,
    canUndo: Boolean,
    storageWarning: String?,
    onUndo: () -> Unit,
    onDone: () -> Unit
) {
    WatchScreen(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (match.winner == MatchSide.Player) {
                        stringResource(R.string.match_you_won)
                    } else {
                        stringResource(R.string.match_complete)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (match.winner == MatchSide.Player) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                )
                Text(
                    text = "${match.playerGames}–${match.opponentGames}",
                    style = MaterialTheme.typography.numeralExtraLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.match_games_upper),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            InfoCard(title = stringResource(R.string.match_game_scores)) {
                match.completedGames.forEachIndexed { index, game ->
                    DetailRow(stringResource(R.string.match_game_number, index + 1), "${game.player}–${game.opponent}")
                }
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
                secondaryLabel = { Text(stringResource(R.string.match_close_scoreboard)) }
            )
        }
        item {
            CompactButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                label = { Text(stringResource(R.string.match_undo_match_point)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/** Static, high-contrast scoreboard for always-on mode: no pager, taps or animation. */
@Composable
private fun AmbientMatchScore(match: MatchState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (match.isComplete) {
                stringResource(R.string.match_complete)
            } else {
                stringResource(
                    R.string.match_game_score,
                    match.currentGameNumber,
                    match.playerGames,
                    match.opponentGames
                )
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AmbientSide(
                stringResource(R.string.match_side_you).uppercase(Locale.getDefault()),
                match.playerPoints,
                match.server == MatchSide.Player
            )
            Text(
                text = "–",
                style = MaterialTheme.typography.numeralMedium,
                color = MaterialTheme.colorScheme.outline
            )
            AmbientSide(
                stringResource(R.string.match_side_them).uppercase(Locale.getDefault()),
                match.opponentPoints,
                match.server == MatchSide.Opponent
            )
        }
        if (!match.isComplete) {
            val side = if (match.server == MatchSide.Player) {
                stringResource(R.string.match_side_you)
            } else {
                stringResource(R.string.match_side_them)
            }
            Text(
                text = stringResource(
                    R.string.match_serve_pill,
                    side.uppercase(Locale.getDefault()),
                    stringResource(match.servingCourt.displayNameResource).uppercase(Locale.getDefault())
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        match.prompt?.takeUnless { it == MatchPrompt.MatchComplete }?.let { prompt ->
            Text(
                text = when (prompt) {
                    MatchPrompt.IntervalAtEleven -> stringResource(R.string.match_interval_at_eleven)
                    MatchPrompt.GameInterval, MatchPrompt.ChangeEnds -> stringResource(R.string.match_change_ends)
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AmbientSide(label: String, score: Int, serving: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = score.toString(),
            style = MaterialTheme.typography.numeralLarge,
            color = if (serving) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun MatchFailure(message: String, onClear: () -> Unit) {
    WatchScreen {
        item { ScreenHeader(stringResource(R.string.match_saved)) }
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
                label = { Text(stringResource(R.string.match_remove_damaged)) }
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private fun formatCountdown(seconds: Int): String = String.format(
    Locale.getDefault(),
    "%d:%02d",
    seconds / 60,
    seconds % 60
)

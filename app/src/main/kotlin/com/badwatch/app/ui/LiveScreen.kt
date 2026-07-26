package com.badwatch.app.ui

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.AnimatedText
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberAnimatedTextFontRegistry
import com.badwatch.app.R
import com.badwatch.app.domain.SessionState
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.DistributionBar
import com.badwatch.app.ui.components.DistributionSegment
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.MeterRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.color
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatHeartRate
import com.badwatch.app.ui.components.formatRestRatio
import com.badwatch.app.ui.components.hrZoneColor
import com.badwatch.app.ui.components.hrZoneLabel
import com.badwatch.app.ui.components.provisionalDisplayName
import com.badwatch.core.model.ShotEvent
import java.util.Date
import java.util.Locale

/**
 * The in-play HUD — the screen this app exists for.
 *
 * Designed for half a second of attention between rallies: the shot count is the biggest
 * thing on the watch, the ring around the edge is the heart-rate zone at a glance (color,
 * not numbers), and the stop action lives at the bottom edge. Everything else — rally
 * detail, body scores, discard — is one swipe away on the second page.
 */
@Composable
fun LiveScreen(
    state: SessionState.Recording,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    isAmbient: Boolean = false,
    ambientTimeMillis: Long = System.currentTimeMillis()
) {
    // Always-on: in ambient the watch keeps a dim, static face — no animations, no pager,
    // no actions. Recording runs in the foreground service; this is purely the glance.
    if (isAmbient) {
        AmbientHud(state = state, ambientTimeMillis = ambientTimeMillis)
        return
    }
    val pagerState = rememberPagerState(pageCount = { 2 })
    var confirmDiscard by remember { mutableStateOf(false) }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> HudPage(state = state, onStop = onStop)
                else -> HudDetailsPage(
                    state = state,
                    onDiscardRequest = { confirmDiscard = true }
                )
            }
        }
    }

    AlertDialog(
        visible = confirmDiscard,
        onDismissRequest = { confirmDiscard = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmDiscard = false
                    onDiscard()
                }
            )
        },
        title = { Text(stringResource(R.string.live_discard_question)) },
        text = { Text(stringResource(R.string.live_discard_body)) }
    )
}

@Composable
private fun HudPage(
    state: SessionState.Recording,
    onStop: () -> Unit
) {
    val snapshot = state.snapshot
    val heartRate = snapshot.currentHeartRate
    val maxHeartRate = state.profile.maxHeartRate
    val hasPersonalizedZones = state.profile.hasConfiguredMaxHeartRate
    val zoneColor = if (hasPersonalizedZones) {
        hrZoneColor(heartRate, maxHeartRate)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Heart-rate as a ring: both zone and fill use this player's estimated maximum.
        if (hasPersonalizedZones) {
            CircularProgressIndicator(
                progress = { ((heartRate ?: 0f) / maxHeartRate).coerceIn(0.02f, 1f) },
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
                colors = ProgressIndicatorDefaults.colors(
                    indicatorColor = zoneColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                strokeWidth = 5.dp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.live_hits_upper),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PulsingShotCount(count = snapshot.totalShots)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = zoneColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (hasPersonalizedZones) {
                        stringResource(
                            R.string.live_heart_rate_line,
                            formatHeartRate(heartRate),
                            hrZoneLabel(heartRate, maxHeartRate)
                        )
                    } else {
                        stringResource(
                            R.string.live_heart_rate_only,
                            formatHeartRate(heartRate)
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = zoneColor
                )
            }
            Text(
                text = formatDuration(snapshot.durationMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            snapshot.lastShot?.let { shot ->
                LastShotBadge(shot = shot)
            }
        }

        EdgeButton(
            onClick = onStop,
            modifier = Modifier.align(Alignment.BottomCenter),
            buttonSize = EdgeButtonSize.Small,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Text(stringResource(R.string.live_stop_save))
        }
    }
}

/**
 * The count snaps back to rest with a spring every time it changes — the player *feels* a
 * detected shot when they glance down, without needing haptics to fire mid-rally. On API 31+
 * the digits themselves also morph weight via [AnimatedText] (variable-font interpolation).
 */
@Composable
private fun PulsingShotCount(count: Int) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(count) {
        scale.snapTo(1.3f)
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val pulsed = Modifier.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val style = MaterialTheme.typography.numeralExtraLarge
            .copy(color = MaterialTheme.colorScheme.primary)
        val registry = rememberAnimatedTextFontRegistry(
            startFontVariationSettings = FontVariation.Settings(FontVariation.weight(350)),
            endFontVariationSettings = FontVariation.Settings(FontVariation.weight(900)),
            textStyle = style
        )
        val fraction = remember { Animatable(1f) }
        LaunchedEffect(count) {
            fraction.snapTo(0f)
            fraction.animateTo(1f, tween(300))
        }
        AnimatedText(
            text = count.toString(),
            fontRegistry = registry,
            progressFraction = { fraction.value },
            modifier = pulsed
        )
    } else {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.numeralExtraLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = pulsed
        )
    }
}

/**
 * The always-on face. Ambient rules: mostly black pixels, thin strokes, no animation, no
 * interactive elements — the system shifts it periodically against burn-in. Everything here
 * is legible at a glance with the screen at its lowest power state.
 */
@Composable
private fun AmbientHud(
    state: SessionState.Recording,
    ambientTimeMillis: Long
) {
    val snapshot = state.snapshot
    val context = LocalContext.current
    val is24HourClock = DateFormat.is24HourFormat(context)
    val model = remember(snapshot.startedAtMillis, ambientTimeMillis, is24HourClock) {
        ambientHudModel(
            ambientTimeMillis = ambientTimeMillis,
            // The minute-scale ambient callback is the key, so 100 Hz telemetry
            // recompositions cannot continually redraw this otherwise static count.
            detectedHitCount = snapshot.totalShots,
            formatLocalTime = { timestampMillis ->
                DateFormat.getTimeFormat(context).format(Date(timestampMillis))
            }
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = model.clockText,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.82f),
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.live_hits_upper),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            text = model.detectedHitCount.toString(),
            style = MaterialTheme.typography.numeralExtraLarge,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center
        )
        Text(
            // Neither value is rendered stale: both explicit dashes truthfully say that
            // high-frequency live metrics resume only after the player wakes the display.
            text = stringResource(R.string.ambient_live_values_paused),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.5f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LastShotBadge(shot: ShotEvent) {
    Crossfade(targetState = shot.type, label = "lastShot") { type ->
        Row(
            modifier = Modifier
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(50))
                .background(type.color().copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(type.color())
            )
            Text(
                text = type.provisionalDisplayName(),
                style = MaterialTheme.typography.labelMedium,
                color = type.color()
            )
        }
    }
}

/** Second pager page: the numbers that need a whole second of attention. */
@Composable
private fun HudDetailsPage(
    state: SessionState.Recording,
    onDiscardRequest: () -> Unit
) {
    val snapshot = state.snapshot
    val rallies = state.rallyProfile

    WatchScreen {
        item {
            InfoCard(title = stringResource(R.string.live_detected_play)) {
                DetailRow(stringResource(R.string.live_rally_bursts), rallies.rallyCount.toString())
                DetailRow(
                    stringResource(R.string.live_average_hits),
                    String.format(Locale.getDefault(), "%.1f", rallies.averageShotsPerRally)
                )
                val longestHits = rallies.longestRally?.shotCount ?: 0
                DetailRow(
                    stringResource(R.string.label_longest),
                    pluralStringResource(R.plurals.common_hits_count, longestHits, longestHits)
                )
                DetailRow(stringResource(R.string.live_active_quiet), formatRestRatio(rallies.restRatio))
                val total = rallies.totalWorkMillis + rallies.totalRestMillis
                if (total > 0) {
                    val activePercent = (rallies.workDensity * 100).toInt()
                    DistributionBar(
                        segments = listOf(
                            DistributionSegment(
                                color = MaterialTheme.colorScheme.primary,
                                fraction = rallies.totalWorkMillis.toFloat() / total
                            ),
                            DistributionSegment(
                                color = MaterialTheme.colorScheme.secondary,
                                fraction = rallies.totalRestMillis.toFloat() / total
                            )
                        ),
                        contentDescription = stringResource(
                            R.string.live_activity_distribution,
                            activePercent,
                            100 - activePercent
                        )
                    )
                    DetailRow(
                        stringResource(R.string.label_estimated_active),
                        "$activePercent%"
                    )
                }
            }
        }

        item {
            InfoCard(title = stringResource(R.string.label_heart_rate)) {
                DetailRow(
                    stringResource(R.string.label_current),
                    stringResource(R.string.format_bpm, formatHeartRate(snapshot.currentHeartRate))
                )
                DetailRow(
                    stringResource(R.string.label_average),
                    stringResource(R.string.format_bpm, formatHeartRate(snapshot.averageHeartRate))
                )
                DetailRow(
                    stringResource(R.string.label_peak),
                    stringResource(R.string.format_bpm, formatHeartRate(snapshot.maxHeartRate))
                )
                snapshot.averageHeartRateReserve?.let { reserve ->
                    MeterRow(
                        label = stringResource(R.string.live_heart_rate_reserve),
                        fraction = reserve,
                        color = MaterialTheme.colorScheme.secondary,
                        valueText = "${(reserve * 100).toInt()}%"
                    )
                }
                if (snapshot.heartRateSampleCount > 0) {
                    DetailRow(
                        stringResource(R.string.label_signal_coverage),
                        "${(snapshot.heartRateCoverage * 100).toInt()}%"
                    )
                }
            }
        }

        item {
            InfoCard(title = stringResource(R.string.live_last_hit)) {
                val last = snapshot.lastShot
                if (last == null) {
                    Text(
                        text = stringResource(R.string.live_waiting_swing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    DetailRow(
                        label = stringResource(R.string.label_type),
                        value = last.type.provisionalDisplayName(),
                        valueColor = last.type.color()
                    )
                    DetailRow(
                        stringResource(R.string.label_peak),
                        stringResource(R.string.format_radians_per_second, last.peakAngularVelocity)
                    )
                }
            }
        }

        item {
            CompactButton(
                onClick = onDiscardRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.SmallIconSize)
                    )
                },
                label = { Text(stringResource(R.string.action_discard)) }
            )
        }
    }
}

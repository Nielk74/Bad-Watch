package com.badwatch.app.ui

import android.os.Build
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
import com.badwatch.app.domain.SessionState
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.DistributionBar
import com.badwatch.app.ui.components.DistributionSegment
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.MeterRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.color
import com.badwatch.app.ui.components.displayName
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatHeartRate
import com.badwatch.app.ui.components.formatRestRatio
import com.badwatch.app.ui.components.hrZoneColor
import com.badwatch.app.ui.components.hrZoneLabel
import com.badwatch.core.model.ShotEvent
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
    isAmbient: Boolean = false
) {
    // Always-on: in ambient the watch keeps a dim, static face — no animations, no pager,
    // no actions. Recording runs in the foreground service; this is purely the glance.
    if (isAmbient) {
        AmbientHud(state = state)
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
        title = { Text("Discard session?") },
        text = { Text("Recording stops and nothing is saved.") }
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
    val zoneColor = hrZoneColor(heartRate, maxHeartRate)

    Box(modifier = Modifier.fillMaxSize()) {
        // Heart-rate as a ring: both zone and fill use this player's estimated maximum.
        CircularProgressIndicator(
            progress = { ((heartRate ?: 0f) / maxHeartRate).coerceIn(0.02f, 1f) },
            modifier = Modifier.fillMaxSize(),
            colors = ProgressIndicatorDefaults.colors(
                indicatorColor = zoneColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            strokeWidth = 5.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "HITS",
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
                    text = "${formatHeartRate(heartRate)} bpm · ${hrZoneLabel(heartRate, maxHeartRate)}",
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
            Text("Stop & save")
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
private fun AmbientHud(state: SessionState.Recording) {
    val snapshot = state.snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "HITS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = snapshot.totalShots.toString(),
            style = MaterialTheme.typography.numeralExtraLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${formatHeartRate(snapshot.currentHeartRate)} bpm · " +
                formatDuration(snapshot.durationMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                text = type.displayName(),
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
            InfoCard(title = "Detected play") {
                DetailRow("Rally bursts", rallies.rallyCount.toString())
                DetailRow("Avg hits", String.format(Locale.US, "%.1f", rallies.averageShotsPerRally))
                DetailRow("Longest", "${rallies.longestRally?.shotCount ?: 0} hits")
                DetailRow("Est. active : quiet", formatRestRatio(rallies.restRatio))
                val total = rallies.totalWorkMillis + rallies.totalRestMillis
                if (total > 0) {
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
                        )
                    )
                    DetailRow("Estimated active", "${(rallies.workDensity * 100).toInt()}%")
                }
            }
        }

        item {
            InfoCard(title = "Heart rate") {
                DetailRow("Current", "${formatHeartRate(snapshot.currentHeartRate)} bpm")
                DetailRow("Average", "${formatHeartRate(snapshot.averageHeartRate)} bpm")
                DetailRow("Peak", "${formatHeartRate(snapshot.maxHeartRate)} bpm")
                snapshot.averageHeartRateReserve?.let { reserve ->
                    MeterRow(
                        label = "HR reserve",
                        fraction = reserve,
                        color = MaterialTheme.colorScheme.secondary,
                        valueText = "${(reserve * 100).toInt()}%"
                    )
                }
                if (snapshot.heartRateSampleCount > 0) {
                    DetailRow(
                        "Signal coverage",
                        "${(snapshot.heartRateCoverage * 100).toInt()}%"
                    )
                }
            }
        }

        item {
            InfoCard(title = "Last detected hit") {
                val last = snapshot.lastShot
                if (last == null) {
                    Text(
                        text = "Waiting for your first swing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    DetailRow(
                        label = "Type",
                        value = last.type.displayName(),
                        valueColor = last.type.color()
                    )
                    DetailRow("Confidence", "${(last.confidence * 100).toInt()}%")
                    DetailRow(
                        "Peak",
                        String.format(Locale.US, "%.1f rad/s", last.peakAngularVelocity)
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
                label = { Text("Discard") }
            )
        }
    }
}

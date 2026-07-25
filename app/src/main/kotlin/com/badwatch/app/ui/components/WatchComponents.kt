package com.badwatch.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.badwatch.app.ui.theme.CourtColors
import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.InsightSeverity
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.heartRateZoneFor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Bad Watch building blocks. Every screen is a [WatchScreen] — a round-display
 * scaffold (time text, scroll indicator, rotary input) wrapping a TransformingLazyColumn.
 * On top of that: cards for grouped content, 3-up stat rows for glanceable numbers, and
 * small canvas charts (sparkline, distribution bar) drawn directly — no chart library.
 */

@Composable
fun WatchScreen(
    modifier: Modifier = Modifier,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: TransformingLazyColumnScope.() -> Unit
) {
    val scrollState = rememberTransformingLazyColumnState()
    val list: @Composable BoxScope.(androidx.compose.foundation.layout.PaddingValues) -> Unit =
        { contentPadding ->
            TransformingLazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = verticalArrangement,
                content = content
            )
        }
    if (edgeButton != null) {
        ScreenScaffold(
            scrollState = scrollState,
            edgeButton = edgeButton,
            modifier = modifier,
            content = list
        )
    } else {
        ScreenScaffold(scrollState = scrollState, modifier = modifier, content = list)
    }
}

/** Centered screen title — the first item of every scrolling screen. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(Locale.US),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
}

/** A grouped, non-clickable content card with an optional accent title. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(
                    text = title.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

/** One glanceable number: label above, numeral below. */
data class Stat(val label: String, val value: String, val color: Color = Color.Unspecified)

@Composable
fun StatRow(vararg stats: Stat, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        stats.forEach { stat ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stat.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Text(
                    text = stat.value,
                    style = MaterialTheme.typography.numeralMedium,
                    color = stat.color,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/** A left-label / right-value line, for dense detail lists inside cards. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End
        )
    }
}

/** A labeled progress meter, e.g. fatigue or effort scores in 0..1. */
@Composable
fun MeterRow(
    label: String,
    fraction: Float,
    color: Color,
    valueText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (valueText != null) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = color
                )
            }
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            colors = ProgressIndicatorDefaults.colors(
                indicatorColor = color,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
    }
}

/** A line-and-dot sparkline for recent trends. Silent (draws nothing) under two points. */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp)) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = stepX * index
            val y = size.height * (1f - (value - min) / range)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val lastX = size.width
        val lastY = size.height * (1f - (values.last() - min) / range)
        drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}

/** One stacked proportion segment of a [DistributionBar]. */
data class DistributionSegment(val color: Color, val fraction: Float)

/** A rounded stacked bar showing proportions, e.g. work:rest or shot mix. */
@Composable
fun DistributionBar(
    segments: List<DistributionSegment>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
    ) {
        var x = 0f
        segments.forEach { segment ->
            val width = size.width * segment.fraction.coerceIn(0f, 1f)
            if (width > 0f) {
                drawRect(
                    color = segment.color,
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height)
                )
            }
            x += width
        }
    }
}

/**
 * An insight card with a severity-colored leading bar. Insights are the only part of the
 * recap that *says* something rather than just reporting, so they read first.
 */
@Composable
fun InsightCard(insight: Insight) {
    val accent = insight.severity.color()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .padding(vertical = 1.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) { drawRect(accent) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = insight.severity.label().uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Text(
                    text = insight.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = insight.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = insight.evidence,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun InsightSeverity.color(): Color = when (this) {
    InsightSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    InsightSeverity.Notable -> MaterialTheme.colorScheme.primary
    InsightSeverity.Caution -> CourtColors.Warning
}

fun InsightSeverity.label(): String = when (this) {
    InsightSeverity.Info -> "Noticed"
    InsightSeverity.Notable -> "Stands out"
    InsightSeverity.Caution -> "Worth watching"
}

// ---------------------------------------------------------------------------------------------
// Heart-rate zones
// ---------------------------------------------------------------------------------------------

/**
 * 0 = no reading, 1..5 = zones relative to this player's estimated maximum heart rate.
 * Keeping this mapping on the same core function as the session histogram prevents a recap
 * from disagreeing with the live HUD for younger, older, or manually calibrated players.
 */
fun hrZoneOf(bpm: Float?, maxHeartRate: Float): Int {
    if (bpm == null || bpm <= 0f || maxHeartRate <= 0f) return 0
    return when (heartRateZoneFor(bpm, maxHeartRate)) {
        HeartRateZone.WarmUp -> 1
        HeartRateZone.Endurance -> 2
        HeartRateZone.Tempo -> 3
        HeartRateZone.Threshold -> 4
        HeartRateZone.VO2Max -> 5
    }
}

fun hrZoneColor(bpm: Float?, maxHeartRate: Float): Color = when (hrZoneOf(bpm, maxHeartRate)) {
    1 -> CourtColors.Zone1
    2 -> CourtColors.Zone2
    3 -> CourtColors.Zone3
    4 -> CourtColors.Zone4
    5 -> CourtColors.Zone5
    else -> Color(0xFFA7B4C2)
}

fun hrZoneLabel(bpm: Float?, maxHeartRate: Float): String =
    when (val zone = hrZoneOf(bpm, maxHeartRate)) {
        0 -> "--"
        else -> "Z$zone"
    }

// ---------------------------------------------------------------------------------------------
// Shot types
// ---------------------------------------------------------------------------------------------

fun ShotType.displayName(): String = when (this) {
    ShotType.Smash -> "Smash"
    ShotType.Clear -> "Clear"
    ShotType.Drop -> "Drop"
    ShotType.Drive -> "Drive"
    ShotType.BackhandDrive -> "Backhand drive"
    ShotType.Unknown -> "Unclassified"
}

fun ShotType.color(): Color = when (this) {
    ShotType.Smash -> CourtColors.Smash
    ShotType.Clear -> CourtColors.Clear
    ShotType.Drop -> CourtColors.Drop
    ShotType.Drive -> CourtColors.Drive
    ShotType.BackhandDrive -> CourtColors.Backhand
    ShotType.Unknown -> CourtColors.UnknownShot
}

// ---------------------------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------------------------

fun formatDuration(durationMillis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(durationMillis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatHeartRate(bpm: Float?): String =
    if (bpm == null || bpm <= 0f) "--" else bpm.toInt().toString()

/** Work:rest shown the way coaches say it — "1:2.3". */
fun formatRestRatio(ratio: Float): String =
    if (ratio <= 0f) "--" else String.format(Locale.US, "1:%.1f", ratio)

fun formatSessionDate(epochMillis: Long): String =
    SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault()).format(Date(epochMillis))

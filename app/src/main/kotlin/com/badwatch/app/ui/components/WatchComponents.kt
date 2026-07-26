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
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.badwatch.app.R
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.localization.provisionalDisplayNameResource
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
        text = title.uppercase(Locale.getDefault()),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
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
                    text = title.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

/** One glanceable number: label above, numeral below. */
data class Stat(
    val label: String,
    val value: String,
    val color: Color = Color.Unspecified,
    val weight: Float = 1f
)

/** Per-stat presentation derived from the stat's own strings, cached across recompositions. */
private data class RenderedStat(val label: String, val isTextual: Boolean)

@Composable
fun StatRow(vararg stats: Stat, modifier: Modifier = Modifier) {
    val dense = stats.size >= 3
    val stacked = shouldStackStats(stats.size, LocalDensity.current.fontScale)
    // Uppercasing and scanning each value for letters is pure per-stat string work that used
    // to run on every recomposition of every card. Keyed on the stats themselves.
    // Keyed on the stat list's contents, not the vararg array: arrays compare by identity, so
    // an array key would both miss every call and risk serving a stale entry for a reused one.
    val statList = stats.asList()
    val rendered = remember(statList, Locale.getDefault()) {
        val locale = Locale.getDefault()
        statList.map { stat ->
            RenderedStat(
                label = stat.label.uppercase(locale),
                isTextual = stat.value.any(Char::isLetter)
            )
        }
    }

    if (stacked) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            stats.forEachIndexed { index, stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = rendered[index].label,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stat.value,
                        style = if (rendered[index].isTextual) {
                            MaterialTheme.typography.titleSmall
                        } else {
                            MaterialTheme.typography.numeralSmall
                        },
                        color = stat.color,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        stats.forEachIndexed { index, stat ->
            Column(
                modifier = Modifier
                    .weight(stat.weight)
                    .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = rendered[index].label,
                    // Three-up cards are narrower than the full screen. Keep both words of
                    // qualifiers such as "Detected exchanges" visible rather than clipping a
                    // truth-bearing label at the round edge.
                    style = if (dense) {
                        MaterialTheme.typography.bodyExtraSmall
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stat.value,
                    style = when {
                        rendered[index].isTextual -> MaterialTheme.typography.titleSmall
                        dense -> MaterialTheme.typography.numeralSmall
                        else -> MaterialTheme.typography.numeralMedium
                    },
                    color = stat.color,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Three-up watch metrics need a vertical reflow once accessibility text outgrows each third. */
internal fun shouldStackStats(statCount: Int, fontScale: Float): Boolean =
    statCount >= 3 && fontScale >= 1.2f

internal enum class DurationStatRowLayout {
    ThreeUp,
    WideDuration,
    AccessibilityStack
}

/**
 * Three related metrics where the final value is an exact elapsed duration.
 *
 * Single-digit-minute values retain the glanceable three-up layout. At ten minutes the formatted
 * value gains a fifth character and no longer fits the outer third of a 480 px round display, so
 * it receives a full-width row. Enlarged text uses [StatRow]'s existing accessibility stack.
 */
@Composable
fun DurationStatRow(
    first: Stat,
    second: Stat,
    durationLabel: String,
    durationMillis: Long,
    modifier: Modifier = Modifier,
    durationWeight: Float = 1f
) {
    val duration = Stat(
        label = durationLabel,
        value = formatDuration(durationMillis.coerceAtLeast(0L)),
        weight = durationWeight
    )
    when (durationStatRowLayout(durationMillis, LocalDensity.current.fontScale)) {
        DurationStatRowLayout.WideDuration -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatRow(first, second)
                StatRow(duration)
            }
        }

        DurationStatRowLayout.ThreeUp,
        DurationStatRowLayout.AccessibilityStack ->
            StatRow(first, second, duration, modifier = modifier)
    }
}

internal fun durationStatRowLayout(
    durationMillis: Long,
    fontScale: Float
): DurationStatRowLayout = when {
    shouldStackStats(statCount = 3, fontScale = fontScale) ->
        DurationStatRowLayout.AccessibilityStack
    durationMillis.coerceAtLeast(0L) >= TimeUnit.MINUTES.toMillis(10L) ->
        DurationStatRowLayout.WideDuration
    else -> DurationStatRowLayout.ThreeUp
}

/** A left-label / right-value line, for dense detail lists inside cards. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A labeled progress meter for a bounded value in 0..1, such as goal completion. */
@Composable
fun MeterRow(
    label: String,
    fraction: Float,
    color: Color,
    valueText: String? = null
) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = label,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (valueText != null) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    textAlign = TextAlign.End
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
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val accessibility = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    // Allocated once and rewound per frame rather than rebuilt on every draw pass.
    val path = remember { Path() }
    Canvas(modifier = modifier.fillMaxWidth().height(28.dp).then(accessibility)) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        path.reset()
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
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val accessibility = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .then(accessibility)
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
    val localized = insight.localizedText()
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
                    text = insight.severity.label().uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
                Text(
                    text = localized.headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = localized.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = localized.evidence,
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private data class LocalizedInsightText(
    val headline: String,
    val detail: String,
    val evidence: String
)

@Composable
private fun Insight.localizedText(): LocalizedInsightText {
    fun fallback() = LocalizedInsightText(headline, detail, evidence)
    fun int(key: String) = localizationArgs[key]?.toIntOrNull()
    fun decimal(key: String) = localizationArgs[key]?.toFloatOrNull()

    return when (id) {
        "rest-ratio-up" -> {
            val ratio = decimal("ratio") ?: return fallback()
            val exchanges = int("exchanges") ?: return fallback()
            val change = int("change") ?: return fallback()
            val usual = decimal("usual") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_rest_up_headline),
                detail = stringResource(R.string.insight_rest_up_detail, change, usual),
                evidence = pluralStringResource(
                    R.plurals.insight_rest_evidence,
                    exchanges,
                    ratio,
                    exchanges
                )
            )
        }
        "rest-ratio-down" -> {
            val ratio = decimal("ratio") ?: return fallback()
            val exchanges = int("exchanges") ?: return fallback()
            val change = int("change") ?: return fallback()
            val usual = decimal("usual") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_rest_down_headline),
                detail = stringResource(R.string.insight_rest_down_detail, change, usual),
                evidence = pluralStringResource(
                    R.plurals.insight_rest_evidence,
                    exchanges,
                    ratio,
                    exchanges
                )
            )
        }
        "rest-ratio-high" -> {
            val ratio = decimal("ratio") ?: return fallback()
            val exchanges = int("exchanges") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_rest_high_headline),
                detail = stringResource(R.string.insight_rest_high_detail, ratio),
                evidence = pluralStringResource(
                    R.plurals.insight_rest_evidence,
                    exchanges,
                    ratio,
                    exchanges
                )
            )
        }
        "endurance-decay" -> {
            val third = int("third") ?: return fallback()
            val closing = decimal("closing") ?: return fallback()
            val opening = decimal("opening") ?: return fallback()
            val change = int("change") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_endurance_headline),
                detail = pluralStringResource(
                    R.plurals.insight_endurance_detail,
                    third,
                    third,
                    closing,
                    opening,
                    change
                ),
                evidence = stringResource(
                    R.string.insight_endurance_evidence,
                    opening,
                    closing
                )
            )
        }
        "longest-rally-best" -> {
            val hits = int("hits") ?: return fallback()
            val previous = int("previous") ?: return fallback()
            val seconds = int("seconds") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_longest_best_headline),
                detail = stringResource(R.string.insight_longest_best_detail, hits, previous),
                evidence = pluralStringResource(
                    R.plurals.insight_longest_best_evidence,
                    hits,
                    hits,
                    seconds
                )
            )
        }
        "longest-rally" -> {
            val hits = int("hits") ?: return fallback()
            val seconds = int("seconds") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_longest_headline),
                detail = pluralStringResource(
                    R.plurals.insight_longest_detail,
                    hits,
                    hits,
                    seconds
                ),
                evidence = pluralStringResource(
                    R.plurals.insight_longest_evidence,
                    hits,
                    hits
                )
            )
        }
        "low-work-density" -> {
            val activeMinutes = int("activeMinutes") ?: return fallback()
            val totalMinutes = int("totalMinutes") ?: return fallback()
            val activePercent = int("activePercent") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_density_headline),
                detail = stringResource(
                    R.string.insight_density_detail,
                    stringResource(R.string.format_minutes, activeMinutes),
                    stringResource(R.string.format_minutes, totalMinutes)
                ),
                evidence = stringResource(R.string.insight_density_evidence, activePercent)
            )
        }
        "cardiac-drift" -> {
            val drift = int("drift") ?: return fallback()
            val early = int("early") ?: return fallback()
            val late = int("late") ?: return fallback()
            LocalizedInsightText(
                headline = stringResource(R.string.insight_cardiac_headline),
                detail = stringResource(R.string.insight_cardiac_detail, drift),
                evidence = stringResource(R.string.insight_cardiac_evidence, early, late)
            )
        }
        else -> fallback()
    }
}

@Composable
fun InsightSeverity.color(): Color = when (this) {
    InsightSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    InsightSeverity.Notable -> MaterialTheme.colorScheme.primary
    InsightSeverity.Caution -> CourtColors.Warning
}

@Composable
fun InsightSeverity.label(): String = when (this) {
    InsightSeverity.Info -> stringResource(R.string.insight_severity_info)
    InsightSeverity.Notable -> stringResource(R.string.insight_severity_notable)
    InsightSeverity.Caution -> stringResource(R.string.insight_severity_caution)
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
    else -> CourtColors.UnknownShot
}

fun hrZoneLabel(bpm: Float?, maxHeartRate: Float): String =
    when (val zone = hrZoneOf(bpm, maxHeartRate)) {
        0 -> "--"
        else -> "Z$zone"
    }

// ---------------------------------------------------------------------------------------------
// Shot types
// ---------------------------------------------------------------------------------------------

@Composable
fun ShotType.displayName(): String = stringResource(displayNameResource)

/** Use for automatic classifier output; labelled capture drills intentionally use [displayName]. */
@Composable
fun ShotType.provisionalDisplayName(): String = stringResource(provisionalDisplayNameResource)

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
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

fun formatHeartRate(bpm: Float?): String =
    if (bpm == null || bpm <= 0f) "--" else bpm.toInt().toString()

/** Work:rest shown the way coaches say it — "1:2.3". */
fun formatRestRatio(ratio: Float): String =
    if (ratio <= 0f) "--" else String.format(Locale.getDefault(), "1:%.1f", ratio)

/**
 * Reused per thread rather than constructed per call: this runs once per history row, and
 * building a [SimpleDateFormat] parses the pattern and loads locale symbols every time.
 * [SimpleDateFormat] is not thread-safe, hence the [ThreadLocal] rather than a shared instance.
 * The cached formatter is rebuilt when the default locale changes, so a locale switch still
 * formats exactly as it did before.
 */
private val sessionDateFormat = object : ThreadLocal<Pair<Locale, SimpleDateFormat>>() {
    override fun initialValue(): Pair<Locale, SimpleDateFormat> = newFormatter()
}

private fun newFormatter(): Pair<Locale, SimpleDateFormat> {
    val locale = Locale.getDefault()
    return locale to SimpleDateFormat("EEE d MMM · HH:mm", locale)
}

fun formatSessionDate(epochMillis: Long): String {
    val cached = sessionDateFormat.get() ?: newFormatter()
    val formatter = if (cached.first == Locale.getDefault()) {
        cached.second
    } else {
        newFormatter().also(sessionDateFormat::set).second
    }
    return formatter.format(Date(epochMillis))
}

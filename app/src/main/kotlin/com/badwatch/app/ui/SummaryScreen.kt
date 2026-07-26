package com.badwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.R
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.DistributionBar
import com.badwatch.app.ui.components.DistributionSegment
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.InsightCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.color
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatHeartRate
import com.badwatch.app.ui.components.formatRestRatio
import com.badwatch.app.ui.components.hrZoneLabel
import com.badwatch.app.ui.components.provisionalDisplayName
import com.badwatch.core.insight.Insight
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.physiology.PostBurstHeartRateBuilder
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.effectiveMetrics
import com.badwatch.core.sync.reviewedAnalysis

/**
 * Post-session recap.
 *
 * Leads with the headline numbers, then the insights — the only part of the recap that says
 * something rather than just reporting — then the structure behind them: rally shape, work
 * vs rest, heart rate, shot mix. When the insight engine has nothing trustworthy to say it
 * says nothing, and that section simply does not appear.
 */
@Composable
fun SummaryScreen(
    stored: SessionExport,
    insights: List<Insight>,
    onDone: () -> Unit,
    onEditDiary: (() -> Unit)? = null,
    onCorrectRecording: (() -> Unit)? = null
) {
    val reviewed = stored.reviewedAnalysis()
    val summary = reviewed.session.summary
    val rallies = reviewed.rallyProfile
    val effective = reviewed.metrics
    val postBurstHeartRate = PostBurstHeartRateBuilder.build(stored)

    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = onDone, buttonSize = EdgeButtonSize.Medium) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.action_done))
            }
        }
    ) {
        item { ScreenHeader(stringResource(R.string.summary_saved)) }

        item {
            StatRow(
                Stat(
                    stringResource(
                        if (effective.hasCorrections) {
                            R.string.label_reviewed_hits
                        } else {
                            R.string.label_detected_hits
                        }
                    ),
                    effective.correctedDetectedHitCount.toString()
                ),
                Stat(
                    label = stringResource(R.string.label_exchanges),
                    value = rallies.rallyCount.toString(),
                    // The full, truthful French qualifier must survive a 480 px round screen
                    // and enlarged text; the neighbouring time label needs much less room.
                    weight = 1.30f
                ),
                Stat(
                    stringResource(
                        if (effective.hasCorrections) {
                            R.string.summary_reviewed_time
                        } else {
                            R.string.label_time
                        }
                    ),
                    formatDuration(effective.window.durationMillis),
                    // `m:ss` still needs enough width for the large numeral on a round edge.
                    weight = 0.85f
                )
            )
        }

        if (stored.context.diaryReviewStatus != DiaryReviewStatus.Unreviewed ||
            stored.context.activityMode != ActivityMode.Unspecified ||
            stored.report.rpe != null || stored.report.sorenessReviewed
        ) {
            item {
                InfoCard(title = stringResource(R.string.summary_your_report)) {
                    DetailRow(
                        stringResource(R.string.label_activity),
                        stringResource(stored.context.activityMode.displayNameResource)
                    )
                    stored.report.rpe?.let {
                        DetailRow(
                            stringResource(R.string.summary_perceived_effort),
                            stringResource(R.string.format_rpe, it)
                        )
                    }
                    stored.report.rpe?.let { rpe ->
                        val sessionRpeLoad = effective.window.durationMillis / 60_000f * rpe
                        DetailRow(
                            stringResource(R.string.summary_session_rpe_load),
                            stringResource(R.string.format_minutes_rpe, sessionRpeLoad)
                        )
                    }
                    if (stored.report.sorenessReviewed) {
                        val sorenessText = if (stored.report.soreness.isEmpty()) {
                            stringResource(R.string.summary_nothing_logged)
                        } else {
                            val localizedReports = mutableListOf<String>()
                            for (report in stored.report.soreness) {
                                localizedReports +=
                                    "${stringResource(report.bodyArea.displayNameResource)} " +
                                        "${report.severity}/10"
                            }
                            localizedReports.joinToString()
                        }
                        DetailRow(stringResource(R.string.summary_soreness), sorenessText)
                    }
                    if (stored.context.completion != SessionCompletion.Unreported) {
                        DetailRow(
                            stringResource(R.string.summary_plan),
                            stringResource(stored.context.completion.displayNameResource)
                        )
                    }
                    if (stored.context.recordingQuality != RecordingQuality.Unreviewed) {
                        DetailRow(
                            stringResource(R.string.label_recording),
                            stringResource(stored.context.recordingQuality.displayNameResource)
                        )
                    }
                }
            }
        }

        items(insights.size) { index -> InsightCard(insight = insights[index]) }

        item {
            InfoCard(title = stringResource(R.string.summary_detected_play)) {
                DetailRow(
                    stringResource(R.string.summary_average_burst),
                    String.format(java.util.Locale.getDefault(), "%.1f", rallies.averageShotsPerRally)
                )
                val longestHits = rallies.longestRally?.shotCount ?: 0
                DetailRow(
                    stringResource(R.string.label_longest),
                    pluralStringResource(R.plurals.common_hits_count, longestHits, longestHits)
                )
                DetailRow(stringResource(R.string.summary_active_quiet), formatRestRatio(rallies.restRatio))
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
                            R.string.summary_activity_distribution,
                            activePercent,
                            100 - activePercent
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot(
                            color = MaterialTheme.colorScheme.primary,
                            label = stringResource(R.string.summary_detected)
                        )
                        LegendDot(
                            color = MaterialTheme.colorScheme.secondary,
                            label = stringResource(R.string.summary_quiet)
                        )
                    }
                }
                DetailRow(
                    stringResource(R.string.label_estimated_active),
                    "${(rallies.workDensity * 100).toInt()}%"
                )
            }
        }

        item {
            InfoCard(title = stringResource(R.string.label_heart_rate)) {
                DetailRow(
                    stringResource(R.string.label_average),
                    stringResource(R.string.format_bpm, formatHeartRate(summary.averageHeartRate))
                )
                DetailRow(
                    stringResource(R.string.label_peak),
                    stringResource(R.string.format_bpm, formatHeartRate(summary.maxHeartRate)),
                    valueColor = MaterialTheme.colorScheme.error
                )
                if (stored.profile.hasConfiguredMaxHeartRate) {
                    DetailRow(
                        stringResource(R.string.summary_peak_zone),
                        hrZoneLabel(summary.maxHeartRate, stored.profile.maxHeartRate)
                    )
                }
                if (summary.heartRateSampleCount > 0) {
                    DetailRow(
                        stringResource(R.string.label_signal_coverage),
                        "${(summary.heartRateCoverage * 100).toInt()}%"
                    )
                }
                summary.cardiovascularLoad?.let { load ->
                    DetailRow(
                        stringResource(R.string.summary_cardio_response),
                        stringResource(R.string.format_hrr_minutes, load)
                    )
                }
                postBurstHeartRate?.let { change ->
                    val text = if (change.decreaseBpm >= 0) {
                        stringResource(
                            R.string.summary_hr_change_decrease,
                            change.decreaseBpm
                        )
                    } else {
                        stringResource(
                            R.string.summary_hr_change_increase,
                            -change.decreaseBpm
                        )
                    }
                    DetailRow(stringResource(R.string.summary_after_final_burst), text)
                    Text(
                        text = stringResource(R.string.summary_optical_hr_caveat),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val zoneSamples = summary.heartRateZoneHistogram.values.sum()
                if (stored.profile.hasConfiguredMaxHeartRate && zoneSamples > 0) {
                    DistributionBar(
                        segments = HEART_RATE_ZONE_ORDER.map { zone ->
                            DistributionSegment(
                                color = zone.color(),
                                fraction = (summary.heartRateZoneHistogram[zone] ?: 0)
                                    .toFloat() / zoneSamples
                            )
                        },
                        contentDescription = stringResource(R.string.summary_hr_distribution)
                    )
                    Text(
                        text = stringResource(R.string.summary_hr_zones),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (summary.shotCounts.isNotEmpty()) {
            item {
                InfoCard(title = stringResource(R.string.summary_stroke_mix)) {
                    val total = summary.shotCounts.values.sum().takeIf { it > 0 } ?: 1
                    val sorted = summary.shotCounts.entries.sortedByDescending { it.value }
                    DistributionBar(
                        segments = sorted.map { (type, count) ->
                            DistributionSegment(
                                color = type.color(),
                                fraction = count.toFloat() / total
                            )
                        }
                    )
                    sorted.forEach { (type, count) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LegendDot(
                                color = type.color(),
                                label = type.provisionalDisplayName()
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = count.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        if (effective.hasCorrections) {
            item {
                InfoCard(title = stringResource(R.string.summary_review_trail)) {
                    DetailRow(
                        stringResource(R.string.summary_raw_events),
                        effective.rawDetectedHitCount.toString()
                    )
                    if (effective.trimExcludedDetectedHitCount > 0) {
                        DetailRow(
                            stringResource(R.string.summary_outside_time),
                            effective.trimExcludedDetectedHitCount.toString()
                        )
                    }
                    if (effective.falseHitCount > 0) {
                        DetailRow(
                            stringResource(R.string.summary_marked_false),
                            effective.falseHitCount.toString()
                        )
                    }
                    if (effective.reportedMissedHitCount > 0) {
                        DetailRow(
                            stringResource(R.string.summary_reported_missed),
                            effective.reportedMissedHitCount.toString()
                        )
                    }
                    Text(
                        text = stringResource(R.string.summary_review_preserved),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (onEditDiary != null) {
            item {
                TitleCard(
                    onClick = onEditDiary,
                    title = { Text(stringResource(R.string.summary_edit_diary)) }
                ) {
                    Text(
                        stringResource(R.string.summary_edit_diary_subtitle),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (onCorrectRecording != null) {
            item {
                TitleCard(
                    onClick = onCorrectRecording,
                    title = { Text(stringResource(R.string.summary_review_detection)) }
                ) {
                    Text(
                        stringResource(R.string.summary_review_detection_subtitle),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartRateZone.color(): androidx.compose.ui.graphics.Color = when (this) {
    HeartRateZone.WarmUp -> com.badwatch.app.ui.theme.CourtColors.Zone1
    HeartRateZone.Endurance -> com.badwatch.app.ui.theme.CourtColors.Zone2
    HeartRateZone.Tempo -> com.badwatch.app.ui.theme.CourtColors.Zone3
    HeartRateZone.Threshold -> com.badwatch.app.ui.theme.CourtColors.Zone4
    HeartRateZone.VO2Max -> com.badwatch.app.ui.theme.CourtColors.Zone5
}

private val HEART_RATE_ZONE_ORDER = listOf(
    HeartRateZone.WarmUp,
    HeartRateZone.Endurance,
    HeartRateZone.Tempo,
    HeartRateZone.Threshold,
    HeartRateZone.VO2Max
)

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

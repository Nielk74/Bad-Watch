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
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
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
import com.badwatch.app.ui.components.displayName
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatHeartRate
import com.badwatch.app.ui.components.formatRestRatio
import com.badwatch.app.ui.components.hrZoneLabel
import com.badwatch.core.insight.Insight
import com.badwatch.core.sync.SessionExport
import java.util.Locale

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
    onDone: () -> Unit
) {
    val summary = stored.session.summary
    val rallies = stored.rallyProfile

    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = onDone, buttonSize = EdgeButtonSize.Medium) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Done")
            }
        }
    ) {
        item { ScreenHeader("Session saved") }

        item {
            StatRow(
                Stat("Hits", summary.totalShots.toString()),
                Stat("Bursts", rallies.rallyCount.toString()),
                Stat("Time", formatDuration(summary.durationMillis))
            )
        }

        items(insights.size) { index -> InsightCard(insight = insights[index]) }

        item {
            InfoCard(title = "Detected play") {
                DetailRow(
                    "Avg rally burst",
                    String.format(Locale.US, "%.1f hits", rallies.averageShotsPerRally)
                )
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendDot(color = MaterialTheme.colorScheme.primary, label = "Detected")
                        LegendDot(color = MaterialTheme.colorScheme.secondary, label = "Quiet")
                    }
                }
                DetailRow("Estimated active", "${(rallies.workDensity * 100).toInt()}%")
            }
        }

        item {
            InfoCard(title = "Heart rate") {
                DetailRow("Average", formatHeartRate(summary.averageHeartRate))
                DetailRow(
                    "Peak",
                    formatHeartRate(summary.maxHeartRate),
                    valueColor = MaterialTheme.colorScheme.error
                )
                DetailRow(
                    "Peak zone",
                    hrZoneLabel(summary.maxHeartRate, stored.profile.maxHeartRate)
                )
                if (summary.heartRateSampleCount > 0) {
                    DetailRow(
                        "Signal coverage",
                        "${(summary.heartRateCoverage * 100).toInt()}%"
                    )
                }
            }
        }

        if (summary.shotCounts.isNotEmpty()) {
            item {
                InfoCard(title = "Provisional stroke mix") {
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
                            LegendDot(color = type.color(), label = type.displayName())
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
    }
}

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

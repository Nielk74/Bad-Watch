package com.badwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.badwatch.app.ui.theme.SurfaceVariantColor
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A titled surface used for every grouped block on the watch. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceVariantColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = {
            title?.let {
                Text(
                    text = it.uppercase(Locale.US),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary
                )
            }
            content()
        }
    )
}

/** Label above, value below. The default readout for a single metric. */
@Composable
fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colors.onSurface
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            color = valueColor,
            textAlign = TextAlign.Center
        )
    }
}

/** Three metrics across the widest part of a round display. */
@Composable
fun MetricRow(
    first: Pair<String, String>,
    second: Pair<String, String>,
    third: Pair<String, String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricPill(first.first, first.second, Modifier.weight(1f))
        MetricPill(second.first, second.second, Modifier.weight(1f))
        MetricPill(third.first, third.second, Modifier.weight(1f))
    }
}

/** A left-label / right-value line, for dense detail lists. */
@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface
        )
    }
}

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

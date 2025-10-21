package com.badwatch.app.ui

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CardDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ExperimentalWearMaterialApi
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.badwatch.app.model.GyroReading
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.ui.theme.AccentCritical
import com.badwatch.app.ui.theme.AccentLuminous
import com.badwatch.app.ui.theme.AccentWarning
import com.badwatch.app.viewmodel.GyroUiState
import com.badwatch.app.viewmodel.GyroViewModel
import com.badwatch.app.viewmodel.IntensityZone
import com.badwatch.app.viewmodel.FocusArea
import com.badwatch.app.viewmodel.FocusSeverity
import com.badwatch.app.viewmodel.SessionInsight
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max

private const val MAX_MAGNITUDE = 8f

@Composable
fun GyroRoute(
    viewModel: GyroViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BadWatchTheme {
        GyroScreen(
            state = state,
            onStart = onStart,
            onStop = onStop,
            onToggleCapture = {
                if (viewModel.uiState.value.captureEnabled) {
                    viewModel.disableDatasetCapture()
                } else {
                    viewModel.enableDatasetCapture("session-${System.currentTimeMillis()}")
                }
            },
            onExportCapture = {
                val csv = viewModel.exportCaptureCsv()
                if (csv.isNotEmpty()) {
                    Log.d(
                        "MotionCapture",
                        "Captured ${viewModel.uiState.value.recordedSampleCount} samples for ${viewModel.uiState.value.captureLabel}"
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalWearMaterialApi::class, ExperimentalAnimationApi::class)
@Composable
fun GyroScreen(
    state: GyroUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onToggleCapture: () -> Unit,
    onExportCapture: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    LaunchedEffect(Unit) {
        listState.scrollToItem(0)
    }
    DynamicBackground(intensityZone = state.intensityZone) {
        Scaffold(
            timeText = { TimeText() },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 18.dp,
                    end = 18.dp,
                    top = 12.dp,
                    bottom = 28.dp
                ),
                state = listState
            ) {
                item {
                    MotionGaugeCard(
                        tracking = state.tracking,
                        magnitude = state.magnitude,
                        averageMagnitude = state.averageMagnitude,
                        peakMagnitude = state.peakMagnitude,
                        intensityZone = state.intensityZone
                    )
                }
                item {
                    TelemetryRow(
                        magnitude = state.magnitude,
                        averageMagnitude = state.averageMagnitude,
                        peakMagnitude = state.peakMagnitude
                    )
                }
                item {
                    SparklineCard(trail = state.magnitudeTrail)
                }
                item {
                    SessionStatusCard(
                        tracking = state.tracking,
                        sessionDurationMillis = state.sessionDurationMillis,
                        lastUpdatedMillis = state.lastUpdatedMillis,
                        latestReading = state.latestReading,
                        sampleCount = state.sampleCount
                    )
                }
                state.focusArea?.let { focus ->
                    item {
                        FocusAreaCard(focusArea = focus)
                    }
                }
                if (state.insights.isNotEmpty()) {
                    item {
                        InsightsCard(insights = state.insights)
                    }
                }
                item {
                    DataCaptureCard(
                        captureEnabled = state.captureEnabled,
                        captureLabel = state.captureLabel,
                        sampleCount = state.recordedSampleCount,
                        onToggleCapture = onToggleCapture,
                        onExport = onExportCapture
                    )
                }
                if (state.error != null) {
                    item {
                        ErrorCard(
                            message = state.error,
                            onRetry = onStart
                        )
                    }
                }
                item {
                    ActionButtons(
                        tracking = state.tracking,
                        onStart = onStart,
                        onStop = onStop
                    )
                }
            }
        }
    }
}

@Composable
private fun DynamicBackground(
    intensityZone: IntensityZone,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colors
    val gradientColors = gradientColorsForZone(intensityZone, colors)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .drawBehind {
                val gradient = Brush.verticalGradient(
                    colors = gradientColors,
                    startY = 0f,
                    endY = size.height
                )
                drawRect(brush = gradient, size = size)
            }
    ) {
        content()
    }
}

private fun gradientColorsForZone(
    zone: IntensityZone,
    colors: androidx.wear.compose.material.Colors
): List<Color> {
    val accent = colors.primary
    val luminous = AccentLuminous
    val secondary = colors.secondary
    val surface = colors.surface
    return when (zone) {
        IntensityZone.Idle -> listOf(
            luminous.copy(alpha = 0.18f),
            surface.copy(alpha = 0.6f),
            colors.background
        )
        IntensityZone.WarmUp -> listOf(
            accent.copy(alpha = 0.25f),
            surface.copy(alpha = 0.5f),
            colors.background
        )
        IntensityZone.Rally -> listOf(
            secondary.copy(alpha = 0.4f),
            accent.copy(alpha = 0.3f),
            colors.background
        )
        IntensityZone.Burst -> listOf(
            AccentCritical.copy(alpha = 0.5f),
            secondary.copy(alpha = 0.3f),
            colors.background
        )
    }
}

@Composable
private fun MotionGaugeCard(
    tracking: Boolean,
    magnitude: Float?,
    averageMagnitude: Float?,
    peakMagnitude: Float,
    intensityZone: IntensityZone
) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface,
            endBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MotionGauge(
                tracking = tracking,
                magnitude = magnitude,
                peakMagnitude = peakMagnitude
            )
            AnimatedContent(targetState = intensityZone, label = "intensity") { zone ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = zone.displayName(),
                        style = MaterialTheme.typography.title1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = zone.caption(averageMagnitude),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionGauge(
    tracking: Boolean,
    magnitude: Float?,
    peakMagnitude: Float
) {
    val magnitudeValue = magnitude ?: 0f
    val normalized = (magnitudeValue / MAX_MAGNITUDE).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = normalized,
        animationSpec = tween(durationMillis = 420),
        label = "progress"
    )
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val colors = MaterialTheme.colors
    val trackColor = colors.onSurface.copy(alpha = 0.1f)
    val pulseColor = colors.primary
    val gaugeBrush = remember(colors.primary, colors.secondary) {
        Brush.sweepGradient(
            0f to colors.primary,
            0.6f to colors.secondary,
            1f to colors.primary
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            val stroke = 12.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            drawCircle(
                color = trackColor,
                radius = radius,
                center = center,
                style = Stroke(width = stroke)
            )
            val arcRadius = radius
            val arcSize = Size(arcRadius * 2, arcRadius * 2)
            val startAngle = -210f
            val sweep = 240f * animatedProgress
            drawArc(
                brush = gaugeBrush,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
                size = arcSize,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            )
            if (tracking) {
                drawCircle(
                    color = pulseColor.copy(alpha = pulseAlpha),
                    radius = arcRadius,
                    center = center
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatMagnitude(magnitude),
                style = MaterialTheme.typography.display2
            )
            Text(
                text = "rad/s",
                style = MaterialTheme.typography.caption2,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = "Peak ${formatMagnitude(peakMagnitude)}",
                style = MaterialTheme.typography.caption2,
                color = colors.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun TelemetryRow(
    magnitude: Float?,
    averageMagnitude: Float?,
    peakMagnitude: Float
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MetricPill(
            modifier = Modifier.weight(1f),
            label = "Now",
            value = formatMagnitude(magnitude),
            accent = MaterialTheme.colors.primary
        )
        MetricPill(
            modifier = Modifier.weight(1f),
            label = "Avg",
            value = formatMagnitude(averageMagnitude),
            accent = MaterialTheme.colors.secondaryVariant
        )
        MetricPill(
            modifier = Modifier.weight(1f),
            label = "Peak",
            value = formatMagnitude(peakMagnitude),
            accent = MaterialTheme.colors.secondary
        )
    }
}

@Composable
private fun MetricPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.caption2,
            color = accent.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
        Text(
            text = value,
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SparklineCard(trail: List<Float>) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.8f),
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Motion Rhythm",
                style = MaterialTheme.typography.title3
            )
            Sparkline(trail = trail)
        }
    }
}

@Composable
private fun Sparkline(trail: List<Float>) {
    val points = if (trail.isEmpty()) listOf(0f, 0f) else trail
    val maxValue = max(points.maxOrNull() ?: 0f, 1f)
    val strokeColor = MaterialTheme.colors.primary
    val fillColor = strokeColor.copy(alpha = 0.18f)
    val backgroundColor = MaterialTheme.colors.background.copy(alpha = 0.4f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
    ) {
        val stepX = if (points.size <= 1) size.width else size.width / (points.size - 1)
        val linePath = Path()
        val fillPath = Path()
        points.forEachIndexed { index, value ->
            val x = index * stepX
            val normalizedY = if (maxValue == 0f) 0f else value / maxValue
            val y = size.height - (normalizedY * size.height)
            if (index == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((points.size - 1) * stepX, size.height)
        fillPath.close()

        drawPath(
            path = fillPath,
            color = fillColor
        )
        drawPath(
            path = linePath,
            color = strokeColor,
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SessionStatusCard(
    tracking: Boolean,
    sessionDurationMillis: Long,
    lastUpdatedMillis: Long?,
    latestReading: GyroReading?,
    sampleCount: Long
) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface,
            endBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (tracking) "Streaming" else "Paused",
                style = MaterialTheme.typography.body1
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusMiniStack(
                    label = "Duration",
                    value = formatDuration(sessionDurationMillis)
                )
                StatusMiniStack(
                    label = "Samples",
                    value = sampleCount.toString()
                )
                StatusMiniStack(
                    label = "Last",
                    value = lastUpdatedMillis?.let { formatTimestamp(it) } ?: "--"
                )
            }
            latestReading?.let {
                Text(
                    text = "Dominant ${axisSummary(it)}",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatusMiniStack(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FocusAreaCard(focusArea: FocusArea) {
    val badgeColor = focusArea.severity.badgeColor()
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = badgeColor.copy(alpha = 0.18f),
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = focusArea.title,
                    style = MaterialTheme.typography.title2
                )
                SeverityBadge(severity = focusArea.severity)
            }
            Text(
                text = focusArea.description,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SeverityBadge(severity: FocusSeverity) {
    val color = severity.badgeColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = severity.label(),
            style = MaterialTheme.typography.caption2,
            color = color
        )
    }
}

@Composable
private fun FocusSeverity.badgeColor(): Color = when (this) {
    FocusSeverity.Info -> MaterialTheme.colors.primary
    FocusSeverity.Caution -> AccentWarning
    FocusSeverity.Alert -> AccentCritical
}

private fun FocusSeverity.label(): String = when (this) {
    FocusSeverity.Info -> "INFO"
    FocusSeverity.Caution -> "FOCUS"
    FocusSeverity.Alert -> "ALERT"
}

@Composable
private fun InsightsCard(insights: List<SessionInsight>) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.85f),
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Session Insights",
                style = MaterialTheme.typography.title3
            )
            insights.take(3).forEach { insight ->
                InsightRow(insight = insight)
            }
        }
    }
}

@Composable
private fun InsightRow(insight: SessionInsight) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.body1
            )
            Text(
                text = insight.detail,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            InsightProgressBar(score = insight.score)
        }
        Text(
            text = insight.score.toString(),
            style = MaterialTheme.typography.title3
        )
    }
}

@Composable
private fun InsightProgressBar(score: Int) {
    val fraction = score.coerceIn(0, 100) / 100f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colors.onSurface.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(MaterialTheme.colors.primary)
        )
    }
}

@Composable
private fun DataCaptureCard(
    captureEnabled: Boolean,
    captureLabel: String?,
    sampleCount: Int,
    onToggleCapture: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.9f),
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Dataset Capture",
                style = MaterialTheme.typography.title3
            )
            Text(
                text = captureLabel ?: "No label assigned",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Text(
                text = "$sampleCount samples buffered",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = onToggleCapture,
                label = { Text(if (captureEnabled) "Stop capture" else "Start capture") },
                colors = if (captureEnabled) {
                    ChipDefaults.secondaryChipColors()
                } else {
                    ChipDefaults.primaryChipColors()
                }
            )
            CompactChip(
                onClick = onExport,
                label = { Text("Export CSV") },
                enabled = sampleCount > 0
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onRetry: () -> Unit
) {
    Card(
        onClick = {},
        enabled = false,
        modifier = Modifier.fillMaxWidth(),
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = MaterialTheme.colors.error.copy(alpha = 0.16f),
            endBackgroundColor = MaterialTheme.colors.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Sensor issue",
                style = MaterialTheme.typography.body1
            )
            Text(
                text = message,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
            )
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    contentColor = MaterialTheme.colors.onPrimary
                )
            ) {
                Text(text = "Retry")
            }
        }
    }
}

@Composable
private fun ActionButtons(
    tracking: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val backgroundColor = if (tracking) MaterialTheme.colors.secondary else MaterialTheme.colors.primary
        val contentColor = if (tracking) MaterialTheme.colors.onSecondary else MaterialTheme.colors.onPrimary
        Button(
            onClick = if (tracking) onStop else onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = backgroundColor,
                contentColor = contentColor
            ),
            shape = RoundedCornerShape(percent = 50)
        ) {
            Text(if (tracking) "Stop Session" else "Start Session")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.6f))
                .padding(vertical = 10.dp, horizontal = 12.dp)
        ) {
            Text(
                text = "Hold crown to bookmark a rally snapshot",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

private fun formatMagnitude(value: Float?): String =
    value?.let { String.format(Locale.US, "%.2f", it) } ?: "--"

private fun axisSummary(reading: GyroReading): String {
    val dominant = listOf(
        "X" to kotlin.math.abs(reading.x),
        "Y" to kotlin.math.abs(reading.y),
        "Z" to kotlin.math.abs(reading.z)
    ).maxByOrNull { it.second }?.first ?: "--"
    return "$dominant axis"
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00"
    val totalSeconds = durationMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun formatTimestamp(timestampMillis: Long): String {
    val localTime = Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
    return String.format(Locale.US, "%02d:%02d:%02d", localTime.hour, localTime.minute, localTime.second)
}

private fun IntensityZone.displayName(): String = when (this) {
    IntensityZone.Idle -> "Baseline"
    IntensityZone.WarmUp -> "Warm Up"
    IntensityZone.Rally -> "Rally Pace"
    IntensityZone.Burst -> "Burst Mode"
}

private fun IntensityZone.caption(averageMagnitude: Float?): String = when (this) {
    IntensityZone.Idle -> "Waiting for motion"
    IntensityZone.WarmUp -> "Building tempo"
    IntensityZone.Rally -> "Consistent exchanges"
    IntensityZone.Burst -> "Explosive wrist speed"
}.let { base ->
    averageMagnitude?.let { "$base · Avg ${formatMagnitude(it)}" } ?: base
}

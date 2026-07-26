package com.badwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.data.StoredSession
import com.badwatch.app.R
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.Sparkline
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatSessionDate
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.sync.effectiveMetrics
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.reviewedAnalysis
import java.util.concurrent.TimeUnit

/**
 * The ready screen.
 *
 * One job: get the player on court with a single tap. The primary action is an ordinary,
 * full-width button near the top because Wear edge buttons do not appear until the user has
 * scrolled to the end of a list. Training history and maintenance tools follow below it.
 */
@Composable
fun HomeScreen(
    viewModel: BadWatchViewModel,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSession: (StoredSession) -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    HomeContent(
        history = history,
        onStart = onStart,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onOpenCapture = onOpenCapture,
        onOpenMatch = onOpenMatch,
        onOpenTraining = onOpenTraining,
        onOpenProgress = onOpenProgress,
        onOpenSession = onOpenSession
    )
}

@Composable
private fun HomeContent(
    history: List<StoredSession>,
    onStart: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSession: (StoredSession) -> Unit
) {
    val last = latestUsableSession(history)

    WatchScreen(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { BrandHeader() }

        item {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 72.dp),
                label = {
                    Text(
                        text = stringResource(R.string.home_start_session),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                secondaryLabel = {
                    Text(
                        text = stringResource(R.string.home_start_session_subtitle),
                        style = MaterialTheme.typography.bodyExtraSmall
                    )
                },
                icon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.LargeIconSize)
                        )
                    }
                }
            )
        }

        item { ThisWeekCard(history) }

        item {
            TitleCard(
                onClick = onOpenTraining,
                title = { Text(stringResource(R.string.home_practice)) },
                time = { Text(stringResource(R.string.home_practice_count)) }
            ) {
                Text(
                    text = stringResource(R.string.home_practice_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            TitleCard(
                onClick = onOpenMatch,
                title = { Text(stringResource(R.string.home_score_match)) },
                time = { Text(stringResource(R.string.home_manual)) }
            ) {
                Text(
                    text = stringResource(R.string.home_match_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (last != null) {
            item {
                val effective = last.export.effectiveMetrics()
                val reviewed = last.export.reviewedAnalysis()
                TitleCard(
                    onClick = { onOpenSession(last) },
                    title = { Text(stringResource(R.string.home_last_session)) },
                    time = { Text(formatSessionDate(last.export.session.startedAtMillis)) }
                ) {
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
                            stringResource(R.string.label_exchanges),
                            reviewed.rallyProfile.rallyCount.toString()
                        ),
                        Stat(
                            stringResource(R.string.label_time),
                            formatDuration(reviewed.window.durationMillis)
                        )
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    space = 16.dp,
                    alignment = Alignment.CenterHorizontally
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickAction(
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = stringResource(R.string.home_history),
                    onClick = onOpenHistory
                )
                QuickAction(
                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                    label = stringResource(R.string.home_progress),
                    onClick = onOpenProgress
                )
                QuickAction(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = stringResource(R.string.home_settings),
                    onClick = onOpenSettings
                )
            }
        }

        // This trains the provisional classifier, so it belongs in a deliberately secondary
        // lab card rather than beside the everyday History and Settings destinations.
        item {
            TitleCard(
                onClick = onOpenCapture,
                title = { Text(stringResource(R.string.home_detection_lab)) },
                time = { Text(stringResource(R.string.home_optional)) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.home_detection_lab_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

/**
 * The home recap is a trusted summary surface. Keep unusable recordings available for
 * diagnosis in History, but never promote one as the player's latest meaningful session.
 * SessionStore already returns newest first, so the first usable item is the newest one.
 */
internal fun latestUsableSession(history: List<StoredSession>): StoredSession? =
    history.firstOrNull { it.export.context.recordingQuality != RecordingQuality.Unusable }

@Composable
private fun BrandHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = stringResource(R.string.brand_wordmark),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = stringResource(R.string.home_ready),
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun QuickAction(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = label }
        ) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Rolling seven-day activity, computed from sessions actually on the watch. Detected hits
 * and explicitly labelled estimated active time avoid implying whole-rally or court coverage.
 */
@Composable
private fun ThisWeekCard(history: List<StoredSession>) {
    val weekStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
    val usable = history.filter {
        it.export.context.recordingQuality != RecordingQuality.Unusable
    }
    val week = usable.filter { it.export.session.startedAtMillis >= weekStart }

    InfoCard(title = stringResource(R.string.home_this_week)) {
        if (week.isEmpty()) {
            Text(
                text = stringResource(R.string.home_first_recap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val totalShots = week.sumOf {
                it.export.effectiveMetrics().correctedDetectedHitCount
            }
            val hasCorrections = week.any { it.export.effectiveMetrics().hasCorrections }
            val estimatedActiveMillis = week.sumOf {
                it.export.reviewedAnalysis().rallyProfile.totalWorkMillis
            }
            StatRow(
                Stat(stringResource(R.string.label_sessions), week.size.toString()),
                Stat(
                    stringResource(
                        if (hasCorrections) R.string.label_reviewed_hits else R.string.label_detected_hits
                    ),
                    totalShots.toString()
                ),
                Stat(stringResource(R.string.home_estimated_active_short), formatDuration(estimatedActiveMillis))
            )
            val recentShots = usable.take(8).reversed()
                .map { it.export.effectiveMetrics().correctedDetectedHitCount.toFloat() }
            if (recentShots.size >= 2) {
                Spacer(modifier = Modifier.height(2.dp))
                Sparkline(
                    values = recentShots,
                    color = MaterialTheme.colorScheme.primary,
                    contentDescription = pluralStringResource(
                        R.plurals.home_hits_trend,
                        recentShots.size,
                        recentShots.size,
                        recentShots.first().toInt(),
                        recentShots.last().toInt()
                    )
                )
                DetailRow(
                    label = stringResource(R.string.home_recent_hits, recentShots.size),
                    value = ""
                )
            }
        }
    }
}

@Preview(widthDp = 240, heightDp = 240, showBackground = true)
@Composable
private fun EmptyHomePreview() {
    BadWatchTheme {
        HomeContent(
            history = emptyList(),
            onStart = {},
            onOpenHistory = {},
            onOpenSettings = {},
            onOpenCapture = {},
            onOpenMatch = {},
            onOpenTraining = {},
            onOpenProgress = {},
            onOpenSession = {}
        )
    }
}

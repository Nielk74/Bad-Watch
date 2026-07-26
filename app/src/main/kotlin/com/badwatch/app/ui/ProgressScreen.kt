package com.badwatch.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import com.badwatch.app.R
import com.badwatch.app.data.StoredSession
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.MeterRow
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.SelfReportedExperience
import com.badwatch.core.progress.PlayProfile
import com.badwatch.core.progress.PlayProfileBuilder
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.effectiveMetrics
import com.badwatch.core.sync.reviewedAnalysis
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Goals, personal records, and a context-specific history profile in one watch-sized view. */
@Composable
fun ProgressScreen(viewModel: BadWatchViewModel) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val sessionGoal by viewModel.weeklySessionGoal.collectAsStateWithLifecycle()
    val minuteGoal by viewModel.weeklyRecordedMinutesGoal.collectAsStateWithLifecycle()

    ProgressContent(
        history = history,
        experience = profile.experience,
        sessionGoal = sessionGoal,
        minuteGoal = minuteGoal,
        onGoalsChanged = viewModel::setWeeklyGoals
    )
}

@Composable
private fun ProgressContent(
    history: List<StoredSession>,
    experience: SelfReportedExperience,
    sessionGoal: Int,
    minuteGoal: Int,
    onGoalsChanged: (Int, Int) -> Unit
) {
    val nowMillis = System.currentTimeMillis()
    val usable = selectProgressUsableHistory(history, nowMillis)
    val recent = selectProgressRollingWeek(usable, nowMillis)
    val recentMinutes = progressObservedMillis(recent) / 60_000
    val playProfile = PlayProfileBuilder.build(usable.map { it.export })

    WatchScreen {
        item { ScreenHeader(stringResource(R.string.progress_title)) }

        item {
            InfoCard(title = stringResource(R.string.progress_last_seven_days)) {
                MeterRow(
                    label = stringResource(R.string.label_sessions),
                    fraction = recent.size.toFloat() / sessionGoal,
                    color = MaterialTheme.colorScheme.primary,
                    valueText = "${recent.size} / $sessionGoal"
                )
                MeterRow(
                    label = stringResource(R.string.progress_recorded_time),
                    fraction = recentMinutes.toFloat() / minuteGoal,
                    color = MaterialTheme.colorScheme.secondary,
                    valueText = stringResource(
                        R.string.progress_minutes_goal_value,
                        recentMinutes,
                        minuteGoal
                    )
                )
            }
        }

        item {
            val label = stringResource(R.string.progress_session_goal)
            InfoCard(title = label) {
                Stepper(
                    value = sessionGoal,
                    onValueChange = { onGoalsChanged(it, minuteGoal) },
                    valueProgression = 1..7,
                    decreaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_decrease, label)
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_increase, label)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.progress_sessions_seven_days, sessionGoal),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item {
            val label = stringResource(R.string.progress_time_goal)
            InfoCard(title = label) {
                Stepper(
                    value = minuteGoal,
                    onValueChange = { onGoalsChanged(sessionGoal, it) },
                    valueProgression = 30..600 step 30,
                    decreaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.a11y_decrease, label)
                        )
                    },
                    increaseIcon = {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.a11y_increase, label)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.progress_minutes_seven_days, minuteGoal),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        item {
            InfoCard(title = stringResource(R.string.progress_playing_background)) {
                DetailRow(
                    stringResource(R.string.progress_you_reported),
                    stringResource(experience.displayNameResource)
                )
                Text(
                    text = stringResource(R.string.progress_skill_caveat),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (playProfile) {
            is PlayProfile.Building -> item {
                InfoCard(title = stringResource(R.string.progress_play_pattern)) {
                    Text(
                        text = stringResource(
                            R.string.progress_building,
                            playProfile.comparableSessionCount,
                            playProfile.distinctDayCount
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.progress_tag_context),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is PlayProfile.Ready -> {
                item {
                    InfoCard(title = stringResource(R.string.progress_play_pattern)) {
                        DetailRow(
                            stringResource(R.string.progress_context),
                            playProfile.comparisonKey.displayName()
                        )
                        DetailRow(
                            stringResource(R.string.label_evidence),
                            stringResource(
                                R.string.progress_evidence_join,
                                pluralStringResource(
                                    R.plurals.common_sessions_count,
                                    playProfile.sessionCount,
                                    playProfile.sessionCount
                                ),
                                pluralStringResource(
                                    R.plurals.common_days_count,
                                    playProfile.distinctDayCount,
                                    playProfile.distinctDayCount
                                )
                            )
                        )
                        StatRow(
                            Stat(
                                stringResource(R.string.progress_hits_per_minute),
                                String.format(
                                    Locale.getDefault(),
                                    "%.1f",
                                    playProfile.medianDetectedHitsPerMinute
                                )
                            ),
                            Stat(
                                stringResource(R.string.progress_estimated_active_short),
                                "${(playProfile.medianEstimatedActiveShare * 100).toInt()}%"
                            ),
                            Stat(
                                stringResource(R.string.progress_hits_per_burst),
                                String.format(
                                    Locale.getDefault(),
                                    "%.1f",
                                    playProfile.medianHitsPerBurst
                                )
                            )
                        )
                        playProfile.medianHeartRateReservePercent?.let {
                            DetailRow(stringResource(R.string.progress_median_hr_reserve), "$it%")
                        }
                        playProfile.recentDetectedRateChangePercent?.let { change ->
                            val sign = if (change > 0) "+" else ""
                            DetailRow(
                                stringResource(R.string.progress_recent_hit_rate),
                                stringResource(R.string.progress_vs_prior_three, sign, change)
                            )
                        }
                        Text(
                            text = stringResource(R.string.progress_descriptive_caveat),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (usable.isNotEmpty()) {
            val mostHitsSession = usable.maxBy {
                it.export.effectiveMetrics().correctedDetectedHitCount
            }
            val mostHits = mostHitsSession.export.effectiveMetrics().correctedDetectedHitCount
            val longestBurst = usable.maxOf {
                it.export.reviewedAnalysis().rallyProfile.longestRally?.shotCount ?: 0
            }
            val longestRecording = progressLongestObservedMillis(usable)
            item {
                InfoCard(title = stringResource(R.string.progress_personal_records)) {
                    DetailRow(
                        stringResource(
                            if (mostHitsSession.export.effectiveMetrics().hasCorrections) {
                                R.string.label_reviewed_hits
                            } else {
                                R.string.label_detected_hits
                            }
                        ),
                        mostHits.toString()
                    )
                    DetailRow(
                        stringResource(R.string.progress_longest_inferred_burst),
                        pluralStringResource(
                            R.plurals.common_hits_count,
                            longestBurst,
                            longestBurst
                        )
                    )
                    DetailRow(
                        stringResource(R.string.progress_longest_recording),
                        formatDuration(longestRecording)
                    )
                    Text(
                        text = stringResource(R.string.progress_records_caveat),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Trusted Progress corpus. Future records stay inspectable in History but cannot set records. */
internal fun selectProgressUsableHistory(
    history: List<StoredSession>,
    nowMillis: Long
): List<StoredSession> = history.filter { stored ->
    stored.export.context.recordingQuality != RecordingQuality.Unusable &&
        stored.export.session.startedAtMillis <= nowMillis
}

/** Sessions eligible for Progress goals, including both rolling-window time boundaries. */
internal fun selectProgressRollingWeek(
    history: List<StoredSession>,
    nowMillis: Long
): List<StoredSession> {
    val sevenDaysAgo = nowMillis - TimeUnit.DAYS.toMillis(7)
    return selectProgressUsableHistory(history, nowMillis).filter { stored ->
        stored.export.session.startedAtMillis >= sevenDaysAgo
    }
}

/** Progress duration excludes immutable process gaps without dropping the session itself. */
internal fun progressObservedMillis(history: List<StoredSession>): Long =
    history.sumOf { it.export.observedEffectiveDurationMillis }

internal fun progressLongestObservedMillis(history: List<StoredSession>): Long =
    history.maxOfOrNull { it.export.observedEffectiveDurationMillis } ?: 0L

@Composable
private fun com.badwatch.core.sync.SessionComparisonKey.displayName(): String {
    val mode = stringResource(activityMode.displayNameResource)
    return comparisonTag?.let { "$mode · $it" } ?: mode
}

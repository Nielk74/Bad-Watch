package com.badwatch.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.annotation.StringRes
import com.badwatch.app.R
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.BodySide
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.ReportedSoreness
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionContext

/**
 * Optional, tap-only post-session diary.
 *
 * A watch keyboard after training is hostile UX, so the immediate review captures the few
 * structured facts that make history comparable: activity, perceived effort, soreness,
 * completion and recording coverage. Names, hall and prose notes remain part of the shared
 * schema and can be supplied through archive/dashboard tooling without blocking this flow.
 */
@Composable
fun SessionReviewScreen(
    existingContext: SessionContext,
    existingReport: PostSessionReport,
    onSave: (SessionContext, PostSessionReport) -> Unit,
    onSkip: () -> Unit,
    isSaving: Boolean = false,
    saveError: String? = null
) {
    var step by remember { mutableStateOf(ReviewStep.Activity) }
    var activityMode by remember { mutableStateOf(existingContext.activityMode) }
    var rpe by remember { mutableStateOf(existingReport.rpe) }
    var sorenessArea by remember { mutableStateOf<BodyArea?>(null) }
    var sorenessSeverity by remember { mutableStateOf<Int?>(null) }
    var sorenessDecision by remember {
        mutableStateOf<SorenessReviewDecision>(SorenessReviewDecision.Preserve)
    }
    var completion by remember { mutableStateOf(existingContext.completion) }

    fun save(recordingQuality: RecordingQuality) {
        onSave(
            existingContext.copy(
                activityMode = activityMode,
                completion = completion,
                recordingQuality = recordingQuality,
                diaryReviewStatus = DiaryReviewStatus.Reviewed
            ),
            existingReport.copy(rpe = rpe).applySorenessDecision(sorenessDecision)
        )
    }

    BackHandler(enabled = !isSaving) {
        step = when (step) {
            ReviewStep.Activity -> {
                onSkip()
                ReviewStep.Activity
            }
            ReviewStep.Effort -> ReviewStep.Activity
            ReviewStep.Soreness -> ReviewStep.Effort
            ReviewStep.SorenessSeverity -> ReviewStep.Soreness
            ReviewStep.Completion -> ReviewStep.Soreness
            ReviewStep.Recording -> ReviewStep.Completion
        }
    }

    WatchScreen(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenHeader(
                when (step) {
                    ReviewStep.Activity -> stringResource(R.string.review_activity_question)
                    ReviewStep.Effort -> stringResource(R.string.review_effort_question)
                    ReviewStep.Soreness -> stringResource(R.string.review_soreness_question)
                    ReviewStep.SorenessSeverity -> stringResource(R.string.review_severity_question)
                    ReviewStep.Completion -> stringResource(R.string.review_completion_question)
                    ReviewStep.Recording -> stringResource(R.string.review_recording_question)
                }
            )
        }

        if (isSaving || saveError != null) {
            item {
                InfoCard {
                    Text(
                        text = saveError ?: stringResource(R.string.review_saving),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (saveError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        item {
            Text(
                text = when (step) {
                    ReviewStep.Activity -> stringResource(R.string.review_progress_optional, 1)
                    ReviewStep.Effort -> stringResource(R.string.review_progress_optional, 2)
                    ReviewStep.Soreness -> stringResource(R.string.review_progress_optional, 3)
                    ReviewStep.SorenessSeverity -> stringResource(R.string.review_progress_soreness)
                    ReviewStep.Completion -> stringResource(R.string.review_progress_optional, 4)
                    ReviewStep.Recording -> stringResource(R.string.review_progress_final)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        when (step) {
            ReviewStep.Activity -> {
                item {
                    InfoCard {
                        Text(
                            stringResource(R.string.review_activity_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(ACTIVITY_CHOICES.size) { index ->
                    val choice = ACTIVITY_CHOICES[index]
                    ReviewChoice(
                        stringResource(choice.mode.displayNameResource),
                        stringResource(choice.detailResource)
                    ) {
                        activityMode = choice.mode
                        step = ReviewStep.Effort
                    }
                }
                item {
                    CompactButton(
                        onClick = onSkip,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        label = { Text(stringResource(R.string.review_skip_diary)) }
                    )
                }
            }

            ReviewStep.Effort -> {
                item {
                    InfoCard {
                        Text(
                            stringResource(R.string.review_effort_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(EFFORT_CHOICES.size) { index ->
                    val choice = EFFORT_CHOICES[index]
                    ReviewChoice(
                        stringResource(choice.labelResource),
                        stringResource(R.string.review_rpe_detail, choice.value)
                    ) {
                        rpe = choice.value
                        step = ReviewStep.Soreness
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.review_not_sure),
                        stringResource(R.string.review_effort_unreported)
                    ) {
                        rpe = null
                        step = ReviewStep.Soreness
                    }
                }
            }

            ReviewStep.Soreness -> {
                item {
                    ReviewChoice(
                        stringResource(R.string.review_nothing_log),
                        stringResource(R.string.review_explicitly_reviewed)
                    ) {
                        sorenessArea = null
                        sorenessSeverity = null
                        sorenessDecision = SorenessReviewDecision.Clear
                        step = ReviewStep.Completion
                    }
                }
                items(SORENESS_CHOICES.size) { index ->
                    val choice = SORENESS_CHOICES[index]
                    ReviewChoice(
                        stringResource(choice.labelResource),
                        stringResource(R.string.review_soreness_caveat)
                    ) {
                        sorenessArea = choice.area
                        step = ReviewStep.SorenessSeverity
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.review_skip_question),
                        stringResource(R.string.review_no_soreness_conclusion)
                    ) {
                        sorenessArea = null
                        sorenessSeverity = null
                        sorenessDecision = SorenessReviewDecision.Preserve
                        step = ReviewStep.Completion
                    }
                }
            }

            ReviewStep.SorenessSeverity -> {
                items(SORENESS_SEVERITIES.size) { index ->
                    val choice = SORENESS_SEVERITIES[index]
                    ReviewChoice(
                        stringResource(choice.labelResource),
                        stringResource(R.string.review_severity_detail, choice.value)
                    ) {
                        sorenessSeverity = choice.value
                        sorenessDecision = SorenessReviewDecision.AddOrReplace(
                            ReportedSoreness(
                                bodyArea = requireNotNull(sorenessArea),
                                severity = choice.value,
                                side = BodySide.Unspecified
                            )
                        )
                        step = ReviewStep.Completion
                    }
                }
            }

            ReviewStep.Completion -> {
                item {
                    ReviewChoice(
                        stringResource(R.string.completion_completed),
                        stringResource(R.string.review_completion_completed_detail)
                    ) {
                        completion = SessionCompletion.Completed
                        step = ReviewStep.Recording
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.completion_stopped_early),
                        stringResource(R.string.review_completion_early_detail)
                    ) {
                        completion = SessionCompletion.StoppedEarly
                        step = ReviewStep.Recording
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.review_not_sure),
                        stringResource(R.string.review_completion_unreported)
                    ) {
                        completion = SessionCompletion.Unreported
                        step = ReviewStep.Recording
                    }
                }
            }

            ReviewStep.Recording -> {
                item {
                    InfoCard {
                        Text(
                            stringResource(R.string.review_recording_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.review_recording_complete),
                        stringResource(R.string.review_recording_complete_detail),
                        enabled = !isSaving
                    ) {
                        save(RecordingQuality.Complete)
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.review_recording_partial),
                        stringResource(R.string.review_recording_partial_detail),
                        enabled = !isSaving
                    ) {
                        save(RecordingQuality.Partial)
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.recording_unusable),
                        stringResource(R.string.review_recording_unusable_detail),
                        enabled = !isSaving
                    ) {
                        save(RecordingQuality.Unusable)
                    }
                }
                item {
                    ReviewChoice(
                        stringResource(R.string.recording_not_reviewed),
                        stringResource(R.string.review_recording_unreviewed_detail),
                        enabled = !isSaving
                    ) {
                        save(existingContext.recordingQuality)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewChoice(
    label: String,
    detail: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TitleCard(
        onClick = onClick,
        enabled = enabled,
        title = { Text(label) }
    ) {
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyExtraSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class ReviewStep { Activity, Effort, Soreness, SorenessSeverity, Completion, Recording }

private data class ActivityChoice(
    val mode: ActivityMode,
    @param:StringRes val detailResource: Int
)

private val ACTIVITY_CHOICES = listOf(
    ActivityChoice(ActivityMode.FreePlay, R.string.review_activity_free_play_detail),
    ActivityChoice(ActivityMode.SinglesMatch, R.string.review_activity_singles_detail),
    ActivityChoice(ActivityMode.DoublesMatch, R.string.review_activity_doubles_detail),
    ActivityChoice(ActivityMode.Drill, R.string.review_activity_drill_detail),
    ActivityChoice(ActivityMode.Shadow, R.string.review_activity_shadow_detail),
    ActivityChoice(ActivityMode.Conditioning, R.string.review_activity_conditioning_detail)
)

private data class ScaleChoice(val value: Int, @param:StringRes val labelResource: Int)

private val EFFORT_CHOICES = listOf(
    ScaleChoice(2, R.string.review_effort_easy),
    ScaleChoice(4, R.string.review_effort_moderate),
    ScaleChoice(6, R.string.review_effort_hard),
    ScaleChoice(8, R.string.review_effort_very_hard),
    ScaleChoice(10, R.string.review_effort_maximum)
)

private data class SorenessChoice(
    val area: BodyArea,
    @param:StringRes val labelResource: Int
)

private val SORENESS_CHOICES = listOf(
    SorenessChoice(BodyArea.Shoulder, R.string.body_area_shoulder),
    SorenessChoice(BodyArea.Wrist, R.string.review_soreness_wrist_hand),
    SorenessChoice(BodyArea.LowerBack, R.string.body_area_lower_back),
    SorenessChoice(BodyArea.Knee, R.string.body_area_knee),
    SorenessChoice(BodyArea.Ankle, R.string.review_soreness_ankle_achilles),
    SorenessChoice(BodyArea.General, R.string.body_area_general)
)

private val SORENESS_SEVERITIES = listOf(
    ScaleChoice(1, R.string.review_severity_barely),
    ScaleChoice(3, R.string.review_severity_mild),
    ScaleChoice(5, R.string.review_severity_moderate),
    ScaleChoice(7, R.string.review_severity_strong),
    ScaleChoice(9, R.string.review_severity_severe)
)

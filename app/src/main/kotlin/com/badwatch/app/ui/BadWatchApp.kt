package com.badwatch.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import com.badwatch.app.R
import com.badwatch.app.data.StoredSession
import com.badwatch.app.domain.CaptureFailureRecovery
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.domain.MatchControllerState
import com.badwatch.app.domain.SessionState
import com.badwatch.app.domain.ShadowControllerState
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.DiaryReviewStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Root of the watch UI.
 *
 * Screen selection is driven primarily by [SessionState] rather than by a navigation stack:
 * during play there is exactly one screen that matters, and the app lands on it whenever the
 * player raises their wrist. Local navigation (history, settings, drills) only exists while
 * nothing is recording. Transitions are short crossfades keyed on the *kind* of screen, so
 * live data updates never re-trigger animation.
 */
@Composable
fun BadWatchApp(
    viewModel: BadWatchViewModel,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onDiscardSession: () -> Unit,
    onAcknowledgeSessionFailure: () -> Unit,
    onStartCapture: (ShotType) -> Unit,
    onFinishCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    onRetryCaptureSave: () -> Unit,
    isAmbient: StateFlow<Boolean> = MutableStateFlow(false),
    ambientTimeMillis: StateFlow<Long> = MutableStateFlow(System.currentTimeMillis())
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val matchState by viewModel.matchState.collectAsStateWithLifecycle()
    val shadowRoutineState by viewModel.shadowRoutineState.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val ambient by isAmbient.collectAsStateWithLifecycle()
    val ambientNowMillis by ambientTimeMillis.collectAsStateWithLifecycle()
    val detectedHitHaptics by viewModel.detectedHitHaptics.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Home) }
    var detailSession by remember { mutableStateOf<StoredSession?>(null) }
    val captureFailure = captureState as? CaptureState.Failed

    val matchOwnsScreen = (matchState is MatchControllerState.Active ||
        matchState is MatchControllerState.Failed) &&
        sessionState is SessionState.Idle && captureState is CaptureState.Idle
    val trainingOwnsScreen = (shadowRoutineState is ShadowControllerState.Active ||
        shadowRoutineState is ShadowControllerState.Failed) &&
        matchState is MatchControllerState.Idle &&
        sessionState is SessionState.Idle && captureState is CaptureState.Idle

    BackHandler(
        enabled = !matchOwnsScreen && !trainingOwnsScreen && (screen != Screen.Home ||
            sessionState is SessionState.Completed ||
            sessionState is SessionState.Failed ||
            captureState is CaptureState.Saved ||
            captureState is CaptureState.Failed)
    ) {
        when {
            sessionState is SessionState.Completed -> viewModel.acknowledge()

            sessionState is SessionState.Failed -> onAcknowledgeSessionFailure()

            captureState is CaptureState.Saved -> viewModel.acknowledgeCapture()

            captureFailure != null -> when (captureFailure.recovery) {
                CaptureFailureRecovery.CancelCapture -> onCancelCapture()
                CaptureFailureRecovery.RetrySave -> onRetryCaptureSave()
            }

            screen == Screen.SessionDetail -> screen = Screen.History
            else -> screen = Screen.Home
        }
    }

    // Some players want immediate detector feedback and others find any in-rally cue
    // distracting. It is therefore opt-in and can be changed without restarting collection.
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled by rememberUpdatedState(detectedHitHaptics)
    LaunchedEffect(Unit) {
        viewModel.shots.collect {
            if (hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }

    val frame = resolveScreenFrame(
        onboarded = onboarded,
        sessionState = sessionState,
        captureState = captureState,
        matchState = matchState,
        shadowRoutineState = shadowRoutineState,
        screen = screen,
        detailSession = detailSession
    )

    BadWatchTheme {
        AppScaffold {
            AnimatedContent(
                targetState = frame,
                // The frame carries fresh live data, while the key limits animation to
                // actual navigation. This lets a 100 Hz recording update recompose the HUD
                // without restarting its transition and lets the outgoing screen keep the
                // exact payload it had when navigation began.
                contentKey = ScreenFrame::kind,
                transitionSpec = {
                    fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "screen"
            ) { target ->
                when (target) {
                    ScreenFrame.Loading -> LoadingScreen()

                    ScreenFrame.Onboarding -> OnboardingScreen(
                        onConfirm = { handedness -> viewModel.completeOnboarding(handedness) }
                    )

                    is ScreenFrame.Live -> {
                        LiveScreen(
                            state = target.state,
                            onStop = onStopSession,
                            onDiscard = onDiscardSession,
                            isAmbient = ambient,
                            ambientTimeMillis = ambientNowMillis
                        )
                    }

                    is ScreenFrame.Recap -> {
                        var recapPage by remember(target.state.export.session.id) {
                            mutableStateOf(
                                if (target.state.export.context.diaryReviewStatus ==
                                    DiaryReviewStatus.Unreviewed
                                ) {
                                    RecapPage.Review
                                } else {
                                    RecapPage.Summary
                                }
                            )
                        }
                        var saveInProgress by remember(target.state.export.session.id) {
                            mutableStateOf(false)
                        }
                        var saveError by remember(target.state.export.session.id) {
                            mutableStateOf<String?>(null)
                        }
                        val diarySaveFailed = stringResource(R.string.review_save_failed)
                        val correctionSaveFailed = stringResource(R.string.correction_save_failed)
                        when (recapPage) {
                            RecapPage.Review -> SessionReviewScreen(
                                existingContext = target.state.export.context,
                                existingReport = target.state.export.report,
                                onSave = { context, report ->
                                    if (!saveInProgress) {
                                        saveInProgress = true
                                        saveError = null
                                        viewModel.saveCompletedSessionReview(
                                            context = context,
                                            report = report
                                        ) { result ->
                                            saveInProgress = false
                                            result.onSuccess {
                                                recapPage = RecapPage.Summary
                                            }.onFailure {
                                                saveError = diarySaveFailed
                                            }
                                        }
                                    }
                                },
                                onSkip = {
                                    if (!saveInProgress) {
                                        saveInProgress = true
                                        saveError = null
                                        viewModel.skipCompletedSessionReview { result ->
                                            saveInProgress = false
                                            result.onSuccess {
                                                recapPage = RecapPage.Summary
                                            }.onFailure {
                                                saveError = diarySaveFailed
                                            }
                                        }
                                    }
                                },
                                isSaving = saveInProgress,
                                saveError = saveError
                            )

                            RecapPage.Summary -> SummaryScreen(
                                stored = target.state.export,
                                insights = target.state.insights,
                                onDone = {
                                    viewModel.acknowledge()
                                    screen = Screen.Home
                                },
                                onEditDiary = {
                                    saveError = null
                                    recapPage = RecapPage.Review
                                },
                                onCorrectRecording = {
                                    saveError = null
                                    recapPage = RecapPage.Corrections
                                }
                            )

                            RecapPage.Corrections -> SessionCorrectionScreen(
                                export = target.state.export,
                                onSave = { falseHits, missedHits, trimStart, trimEnd ->
                                    if (!saveInProgress) {
                                        saveInProgress = true
                                        saveError = null
                                        viewModel.saveCompletedSessionCorrections(
                                            falseHitIds = falseHits,
                                            missedHitCount = missedHits,
                                            trimFromStartMillis = trimStart,
                                            trimFromEndMillis = trimEnd
                                        ) { result ->
                                            saveInProgress = false
                                            result.onSuccess {
                                                recapPage = RecapPage.Summary
                                            }.onFailure {
                                                saveError = correctionSaveFailed
                                            }
                                        }
                                    }
                                },
                                onBack = { recapPage = RecapPage.Summary },
                                isSaving = saveInProgress,
                                saveError = saveError
                            )
                        }
                    }

                    is ScreenFrame.Drill -> {
                        CaptureScreen(
                            state = target.state,
                            onDiscardLast = viewModel::discardLastSwing,
                            onFinish = onFinishCapture,
                            onCancel = {
                                onCancelCapture()
                                screen = Screen.Home
                            }
                        )
                    }

                    is ScreenFrame.DrillSaved -> {
                        CaptureSavedScreen(
                            export = target.state.export,
                            onDone = {
                                viewModel.acknowledgeCapture()
                                screen = Screen.Home
                            }
                        )
                    }

                    ScreenFrame.CapturePicker -> CapturePickerScreen(
                        totalSwings = viewModel.labelledSwingCount
                            .collectAsStateWithLifecycle().value,
                        onPick = onStartCapture,
                        onBack = { screen = Screen.Home }
                    )

                    is ScreenFrame.SessionFailed -> {
                        ErrorScreen(
                            message = target.state.message,
                            onDismiss = onAcknowledgeSessionFailure,
                            // The failed collector may still own a durable journal. Route the
                            // destructive choice through SessionService so recorder, optical HR,
                            // foreground state, and the checkpoint are cleared as one command.
                            onDiscardRecovery = onDiscardSession
                        )
                    }

                    is ScreenFrame.CaptureFailed -> {
                        val retrySave = target.state.recovery == CaptureFailureRecovery.RetrySave
                        ErrorScreen(
                            message = target.state.message,
                            onDismiss = if (retrySave) onRetryCaptureSave else onCancelCapture,
                            titleResource = R.string.error_capture_stopped,
                            primaryActionResource = if (retrySave) {
                                R.string.action_retry_save
                            } else {
                                R.string.action_discard_drill
                            },
                            confirmPrimaryAction = !retrySave,
                            discardQuestionResource = R.string.error_capture_discard_question,
                            discardBodyResource = R.string.error_capture_discard_body
                        )
                    }

                    is ScreenFrame.Match -> MatchScreen(
                        controllerState = target.state,
                        onStart = viewModel::startMatch,
                        onAwardPoint = viewModel::awardMatchPoint,
                        onUndo = viewModel::undoMatchPoint,
                        onAcknowledgePrompt = viewModel::acknowledgeMatchPrompt,
                        onClear = {
                            viewModel.clearMatch()
                            screen = Screen.Home
                        },
                        isAmbient = ambient
                    )

                    is ScreenFrame.Training -> TrainingScreen(
                        controllerState = target.state,
                        onStartShadow = viewModel::startShadowRoutine,
                        onConfirmShadow = viewModel::confirmShadowRepetition,
                        onPauseShadow = viewModel::pauseShadowRoutine,
                        onResumeShadow = viewModel::resumeShadowRoutine,
                        onFinishShadowEarly = viewModel::finishShadowRoutineEarly,
                        onClearShadow = {
                            viewModel.clearShadowRoutine()
                            screen = Screen.Training
                        },
                        isAmbient = ambient
                    )

                    ScreenFrame.History -> HistoryScreen(
                        viewModel = viewModel,
                        onOpenSession = { stored ->
                            detailSession = stored
                            screen = Screen.SessionDetail
                        },
                        onBack = { screen = Screen.Home }
                    )

                    ScreenFrame.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onOpenDashboard = { screen = Screen.Dashboard },
                        onBack = { screen = Screen.Home }
                    )

                    ScreenFrame.Dashboard -> DashboardSetupScreen(
                        viewModel = viewModel,
                        onBack = { screen = Screen.Settings }
                    )

                    ScreenFrame.Progress -> ProgressScreen(
                        viewModel = viewModel,
                        onBack = { screen = Screen.Home }
                    )

                    is ScreenFrame.SessionDetail -> {
                        var detailPage by remember(target.session.export.session.id) {
                            mutableStateOf(RecapPage.Summary)
                        }
                        var saveInProgress by remember(target.session.export.session.id) {
                            mutableStateOf(false)
                        }
                        var saveError by remember(target.session.export.session.id) {
                            mutableStateOf<String?>(null)
                        }
                        val diarySaveFailed = stringResource(R.string.review_save_failed)
                        val correctionSaveFailed = stringResource(R.string.correction_save_failed)
                        when (detailPage) {
                            RecapPage.Review -> SessionReviewScreen(
                                existingContext = target.session.export.context,
                                existingReport = target.session.export.report,
                                onSave = { context, report ->
                                    if (!saveInProgress) {
                                        saveInProgress = true
                                        saveError = null
                                        viewModel.saveStoredSessionReview(
                                            export = target.session.export,
                                            context = context,
                                            report = report
                                        ) { result ->
                                            saveInProgress = false
                                            result.onSuccess { revised ->
                                                detailSession = target.session.copy(
                                                    export = revised,
                                                    synced = false,
                                                    syncRejection = null,
                                                    syncPayloadFingerprint = ""
                                                )
                                                detailPage = RecapPage.Summary
                                            }.onFailure {
                                                saveError = diarySaveFailed
                                            }
                                        }
                                    }
                                },
                                onSkip = { detailPage = RecapPage.Summary },
                                isSaving = saveInProgress,
                                saveError = saveError
                            )

                            RecapPage.Summary -> SummaryScreen(
                                stored = target.session.export,
                                insights = viewModel.storedSessionInsights(target.session.export),
                                onDone = { screen = Screen.History },
                                onEditDiary = {
                                    saveError = null
                                    detailPage = RecapPage.Review
                                },
                                onCorrectRecording = {
                                    saveError = null
                                    detailPage = RecapPage.Corrections
                                }
                            )

                            RecapPage.Corrections -> SessionCorrectionScreen(
                                export = target.session.export,
                                onSave = { falseHits, missedHits, trimStart, trimEnd ->
                                    if (!saveInProgress) {
                                        saveInProgress = true
                                        saveError = null
                                        viewModel.saveStoredSessionCorrections(
                                            export = target.session.export,
                                            falseHitIds = falseHits,
                                            missedHitCount = missedHits,
                                            trimFromStartMillis = trimStart,
                                            trimFromEndMillis = trimEnd
                                        ) { result ->
                                            saveInProgress = false
                                            result.onSuccess { revised ->
                                                detailSession = target.session.copy(
                                                    export = revised,
                                                    synced = false,
                                                    syncRejection = null,
                                                    syncPayloadFingerprint = ""
                                                )
                                                detailPage = RecapPage.Summary
                                            }.onFailure {
                                                saveError = correctionSaveFailed
                                            }
                                        }
                                    }
                                },
                                onBack = { detailPage = RecapPage.Summary },
                                isSaving = saveInProgress,
                                saveError = saveError
                            )
                        }
                    }

                    ScreenFrame.Home -> HomeScreen(
                        viewModel = viewModel,
                        onStart = onStartSession,
                        onOpenHistory = { screen = Screen.History },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenCapture = { screen = Screen.Capture },
                        onOpenMatch = { screen = Screen.Match },
                        onOpenTraining = { screen = Screen.Training },
                        onOpenProgress = { screen = Screen.Progress },
                        onOpenSession = { stored ->
                            detailSession = stored
                            screen = Screen.SessionDetail
                        }
                    )
                }
            }
        }
    }
}

private enum class Screen {
    Home, History, Settings, Dashboard, Progress, Capture, Match, Training, SessionDetail
}

private enum class RecapPage { Review, Summary, Corrections }

private sealed interface ScreenFrame {
    val kind: ScreenKind

    data object Loading : ScreenFrame { override val kind = ScreenKind.Loading }
    data object Onboarding : ScreenFrame { override val kind = ScreenKind.Onboarding }
    data class Live(val state: SessionState.Recording) : ScreenFrame {
        override val kind = ScreenKind.Live
    }
    data class Recap(val state: SessionState.Completed) : ScreenFrame {
        override val kind = ScreenKind.Recap
    }
    data class Drill(val state: CaptureState.Capturing) : ScreenFrame {
        override val kind = ScreenKind.Drill
    }
    data class DrillSaved(val state: CaptureState.Saved) : ScreenFrame {
        override val kind = ScreenKind.DrillSaved
    }
    data object CapturePicker : ScreenFrame { override val kind = ScreenKind.CapturePicker }
    data class SessionFailed(val state: SessionState.Failed) : ScreenFrame {
        override val kind = ScreenKind.SessionFailed
    }
    data class CaptureFailed(val state: CaptureState.Failed) : ScreenFrame {
        override val kind = ScreenKind.CaptureFailed
    }
    data class Match(val state: MatchControllerState) : ScreenFrame {
        override val kind = ScreenKind.Match
    }
    data class Training(val state: ShadowControllerState) : ScreenFrame {
        override val kind = ScreenKind.Training
    }
    data object History : ScreenFrame { override val kind = ScreenKind.History }
    data object Settings : ScreenFrame { override val kind = ScreenKind.Settings }
    data object Dashboard : ScreenFrame { override val kind = ScreenKind.Dashboard }
    data object Progress : ScreenFrame { override val kind = ScreenKind.Progress }
    data class SessionDetail(val session: StoredSession) : ScreenFrame {
        override val kind = ScreenKind.SessionDetail
    }
    data object Home : ScreenFrame { override val kind = ScreenKind.Home }
}

private fun resolveScreenFrame(
    onboarded: Boolean?,
    sessionState: SessionState,
    captureState: CaptureState,
    matchState: MatchControllerState,
    shadowRoutineState: ShadowControllerState,
    screen: Screen,
    detailSession: StoredSession?
): ScreenFrame = when {
    onboarded == null -> ScreenFrame.Loading
    onboarded == false -> ScreenFrame.Onboarding
    sessionState is SessionState.Recording -> ScreenFrame.Live(sessionState)
    sessionState is SessionState.Completed -> ScreenFrame.Recap(sessionState)
    captureState is CaptureState.Capturing -> ScreenFrame.Drill(captureState)
    captureState is CaptureState.Saved -> ScreenFrame.DrillSaved(captureState)
    sessionState is SessionState.Failed -> ScreenFrame.SessionFailed(sessionState)
    captureState is CaptureState.Failed -> ScreenFrame.CaptureFailed(captureState)
    matchState is MatchControllerState.Active || matchState is MatchControllerState.Failed ->
        ScreenFrame.Match(matchState)
    shadowRoutineState is ShadowControllerState.Active ||
        shadowRoutineState is ShadowControllerState.Failed ->
        ScreenFrame.Training(shadowRoutineState)
    screen == Screen.Capture -> ScreenFrame.CapturePicker
    screen == Screen.Match -> ScreenFrame.Match(matchState)
    screen == Screen.Training -> ScreenFrame.Training(shadowRoutineState)
    screen == Screen.History -> ScreenFrame.History
    screen == Screen.Settings -> ScreenFrame.Settings
    screen == Screen.Dashboard -> ScreenFrame.Dashboard
    screen == Screen.Progress -> ScreenFrame.Progress
    screen == Screen.SessionDetail && detailSession != null -> ScreenFrame.SessionDetail(detailSession)
    else -> ScreenFrame.Home
}

private enum class ScreenKind {
    Loading, Onboarding, Live, Recap, Drill, DrillSaved,
    CapturePicker, SessionFailed, CaptureFailed, Match, Training, History, Settings, Dashboard, Progress,
    SessionDetail, Home
}

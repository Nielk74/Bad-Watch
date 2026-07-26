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
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.badwatch.app.R
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
 * player raises their wrist. Idle-time browsing (history, settings, drills, match and training
 * setup) lives in a [SwipeDismissableNavHost] with the platform swipe-to-dismiss gesture, kept
 * underneath the state-owned frames so an active recording, drill, match, or routine always wins
 * the screen and the back stack survives it. Transitions between a state frame and the nav host
 * are short crossfades keyed on the *kind* of frame, so live data updates never re-trigger
 * animation.
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
    val navController = rememberSwipeDismissableNavController()
    val captureFailure = captureState as? CaptureState.Failed

    val matchOwnsScreen = (matchState is MatchControllerState.Active ||
        matchState is MatchControllerState.Failed) &&
        sessionState is SessionState.Idle && captureState is CaptureState.Idle
    val trainingOwnsScreen = (shadowRoutineState is ShadowControllerState.Active ||
        shadowRoutineState is ShadowControllerState.Failed) &&
        matchState is MatchControllerState.Idle &&
        sessionState is SessionState.Idle && captureState is CaptureState.Idle

    // Only transient end states are handled here; idle-time browsing back is the nav host's
    // back stack via swipe-to-dismiss, and while match/training owns the screen back dismisses
    // to the watch face exactly as before.
    BackHandler(
        enabled = !matchOwnsScreen && !trainingOwnsScreen && (
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
        shadowRoutineState = shadowRoutineState
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
                                    navController.popBackStackOrNavigate(Routes.HOME)
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
                                navController.popBackStackOrNavigate(Routes.HOME)
                            }
                        )
                    }

                    is ScreenFrame.DrillSaved -> {
                        CaptureSavedScreen(
                            export = target.state.export,
                            onDone = {
                                viewModel.acknowledgeCapture()
                                navController.popBackStackOrNavigate(Routes.HOME)
                            }
                        )
                    }

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
                            navController.popBackStackOrNavigate(Routes.HOME)
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
                            navController.popBackStackOrNavigate(Routes.TRAINING)
                        },
                        isAmbient = ambient
                    )

                    ScreenFrame.Local -> LocalNavHost(
                        navController = navController,
                        viewModel = viewModel,
                        onStartSession = onStartSession,
                        onStartCapture = onStartCapture,
                        ambient = ambient
                    )
                }
            }
        }
    }
}

/** Idle-time destinations reachable from Home; back is the platform swipe-to-dismiss gesture. */
private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DASHBOARD = "dashboard"
    const val PROGRESS = "progress"
    const val CAPTURE = "capture"
    const val MATCH = "match"
    const val TRAINING = "training"
    const val SESSION = "session/{sessionId}"

    fun session(sessionId: String) = "session/$sessionId"
}

/**
 * Pops to [route] when it is on the back stack, otherwise navigates there. Tiles can start a
 * session, match, or routine without the destination ever having been pushed, so "return to the
 * hub" cannot assume the entry is present.
 */
private fun NavHostController.popBackStackOrNavigate(route: String) {
    if (!popBackStack(route, inclusive = false)) {
        navigate(route) { launchSingleTop = true }
    }
}

@Composable
private fun LocalNavHost(
    navController: NavHostController,
    viewModel: BadWatchViewModel,
    onStartSession: () -> Unit,
    onStartCapture: (ShotType) -> Unit,
    ambient: Boolean
) {
    val matchState by viewModel.matchState.collectAsStateWithLifecycle()
    val shadowRoutineState by viewModel.shadowRoutineState.collectAsStateWithLifecycle()
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onStart = onStartSession,
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenCapture = { navController.navigate(Routes.CAPTURE) },
                onOpenMatch = { navController.navigate(Routes.MATCH) },
                onOpenTraining = { navController.navigate(Routes.TRAINING) },
                onOpenProgress = { navController.navigate(Routes.PROGRESS) },
                onOpenSession = { stored ->
                    navController.navigate(Routes.session(stored.export.session.id))
                }
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                viewModel = viewModel,
                onOpenSession = { stored ->
                    navController.navigate(Routes.session(stored.export.session.id))
                }
            )
        }

        composable(Routes.SESSION) { entry ->
            SessionDetailDestination(
                navController = navController,
                viewModel = viewModel,
                sessionId = entry.arguments?.getString("sessionId")
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onOpenDashboard = { navController.navigate(Routes.DASHBOARD) }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardSetupScreen(viewModel = viewModel)
        }

        composable(Routes.PROGRESS) {
            ProgressScreen(viewModel = viewModel)
        }

        composable(Routes.CAPTURE) {
            CapturePickerScreen(
                totalSwings = viewModel.labelledSwingCount
                    .collectAsStateWithLifecycle().value,
                onPick = onStartCapture,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MATCH) {
            MatchScreen(
                controllerState = matchState,
                onStart = viewModel::startMatch,
                onAwardPoint = viewModel::awardMatchPoint,
                onUndo = viewModel::undoMatchPoint,
                onAcknowledgePrompt = viewModel::acknowledgeMatchPrompt,
                onClear = {
                    viewModel.clearMatch()
                    navController.popBackStackOrNavigate(Routes.HOME)
                },
                isAmbient = ambient
            )
        }

        composable(Routes.TRAINING) {
            TrainingScreen(
                controllerState = shadowRoutineState,
                onStartShadow = viewModel::startShadowRoutine,
                onConfirmShadow = viewModel::confirmShadowRepetition,
                onPauseShadow = viewModel::pauseShadowRoutine,
                onResumeShadow = viewModel::resumeShadowRoutine,
                onFinishShadowEarly = viewModel::finishShadowRoutineEarly,
                onClearShadow = {
                    viewModel.clearShadowRoutine()
                    navController.popBackStackOrNavigate(Routes.TRAINING)
                },
                isAmbient = ambient
            )
        }
    }
}

/**
 * Historical recap reached by session id. The record is always read from the store-backed
 * history flow, never from a stale UI copy, so reviews and corrections saved here appear as soon
 * as the store emits them.
 */
@Composable
private fun SessionDetailDestination(
    navController: NavHostController,
    viewModel: BadWatchViewModel,
    sessionId: String?
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val stored = history.firstOrNull { it.export.session.id == sessionId }
    if (stored == null) {
        // The record was deleted or the id never existed; never render a dead detail.
        LaunchedEffect(sessionId) { navController.popBackStack() }
        return
    }
    var detailPage by remember(stored.export.session.id) {
        mutableStateOf(RecapPage.Summary)
    }
    var saveInProgress by remember(stored.export.session.id) {
        mutableStateOf(false)
    }
    var saveError by remember(stored.export.session.id) {
        mutableStateOf<String?>(null)
    }
    val diarySaveFailed = stringResource(R.string.review_save_failed)
    val correctionSaveFailed = stringResource(R.string.correction_save_failed)
    when (detailPage) {
        RecapPage.Review -> SessionReviewScreen(
            existingContext = stored.export.context,
            existingReport = stored.export.report,
            onSave = { context, report ->
                if (!saveInProgress) {
                    saveInProgress = true
                    saveError = null
                    viewModel.saveStoredSessionReview(
                        export = stored.export,
                        context = context,
                        report = report
                    ) { result ->
                        saveInProgress = false
                        result.onSuccess {
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
            stored = stored.export,
            insights = viewModel.storedSessionInsights(stored.export),
            onDone = { navController.popBackStack() },
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
            export = stored.export,
            onSave = { falseHits, missedHits, trimStart, trimEnd ->
                if (!saveInProgress) {
                    saveInProgress = true
                    saveError = null
                    viewModel.saveStoredSessionCorrections(
                        export = stored.export,
                        falseHitIds = falseHits,
                        missedHitCount = missedHits,
                        trimFromStartMillis = trimStart,
                        trimFromEndMillis = trimEnd
                    ) { result ->
                        saveInProgress = false
                        result.onSuccess {
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
    data object Local : ScreenFrame { override val kind = ScreenKind.Local }
}

private fun resolveScreenFrame(
    onboarded: Boolean?,
    sessionState: SessionState,
    captureState: CaptureState,
    matchState: MatchControllerState,
    shadowRoutineState: ShadowControllerState
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
    else -> ScreenFrame.Local
}

private enum class ScreenKind {
    Loading, Onboarding, Live, Recap, Drill, DrillSaved,
    SessionFailed, CaptureFailed, Match, Training, Local
}

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import com.badwatch.app.data.StoredSession
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.domain.SessionState
import com.badwatch.app.ui.theme.BadWatchTheme
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.ShotType
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
    onStartCapture: (ShotType) -> Unit,
    onFinishCapture: () -> Unit,
    onCancelCapture: () -> Unit,
    isAmbient: StateFlow<Boolean> = MutableStateFlow(false)
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val ambient by isAmbient.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Home) }
    var detailSession by remember { mutableStateOf<StoredSession?>(null) }

    BackHandler(
        enabled = screen != Screen.Home ||
            sessionState is SessionState.Completed ||
            sessionState is SessionState.Failed ||
            captureState is CaptureState.Saved ||
            captureState is CaptureState.Failed
    ) {
        when {
            sessionState is SessionState.Completed || sessionState is SessionState.Failed ->
                viewModel.acknowledge()

            captureState is CaptureState.Saved || captureState is CaptureState.Failed ->
                viewModel.acknowledgeCapture()

            screen == Screen.SessionDetail -> screen = Screen.History
            else -> screen = Screen.Home
        }
    }

    // Haptic-first: a shot detected mid-rally is a buzz, not a pixel. The flow only emits
    // while recording, so there is nothing to gate here.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        viewModel.shots.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val frame = resolveScreenFrame(
        onboarded = onboarded,
        sessionState = sessionState,
        captureState = captureState,
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
                            isAmbient = ambient
                        )
                    }

                    is ScreenFrame.Recap -> {
                        SummaryScreen(
                            stored = target.state.export,
                            insights = target.state.insights,
                            onDone = {
                                viewModel.acknowledge()
                                screen = Screen.Home
                            }
                        )
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
                            onDismiss = viewModel::acknowledge
                        )
                    }

                    is ScreenFrame.CaptureFailed -> {
                        ErrorScreen(
                            message = target.state.message,
                            onDismiss = viewModel::acknowledgeCapture
                        )
                    }

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
                        onBack = { screen = Screen.Home }
                    )

                    is ScreenFrame.SessionDetail -> {
                        SummaryScreen(
                            stored = target.session.export,
                            insights = emptyList(),
                            onDone = { screen = Screen.History }
                        )
                    }

                    ScreenFrame.Home -> HomeScreen(
                        viewModel = viewModel,
                        onStart = onStartSession,
                        onOpenHistory = { screen = Screen.History },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenCapture = { screen = Screen.Capture },
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

private enum class Screen { Home, History, Settings, Capture, SessionDetail }

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
    data object History : ScreenFrame { override val kind = ScreenKind.History }
    data object Settings : ScreenFrame { override val kind = ScreenKind.Settings }
    data class SessionDetail(val session: StoredSession) : ScreenFrame {
        override val kind = ScreenKind.SessionDetail
    }
    data object Home : ScreenFrame { override val kind = ScreenKind.Home }
}

private fun resolveScreenFrame(
    onboarded: Boolean?,
    sessionState: SessionState,
    captureState: CaptureState,
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
    screen == Screen.Capture -> ScreenFrame.CapturePicker
    screen == Screen.History -> ScreenFrame.History
    screen == Screen.Settings -> ScreenFrame.Settings
    screen == Screen.SessionDetail && detailSession != null -> ScreenFrame.SessionDetail(detailSession)
    else -> ScreenFrame.Home
}

private enum class ScreenKind {
    Loading, Onboarding, Live, Recap, Drill, DrillSaved,
    CapturePicker, SessionFailed, CaptureFailed, History, Settings, SessionDetail, Home
}

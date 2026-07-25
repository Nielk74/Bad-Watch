package com.badwatch.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
    onStartCapture: (ShotType) -> Unit,
    onFinishCapture: () -> Unit,
    isAmbient: StateFlow<Boolean> = MutableStateFlow(false)
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val onboarded by viewModel.onboardingComplete.collectAsStateWithLifecycle()
    val ambient by isAmbient.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf(Screen.Home) }
    var detailSession by remember { mutableStateOf<StoredSession?>(null) }

    // Haptic-first: a shot detected mid-rally is a buzz, not a pixel. The flow only emits
    // while recording, so there is nothing to gate here.
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        viewModel.shots.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val kind = when {
        onboarded == null -> ScreenKind.Loading
        onboarded == false -> ScreenKind.Onboarding
        sessionState is SessionState.Recording -> ScreenKind.Live
        sessionState is SessionState.Completed -> ScreenKind.Recap
        captureState is CaptureState.Capturing -> ScreenKind.Drill
        captureState is CaptureState.Saved -> ScreenKind.DrillSaved
        screen == Screen.Capture -> ScreenKind.CapturePicker
        sessionState is SessionState.Failed -> ScreenKind.Failed
        screen == Screen.History -> ScreenKind.History
        screen == Screen.Settings -> ScreenKind.Settings
        screen == Screen.SessionDetail && detailSession != null -> ScreenKind.SessionDetail
        else -> ScreenKind.Home
    }

    BadWatchTheme {
        AppScaffold {
            AnimatedContent(
                targetState = kind,
                transitionSpec = {
                    fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "screen"
            ) { target ->
                when (target) {
                    ScreenKind.Loading -> LoadingScreen()

                    ScreenKind.Onboarding -> OnboardingScreen(
                        onConfirm = { handedness -> viewModel.completeOnboarding(handedness) }
                    )

                    ScreenKind.Live -> {
                        // Each screen captures its data when its kind becomes current:
                        // session state changes hands the instant recording stops, and
                        // without this the outgoing screen would recompose against the
                        // *next* screen's state mid-crossfade and crash on the cast.
                        val live = remember(target) { sessionState as SessionState.Recording }
                        LiveScreen(
                            state = live,
                            onStop = onStopSession,
                            onDiscard = viewModel::discardSession,
                            isAmbient = ambient
                        )
                    }

                    ScreenKind.Recap -> {
                        val done = remember(target) { sessionState as SessionState.Completed }
                        SummaryScreen(
                            stored = done.export,
                            insights = done.insights,
                            onDone = {
                                viewModel.acknowledge()
                                screen = Screen.Home
                            }
                        )
                    }

                    ScreenKind.Drill -> {
                        val drill = remember(target) { captureState as CaptureState.Capturing }
                        CaptureScreen(
                            state = drill,
                            onDiscardLast = viewModel::discardLastSwing,
                            onFinish = onFinishCapture,
                            onCancel = {
                                viewModel.cancelCapture()
                                screen = Screen.Home
                            }
                        )
                    }

                    ScreenKind.DrillSaved -> {
                        val saved = remember(target) { captureState as CaptureState.Saved }
                        CaptureSavedScreen(
                            export = saved.export,
                            onDone = {
                                viewModel.acknowledgeCapture()
                                screen = Screen.Home
                            }
                        )
                    }

                    ScreenKind.CapturePicker -> CapturePickerScreen(
                        totalSwings = viewModel.labelledSwingCount
                            .collectAsStateWithLifecycle().value,
                        onPick = onStartCapture,
                        onBack = { screen = Screen.Home }
                    )

                    ScreenKind.Failed -> {
                        val failed = remember(target) { sessionState as SessionState.Failed }
                        ErrorScreen(
                            message = failed.message,
                            onDismiss = viewModel::acknowledge
                        )
                    }

                    ScreenKind.History -> HistoryScreen(
                        viewModel = viewModel,
                        onOpenSession = { stored ->
                            detailSession = stored
                            screen = Screen.SessionDetail
                        },
                        onBack = { screen = Screen.Home }
                    )

                    ScreenKind.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        onBack = { screen = Screen.Home }
                    )

                    ScreenKind.SessionDetail -> {
                        val detail = remember(target) { detailSession!! }
                        SummaryScreen(
                            stored = detail.export,
                            insights = emptyList(),
                            onDone = { screen = Screen.History }
                        )
                    }

                    ScreenKind.Home -> HomeScreen(
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

private enum class ScreenKind {
    Loading, Onboarding, Live, Recap, Drill, DrillSaved,
    CapturePicker, Failed, History, Settings, SessionDetail, Home
}

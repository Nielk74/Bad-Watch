package com.badwatch.app.domain

import com.badwatch.app.data.SessionStore
import com.badwatch.app.data.SettingsStore
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.InsightBaselineBuilder
import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.session.SessionRecorder
import com.badwatch.core.sync.SessionExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the live session. Single source of truth for "am I recording, and what have I seen".
 *
 * This lives at application scope rather than in a ViewModel because a session must outlive
 * the Activity — the watch screen turns off seconds after you start playing. The foreground
 * service keeps the process alive; this class keeps the state.
 */
class SessionController(
    private val sensorStream: SensorStream,
    private val sessionStore: SessionStore,
    private val settingsStore: SettingsStore,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Emitted per detected shot so the UI can fire a haptic without diffing snapshots. */
    private val _shots = MutableSharedFlow<ShotEvent>(extraBufferCapacity = 32)
    val shots: SharedFlow<ShotEvent> = _shots.asSharedFlow()

    private val insightEngine = SessionInsightEngine()

    private var recorder: SessionRecorder? = null
    private var collectionJob: Job? = null

    val isRecording: Boolean get() = collectionJob?.isActive == true

    fun start() {
        if (isRecording) return
        collectionJob = scope.launch {
            val profile = settingsStore.profile.first()
            val session = SessionRecorder(profile = profile)
            recorder = session
            val startedAt = now()
            session.start(startedAt)
            _state.value = SessionState.Recording(
                snapshot = session.snapshot(startedAt),
                rallyProfile = RallyProfile.EMPTY,
                profile = profile
            )
            try {
                sensorStream.samples().collect { sample ->
                    val shot = session.onSample(sample)
                    if (shot != null) _shots.tryEmit(shot)
                    val timestamp = sample.timestampMillis
                    _state.value = SessionState.Recording(
                        snapshot = session.snapshot(timestamp),
                        rallyProfile = session.rallyProfile(timestamp),
                        profile = profile
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = SessionState.Failed(
                    error.message ?: "Sensor stream stopped unexpectedly"
                )
                session.abort()
                recorder = null
            }
        }
    }

    /**
     * Ends the session and persists it.
     *
     * @return the saved export, or null when the session captured nothing worth keeping.
     */
    suspend fun stopAndSave(): SessionExport? {
        val session = recorder ?: return null
        collectionJob?.cancel()
        collectionJob = null
        val recorded = session.finish(now())
        recorder = null
        if (recorded == null) {
            _state.value = SessionState.Idle
            return null
        }
        val export = SessionExport(
            deviceId = settingsStore.ensureDeviceId(),
            appVersion = appVersion,
            profile = recorded.profile,
            session = recorded.session,
            rallyProfile = recorded.rallyProfile
        )
        sessionStore.save(export)

        // Baseline is built from the sessions already on the watch, so comparisons are
        // against this player rather than a population average.
        val baseline = InsightBaselineBuilder.build(
            sessionStore.sessions.value
                .filterNot { it.export.session.id == export.session.id }
                .map { it.export.rallyProfile }
        )
        _state.value = SessionState.Completed(
            export = export,
            insights = insightEngine.generate(export.session, export.rallyProfile, baseline)
        )
        return export
    }

    fun discard() {
        collectionJob?.cancel()
        collectionJob = null
        recorder?.abort()
        recorder = null
        _state.value = SessionState.Idle
    }

    /** Dismisses a terminal state (completed/failed) back to idle. */
    fun acknowledge() {
        if (_state.value !is SessionState.Recording) _state.value = SessionState.Idle
    }
}

sealed interface SessionState {
    data object Idle : SessionState

    data class Recording(
        val snapshot: TrainingSessionSnapshot,
        val rallyProfile: RallyProfile,
        val profile: PlayerProfile
    ) : SessionState

    data class Completed(
        val export: SessionExport,
        val insights: List<Insight> = emptyList()
    ) : SessionState

    data class Failed(val message: String) : SessionState
}

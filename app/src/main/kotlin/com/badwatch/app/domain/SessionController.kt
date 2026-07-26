package com.badwatch.app.domain

import com.badwatch.app.data.ActiveSessionJournal
import com.badwatch.app.data.ActiveSessionJournalEntry
import com.badwatch.app.data.SessionStore
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.session.SessionRecorder
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.reviewedAnalysis
import com.badwatch.core.sync.reviewedInsightBaseline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Owns the live session. Single source of truth for "am I recording, and what have I seen".
 *
 * This lives at application scope rather than in a ViewModel because a session must outlive
 * the Activity. Its compact journal also lets a foreground-service restart reconstruct the
 * same session identity and accumulated measurements after Android kills the whole process.
 */
class SessionController(
    private val sensorStream: SensorStream,
    private val sessionStore: SessionStore,
    private val runtimeSettings: SessionRuntimeSettings,
    private val activeSessionJournal: ActiveSessionJournal,
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
    private val lifecycleMutex = Mutex()

    private var recorder: SessionRecorder? = null
    private var identity: ActiveSessionIdentity? = null
    private var collectionJob: Job? = null
    /** Retained only when an atomic store write fails, so a repeated stop can retry safely. */
    private var pendingExport: SessionExport? = null

    val isRecording: Boolean get() = collectionJob?.isActive == true

    /** Starts a new recording or resumes the last atomically checkpointed recording. */
    suspend fun start(): SessionStartResult = lifecycleMutex.withLock {
        if (isRecording) return@withLock SessionStartResult.AlreadyRunning

        pendingExport?.let { export ->
            val stored = runCatching { sessionStore.save(export) }.getOrNull()
            if (stored == null) {
                val message = "The previous session is waiting to be saved"
                _state.value = SessionState.Failed(message)
                return@withLock SessionStartResult.Failed(message)
            }
            activeSessionJournal.clear()
            clearActiveRecorder()
            publishCompleted(stored.export)
            return@withLock SessionStartResult.AlreadySaved(stored.export)
        }

        // A failed stream can leave an in-memory recorder behind for an explicit save. When
        // the user starts again, the durable journal is the authoritative recovery boundary.
        recorder?.abort()
        recorder = null
        identity = null
        collectionJob = null

        try {
            val journalEntry = activeSessionJournal.load()
            val recovered = journalEntry != null
            val session: SessionRecorder
            val activeIdentity: ActiveSessionIdentity

            if (journalEntry != null) {
                val alreadySaved = sessionStore.findById(journalEntry.checkpoint.sessionId)
                if (alreadySaved != null) {
                    activeSessionJournal.clear()
                    publishCompleted(alreadySaved.export)
                    return@withLock SessionStartResult.AlreadySaved(alreadySaved.export)
                }
                session = SessionRecorder.restore(journalEntry.checkpoint)
                activeIdentity = ActiveSessionIdentity(
                    deviceId = journalEntry.deviceId,
                    appVersion = journalEntry.appVersion,
                    recoveryCount = journalEntry.recoveryCount + 1
                )
            } else {
                val profile = runtimeSettings.currentProfile()
                session = SessionRecorder(profile = profile).also { it.start(now()) }
                activeIdentity = ActiveSessionIdentity(
                    deviceId = runtimeSettings.stableDeviceId(),
                    appVersion = appVersion,
                    recoveryCount = 0
                )
            }

            recorder = session
            identity = activeIdentity
            val startedAt = session.checkpoint()!!.aggregator.startedAtMillis
            val initialJournalAt = now()
            check(persistCheckpoint(session, activeIdentity, initialJournalAt)) {
                "Could not create the session recovery checkpoint"
            }
            publishRecording(session, timestampMillis = initialJournalAt)
            launchCollection(
                session = session,
                activeIdentity = activeIdentity,
                firstCheckpointAtMillis = initialJournalAt
            )
            SessionStartResult.Started(
                recovered = recovered,
                startedAtMillis = startedAt
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            recorder?.abort()
            recorder = null
            identity = null
            val message = error.message ?: "Session could not start"
            _state.value = SessionState.Failed(message)
            SessionStartResult.Failed(message)
        }
    }

    private fun launchCollection(
        session: SessionRecorder,
        activeIdentity: ActiveSessionIdentity,
        firstCheckpointAtMillis: Long
    ) {
        collectionJob = scope.launch {
            var lastCheckpointAtMillis = firstCheckpointAtMillis
            try {
                sensorStream.samples().collect { sample ->
                    val shot = session.onSample(sample)
                    if (shot != null) _shots.tryEmit(shot)
                    val timestamp = sample.timestampMillis
                    publishRecording(session, timestampMillis = timestamp)
                    if (timestamp - lastCheckpointAtMillis >= CHECKPOINT_INTERVAL_MILLIS) {
                        if (!persistCheckpoint(session, activeIdentity, timestamp)) {
                            throw IOException("Could not update the session recovery checkpoint")
                        }
                        lastCheckpointAtMillis = timestamp
                    }
                }
                throw IllegalStateException("Sensor stream stopped unexpectedly")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Keep both the recorder and its journal. A later service start can resume,
                // while an explicit Stop can still save everything collected so far.
                persistCheckpoint(session, activeIdentity, now())
                _state.value = SessionState.Failed(
                    error.message ?: "Sensor stream stopped unexpectedly"
                )
            }
        }
    }

    /**
     * Ends the session and persists it. Repeated or concurrent stop commands resolve to the
     * same stable export and can never create a second history row.
     *
     * @return the saved export, or null when the session captured nothing worth keeping or
     *   the durable store write failed (the journal is retained for retry in that case).
     */
    suspend fun stopAndSave(): SessionExport? = lifecycleMutex.withLock {
        (_state.value as? SessionState.Completed)?.export?.let { completed ->
            if (recorder == null && pendingExport == null) return@withLock completed
        }

        pendingExport?.let { export ->
            return@withLock persistPendingExport(export)
        }

        val session = recorder ?: return@withLock null
        val activeIdentity = identity ?: return@withLock null
        collectionJob?.cancelAndJoin()
        collectionJob = null

        val stoppedAtMillis = now()
        // The final sample edge must reach disk before we consume the in-memory recorder.
        persistCheckpoint(session, activeIdentity, stoppedAtMillis)
        val recorded = session.finish(stoppedAtMillis)
        if (recorded == null) {
            session.abort()
            clearActiveRecorder()
            // No samples is an explicit empty-session discard, not a completed recording.
            activeSessionJournal.clear()
            _state.value = SessionState.Idle
            return@withLock null
        }

        var export = SessionExport(
            deviceId = activeIdentity.deviceId,
            appVersion = activeIdentity.appVersion,
            profile = recorded.profile,
            session = recorded.session,
            rallyProfile = recorded.rallyProfile
        )
        if (activeIdentity.recoveryCount > 0) {
            export = export.copy(
                context = export.context.copy(recordingQuality = RecordingQuality.Partial)
            )
        }
        pendingExport = export
        persistPendingExport(export)
    }

    /** Explicitly abandons both in-memory and journaled state; safe to repeat. */
    suspend fun discard(): Boolean = lifecycleMutex.withLock {
        collectionJob?.cancelAndJoin()
        collectionJob = null
        recorder?.abort()
        clearActiveRecorder()
        val cleared = activeSessionJournal.clear()
        _state.value = SessionState.Idle
        cleared
    }

    /**
     * Persists a reviewed diary without exposing raw-session replacement to the UI.
     * Corrections remain append-only data supplied by the review flow; sensor output itself
     * is copied byte-for-byte from the completed export.
     */
    suspend fun updateCompletedSession(
        context: SessionContext,
        report: PostSessionReport,
        corrections: SessionCorrections? = null
    ): SessionExport? = mutateCompletedSession { latest ->
        latest.revisedDiary(context, report).copy(
            corrections = corrections ?: latest.corrections
        )
    }

    /**
     * Serializes one completed-session review against the latest durable envelope.
     *
     * This prevents independently launched diary and detector-review saves from restoring stale
     * copies of each other's fields. The store also rejects any transform that changes raw sensor
     * evidence or provenance.
     */
    suspend fun mutateCompletedSession(
        transform: (SessionExport) -> SessionExport
    ): SessionExport? = lifecycleMutex.withLock {
        val completed = (_state.value as? SessionState.Completed)?.export
            ?: return@withLock null
        val stored = sessionStore.mutateReview(completed.session.id, transform)
        publishCompleted(stored.export)
        stored.export
    }

    /** Dismisses a terminal state (completed/failed) back to idle. */
    fun acknowledge() {
        if (_state.value !is SessionState.Recording) _state.value = SessionState.Idle
    }

    private suspend fun persistPendingExport(export: SessionExport): SessionExport? {
        val stored = runCatching { sessionStore.save(export) }.getOrElse { error ->
            _state.value = SessionState.Failed(error.message ?: "Session could not be saved")
            return null
        }
        // Save is fsynced and atomic. Only now may recovery state be discarded.
        activeSessionJournal.clear()
        clearActiveRecorder()
        publishCompleted(stored.export)
        return stored.export
    }

    private suspend fun persistCheckpoint(
        session: SessionRecorder,
        activeIdentity: ActiveSessionIdentity,
        timestampMillis: Long
    ): Boolean {
        val checkpoint = session.checkpoint() ?: return false
        return activeSessionJournal.save(
            ActiveSessionJournalEntry(
                checkpoint = checkpoint,
                deviceId = activeIdentity.deviceId,
                appVersion = activeIdentity.appVersion,
                recoveryCount = activeIdentity.recoveryCount,
                updatedAtMillis = timestampMillis
            )
        )
    }

    private fun publishRecording(session: SessionRecorder, timestampMillis: Long) {
        if (!session.isRunning) return
        _state.value = SessionState.Recording(
            snapshot = session.snapshot(timestampMillis),
            rallyProfile = session.rallyProfile(timestampMillis),
            profile = session.playerProfile
        )
    }

    private fun publishCompleted(export: SessionExport) {
        val history = sessionStore.sessions.value.map { it.export }
        val analysis = export.reviewedAnalysis()
        val baseline = export.reviewedInsightBaseline(history)
        _state.value = SessionState.Completed(
            export = export,
            insights = insightEngine.generate(
                session = analysis.session,
                rallyProfile = analysis.rallyProfile,
                baseline = baseline
            )
        )
    }

    private fun clearActiveRecorder() {
        recorder = null
        identity = null
        collectionJob = null
        pendingExport = null
    }

    private data class ActiveSessionIdentity(
        val deviceId: String,
        val appVersion: String,
        val recoveryCount: Int
    )

    companion object {
        /** Balances a useful recovery point against wakeups and flash writes. */
        const val CHECKPOINT_INTERVAL_MILLIS: Long = 12_000L
    }
}

sealed interface SessionStartResult {
    data class Started(
        val recovered: Boolean,
        val startedAtMillis: Long
    ) : SessionStartResult

    data object AlreadyRunning : SessionStartResult
    data class AlreadySaved(val export: SessionExport) : SessionStartResult
    data class Failed(val message: String) : SessionStartResult
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

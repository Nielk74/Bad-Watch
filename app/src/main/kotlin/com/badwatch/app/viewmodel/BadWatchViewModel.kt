package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.badwatch.app.AppContainer
import com.badwatch.app.data.StoredSession
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.domain.MatchControllerState
import com.badwatch.app.domain.SessionController
import com.badwatch.app.domain.SessionState
import com.badwatch.app.domain.revisedDiary
import com.badwatch.app.domain.ShadowControllerState
import com.badwatch.core.model.Handedness
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SelfReportedExperience
import com.badwatch.core.match.MatchFormat
import com.badwatch.core.match.MatchSide
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.TrimCorrectionRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI state holder. Note that it does *not* own the session — [SessionController] does,
 * at application scope, because a session outlives this ViewModel every single time the
 * watch screen sleeps.
 */
class BadWatchViewModel(
    private val container: AppContainer
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = container.sessionController.state

    /** One emission per detected shot — the UI fires the haptic from this. */
    val shots = container.sessionController.shots

    val history: StateFlow<List<StoredSession>> = container.sessionStore.sessions

    val captureState: StateFlow<CaptureState> = container.captureController.state

    val matchState: StateFlow<MatchControllerState> = container.matchController.state

    val shadowRoutineState: StateFlow<ShadowControllerState> =
        container.shadowRoutineController.state

    private val _labelledSwingCount = MutableStateFlow(0)
    val labelledSwingCount: StateFlow<Int> = _labelledSwingCount.asStateFlow()

    val profile: StateFlow<PlayerProfile> = container.settingsStore.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerProfile())

    val onboardingComplete: StateFlow<Boolean?> = container.settingsStore.onboardingComplete
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shareDetectionLabData: StateFlow<Boolean> =
        container.settingsStore.shareDetectionLabData
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val detectedHitHaptics: StateFlow<Boolean> = container.settingsStore.detectedHitHaptics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val ageYears: StateFlow<Int?> = container.settingsStore.ageYears
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val configuredRestingHeartRate: StateFlow<Float?> =
        container.settingsStore.configuredRestingHeartRate
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val configuredMaxHeartRate: StateFlow<Float?> =
        container.settingsStore.configuredMaxHeartRate
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val weeklySessionGoal: StateFlow<Int> = container.settingsStore.weeklySessionGoal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3)

    val weeklyRecordedMinutesGoal: StateFlow<Int> =
        container.settingsStore.weeklyRecordedMinutesGoal
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 120)

    private val _dashboardUrl = MutableStateFlow<String?>(null)
    val dashboardUrl: StateFlow<String?> = _dashboardUrl.asStateFlow()

    private val _dashboardConnection =
        MutableStateFlow<DashboardConnectionState>(DashboardConnectionState.NotChecked)
    val dashboardConnection: StateFlow<DashboardConnectionState> =
        _dashboardConnection.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            container.sessionStore.refresh()
            _labelledSwingCount.value = container.captureStore.totalSwings()
            _dashboardUrl.value = container.settingsStore.dashboardUrl.first()
        }
    }

    fun completeOnboarding(handedness: Handedness) {
        viewModelScope.launch {
            container.settingsStore.setHandedness(handedness)
            container.settingsStore.setOnboardingComplete(true)
        }
    }

    fun setHandedness(handedness: Handedness) {
        viewModelScope.launch { container.settingsStore.setHandedness(handedness) }
    }

    fun setExperience(experience: SelfReportedExperience) {
        viewModelScope.launch { container.settingsStore.setExperience(experience) }
    }

    fun setAgeYears(ageYears: Int) {
        viewModelScope.launch { container.settingsStore.setAge(ageYears) }
    }

    fun setRestingHeartRate(bpm: Int) {
        viewModelScope.launch { container.settingsStore.setRestingHeartRate(bpm.toFloat()) }
    }

    fun setMaxHeartRate(bpm: Int) {
        viewModelScope.launch { container.settingsStore.setMaxHeartRate(bpm.toFloat()) }
    }

    fun clearHeartRateProfile() {
        viewModelScope.launch { container.settingsStore.clearHeartRateProfile() }
    }

    fun setDashboard(url: String?, token: String?) {
        viewModelScope.launch {
            container.settingsStore.setDashboard(url, token)
            _dashboardUrl.value = url
        }
    }

    fun saveAndCheckDashboard(url: String, replacementToken: String?) {
        _dashboardConnection.value = DashboardConnectionState.Checking
        viewModelScope.launch {
            val normalized = url.trim().trimEnd('/')
            if (normalized.isBlank()) {
                _dashboardConnection.value = DashboardConnectionState.Failed("Enter a server URL")
                return@launch
            }
            container.settingsStore.updateDashboard(normalized, replacementToken)
            _dashboardUrl.value = normalized
            val token = container.settingsStore.dashboardToken.first()
            container.dashboardClient.checkConnection(normalized, token).fold(
                onSuccess = {
                    _dashboardConnection.value = DashboardConnectionState.Connected
                    container.sessionStore.refresh()
                },
                onFailure = { error ->
                    _dashboardConnection.value = DashboardConnectionState.Failed(
                        error.message ?: "Connection failed"
                    )
                }
            )
        }
    }

    fun clearDashboard() {
        viewModelScope.launch {
            container.settingsStore.clearDashboard()
            _dashboardUrl.value = null
            _dashboardConnection.value = DashboardConnectionState.NotChecked
        }
    }

    fun setShareDetectionLabData(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setShareDetectionLabData(enabled) }
    }

    fun setDetectedHitHaptics(enabled: Boolean) {
        viewModelScope.launch { container.settingsStore.setDetectedHitHaptics(enabled) }
    }

    fun setWeeklyGoals(sessions: Int, recordedMinutes: Int) {
        viewModelScope.launch {
            container.settingsStore.setWeeklyGoals(sessions, recordedMinutes)
        }
    }

    fun discardSession() {
        viewModelScope.launch { container.sessionController.discard() }
    }

    fun acknowledge() {
        container.sessionController.acknowledge()
        viewModelScope.launch { container.sessionStore.refresh() }
    }

    fun saveCompletedSessionReview(
        context: SessionContext,
        report: PostSessionReport,
        onComplete: (Result<SessionExport>) -> Unit = {}
    ) {
        val completed = sessionState.value as? SessionState.Completed
        if (completed == null) {
            onComplete(Result.failure(IllegalStateException("No completed session to review")))
            return
        }
        persistSessionMutation(
            sessionId = completed.export.session.id,
            transform = { latest -> latest.revisedDiary(context, report) },
            onComplete = onComplete
        )
    }

    fun skipCompletedSessionReview(
        onComplete: (Result<SessionExport>) -> Unit = {}
    ) {
        val completed = sessionState.value as? com.badwatch.app.domain.SessionState.Completed
        if (completed == null) {
            onComplete(Result.failure(IllegalStateException("No completed session to review")))
            return
        }
        persistSessionMutation(
            sessionId = completed.export.session.id,
            transform = { latest ->
                latest.revisedDiary(
                    context = latest.context.copy(
                        diaryReviewStatus = DiaryReviewStatus.Skipped
                    ),
                    report = latest.report
                )
            },
            onComplete = onComplete
        )
    }

    fun saveCompletedSessionCorrections(
        falseHitIds: Set<String>,
        missedHitCount: Int,
        trimFromStartMillis: Long,
        trimFromEndMillis: Long,
        onComplete: (Result<SessionExport>) -> Unit = {}
    ) {
        val completed = sessionState.value as? com.badwatch.app.domain.SessionState.Completed
        if (completed == null) {
            onComplete(Result.failure(IllegalStateException("No completed session to correct")))
            return
        }
        persistSessionMutation(
            sessionId = completed.export.session.id,
            transform = { latest ->
                revisedWithCorrections(
                    export = latest,
                    falseHitIds = falseHitIds,
                    missedHitCount = missedHitCount,
                    trimFromStartMillis = trimFromStartMillis,
                    trimFromEndMillis = trimFromEndMillis
                )
            },
            onComplete = onComplete
        )
    }

    /** Reopens a historical diary without pretending it is still the active controller state. */
    fun saveStoredSessionReview(
        export: SessionExport,
        context: SessionContext,
        report: PostSessionReport,
        onComplete: (Result<SessionExport>) -> Unit = {}
    ) {
        persistSessionMutation(
            sessionId = export.session.id,
            transform = { latest -> latest.revisedDiary(context, report) },
            onComplete = onComplete
        )
    }

    /** Applies an append-only detector review to any session in History. */
    fun saveStoredSessionCorrections(
        export: SessionExport,
        falseHitIds: Set<String>,
        missedHitCount: Int,
        trimFromStartMillis: Long,
        trimFromEndMillis: Long,
        onComplete: (Result<SessionExport>) -> Unit = {}
    ) {
        persistSessionMutation(
            sessionId = export.session.id,
            transform = { latest ->
                revisedWithCorrections(
                    export = latest,
                    falseHitIds = falseHitIds,
                    missedHitCount = missedHitCount,
                    trimFromStartMillis = trimFromStartMillis,
                    trimFromEndMillis = trimFromEndMillis
                )
            },
            onComplete = onComplete
        )
    }

    private fun revisedWithCorrections(
        export: SessionExport,
        falseHitIds: Set<String>,
        missedHitCount: Int,
        trimFromStartMillis: Long,
        trimFromEndMillis: Long
    ): SessionExport {
        val currentHits = export.corrections.currentHitRevision
        val currentTrim = export.corrections.currentTrimRevision
        val normalizedFalseIds = falseHitIds.sorted()
        val hitChanged = normalizedFalseIds != currentHits?.falseHitIds.orEmpty().distinct().sorted() ||
            missedHitCount != (currentHits?.missedHitCount ?: 0)
        val trimChanged = trimFromStartMillis != (currentTrim?.trimFromStartMillis ?: 0L) ||
            trimFromEndMillis != (currentTrim?.trimFromEndMillis ?: 0L)
        if (!hitChanged && !trimChanged) return export

        val recordedAt = System.currentTimeMillis()
        fun provenance(kind: String) = CorrectionProvenance(
            revisionId = UUID.randomUUID().toString(),
            actor = CorrectionActor.Player,
            recordedAtMillis = recordedAt,
            reason = "On-watch $kind review"
        )
        val revised = SessionCorrections(
            hitRevisions = export.corrections.hitRevisions + if (hitChanged) {
                listOf(
                    HitCorrectionRevision(
                        falseHitIds = normalizedFalseIds,
                        missedHitCount = missedHitCount.coerceAtLeast(0),
                        provenance = provenance("hit")
                    )
                )
            } else {
                emptyList()
            },
            trimRevisions = export.corrections.trimRevisions + if (trimChanged) {
                listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = trimFromStartMillis.coerceAtLeast(0L),
                        trimFromEndMillis = trimFromEndMillis.coerceAtLeast(0L),
                        provenance = provenance("trim")
                    )
                )
            } else {
                emptyList()
            }
        )
        return export.copy(corrections = revised)
    }

    /**
     * Serializes every local diary edit against the latest durable envelope and reports only
     * after the atomic write completes. A sync scheduling failure does not roll back a durable
     * local save; the pending marker remains eligible for the next worker run.
     */
    private fun persistSessionMutation(
        sessionId: String,
        transform: (SessionExport) -> SessionExport,
        onComplete: (Result<SessionExport>) -> Unit
    ) {
        viewModelScope.launch {
            val result = try {
                val completed = sessionState.value as? SessionState.Completed
                val revised = if (completed?.export?.session?.id == sessionId) {
                    container.sessionController.mutateCompletedSession(transform)
                        ?: error("The completed session is no longer available")
                } else {
                    container.sessionStore.mutateReview(sessionId, transform).export
                }
                Result.success(revised)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Result.failure(error)
            }
            if (result.isSuccess) runCatching(container::enqueueSync)
            onComplete(result)
        }
    }

    fun startCapture(label: ShotType) {
        viewModelScope.launch { container.captureController.start(label) }
    }

    fun discardLastSwing() {
        container.captureController.discardLastSwing()
    }

    fun finishCapture() {
        viewModelScope.launch {
            container.captureController.finish()
            _labelledSwingCount.value = container.captureStore.totalSwings()
        }
    }

    fun cancelCapture() {
        viewModelScope.launch { container.captureController.cancel() }
    }

    fun acknowledgeCapture() {
        viewModelScope.launch { container.captureController.acknowledge() }
    }

    fun startMatch(format: MatchFormat, initialServer: MatchSide) {
        container.matchController.start(format, initialServer)
    }

    fun awardMatchPoint(side: MatchSide) {
        container.matchController.awardPoint(side)
    }

    fun undoMatchPoint() {
        container.matchController.undoLastPoint()
    }

    fun acknowledgeMatchPrompt() {
        container.matchController.acknowledgePrompt()
    }

    fun clearMatch() {
        container.matchController.clear()
    }

    fun startShadowRoutine(targetRepetitions: Int) {
        container.shadowRoutineController.start(targetRepetitions)
    }

    fun confirmShadowRepetition() {
        container.shadowRoutineController.confirm()
    }

    fun pauseShadowRoutine() {
        container.shadowRoutineController.pause()
    }

    fun resumeShadowRoutine() {
        container.shadowRoutineController.resume()
    }

    fun finishShadowRoutineEarly() {
        container.shadowRoutineController.finishEarly()
    }

    fun clearShadowRoutine() {
        container.shadowRoutineController.clear()
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { container.sessionStore.delete(sessionId) }
    }

    fun showMessage(text: String?) {
        _message.value = text
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BadWatchViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return BadWatchViewModel(container) as T
        }
    }
}

sealed interface DashboardConnectionState {
    data object NotChecked : DashboardConnectionState
    data object Checking : DashboardConnectionState
    data object Connected : DashboardConnectionState
    data class Failed(val message: String) : DashboardConnectionState
}

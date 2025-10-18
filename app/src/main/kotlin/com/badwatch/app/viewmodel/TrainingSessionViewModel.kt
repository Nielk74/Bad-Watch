package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badwatch.app.data.TrainingSessionRepository
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.pipeline.ShotDetectionPipeline
import com.badwatch.core.session.TrainingSessionAggregator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class TrainingSessionUiState {
    data class Idle(val recentSession: TrainingSession?) : TrainingSessionUiState()
    data class Running(val snapshot: TrainingSessionSnapshot) : TrainingSessionUiState()
    data class Finished(val session: TrainingSession) : TrainingSessionUiState()
    data class Error(val message: String) : TrainingSessionUiState()
}

class TrainingSessionViewModel(
    private val repository: TrainingSessionRepository,
    private val sensorStreamProvider: SensorStreamProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val aggregatorFactory: () -> TrainingSessionAggregator = { TrainingSessionAggregator() },
    private val pipelineFactory: () -> ShotDetectionPipeline =
        { ShotDetectionPipeline(ShotClassifier()) }
) : ViewModel() {

    private val _uiState = MutableStateFlow<TrainingSessionUiState>(TrainingSessionUiState.Idle(null))
    val uiState: StateFlow<TrainingSessionUiState> = _uiState.asStateFlow()

    val history: StateFlow<List<TrainingSession>> =
        repository.history.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = emptyList()
        )

    private var aggregator = aggregatorFactory()
    private var pipeline = pipelineFactory()
    private var sessionJob: Job? = null

    init {
        viewModelScope.launch {
            history.collect { sessions ->
                val recent = sessions.firstOrNull()
                if (_uiState.value is TrainingSessionUiState.Idle) {
                    _uiState.value = TrainingSessionUiState.Idle(recent)
                }
            }
        }
    }

    fun startSession() {
        if (sessionJob != null) return
        aggregator = aggregatorFactory().also { it.reset(clock()) }
        pipeline = pipelineFactory()
        _uiState.value = TrainingSessionUiState.Running(aggregator.snapshot(clock()))
        sessionJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sensorStreamProvider.sensorStream().collect { sample ->
                    aggregator.onSample(sample)
                    pipeline.addSample(sample)?.let { event ->
                        aggregator.onShot(event)
                    }
                    val snapshot = aggregator.snapshot(clock())
                    _uiState.value = TrainingSessionUiState.Running(snapshot)
                }
            } catch (throwable: Throwable) {
                _uiState.value = TrainingSessionUiState.Error(throwable.message ?: "Sensor error")
            }
        }
    }

    fun stopSession(saveResult: Boolean) {
        val job = sessionJob ?: return
        job.cancel()
        sessionJob = null
        val now = clock()
        val session = aggregator.buildSession(now)
        if (saveResult && shouldPersist(session)) {
            viewModelScope.launch {
                repository.persistSession(session)
            }
        }
        aggregator = aggregatorFactory()
        pipeline = pipelineFactory()
        _uiState.value = TrainingSessionUiState.Finished(session)
    }

    fun abortSession() {
        sessionJob?.cancel()
        sessionJob = null
        aggregator = aggregatorFactory()
        pipeline = pipelineFactory()
        _uiState.value = TrainingSessionUiState.Idle(history.value.firstOrNull())
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clear()
        }
    }

    private fun shouldPersist(session: TrainingSession): Boolean =
        session.summary.totalShots > 0 || session.summary.durationMillis >= MIN_PERSIST_DURATION

    companion object {
        private const val MIN_PERSIST_DURATION = 90_000L
    }
}

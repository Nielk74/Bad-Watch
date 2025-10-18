package com.badwatch.app.viewmodel

import com.badwatch.app.MainDispatcherRule
import com.badwatch.app.data.TrainingSessionRepository
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.Vector3
import com.badwatch.core.pipeline.ShotDetectionPipeline
import com.badwatch.core.session.TrainingSessionAggregator
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSessionViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val sensorFlow = MutableSharedFlow<SensorSample>(extraBufferCapacity = 64)
    private val fakeRepository = FakeRepository()
    private val clockMillis = AtomicLong(0L)

    private fun buildViewModel(): TrainingSessionViewModel {
        return TrainingSessionViewModel(
            repository = fakeRepository,
            sensorStreamProvider = object : SensorStreamProvider {
                override fun sensorStream(): Flow<SensorSample> = sensorFlow
            },
            clock = { clockMillis.get() },
            aggregatorFactory = { TrainingSessionAggregator(baselineHeartRate = 60f, maxHeartRate = 190f) },
            pipelineFactory = { ShotDetectionPipeline(ShotClassifier()) }
        )
    }

    @Test
    fun startSessionEmitsRunningStateAndPersistsOnStop() = runTest {
        val viewModel = buildViewModel()
        clockMillis.set(1_000L)
        viewModel.startSession()

        emitSmashSamples(startTimestamp = 1_000L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state).isInstanceOf(TrainingSessionUiState.Running::class.java)

        clockMillis.set(3_000L)
        viewModel.stopSession(saveResult = true)
        advanceUntilIdle()

        assertThat(fakeRepository.persistedSessions).isNotEmpty()
    }

    @Test
    fun discardSessionDoesNotPersist() = runTest {
        val viewModel = buildViewModel()
        clockMillis.set(500L)
        viewModel.startSession()

        emitSmashSamples(startTimestamp = 500L)
        advanceUntilIdle()

        viewModel.stopSession(saveResult = false)
        advanceUntilIdle()

        assertThat(fakeRepository.persistedSessions).isEmpty()
    }

    private suspend fun emitSmashSamples(startTimestamp: Long) {
        val samples = listOf(
            Vector3(0.2f, 0.4f, -1.2f),
            Vector3(0.4f, 0.6f, -2.8f),
            Vector3(0.5f, 0.7f, -4.5f),
            Vector3(0.6f, 0.9f, -5.4f),
            Vector3(0.8f, 1.1f, -6.8f),
            Vector3(0.5f, 0.6f, -4.0f),
            Vector3(0.3f, 0.5f, -1.5f),
            Vector3(0.2f, 0.4f, -1.2f)
        )
        samples.forEachIndexed { index, vector ->
            val timestamp = startTimestamp + index * 40L
            clockMillis.set(timestamp)
            sensorFlow.emit(
                SensorSample(
                    timestampMillis = timestamp,
                    gyro = vector,
                    heartRateBpm = 120f + index,
                    accuracy = 3
                )
            )
        }
    }

    private class FakeRepository : TrainingSessionRepository {
        private val _history = MutableStateFlow<List<TrainingSession>>(emptyList())
        val persistedSessions = mutableListOf<TrainingSession>()

        override val history: Flow<List<TrainingSession>> = _history

        override suspend fun persistSession(session: TrainingSession) {
            persistedSessions += session
            _history.value = listOf(session) + _history.value
        }

        override suspend fun clear() {
            persistedSessions.clear()
            _history.value = emptyList()
        }
    }
}

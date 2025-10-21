package com.badwatch.app.viewmodel

import com.badwatch.app.MainDispatcherRule
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.app.model.GyroReading
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class GyroViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun startBeginsStreamingAndUpdatesState() = runTest {
        val provider = FakeSensorStreamProvider()
        val viewModel = GyroViewModel(provider)

        val reading = GyroReading(
            timestampMillis = 1_000L,
            x = 1.5f,
            y = -0.5f,
            z = 0.25f
        )

        viewModel.start()
        advanceUntilIdle()
        provider.emit(reading)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertWithMessage("state=$state").that(state.tracking).isTrue()
        assertThat(state.latestReading).isEqualTo(reading)
        assertThat(state.magnitude).isWithin(1e-3f).of(
            sqrt(reading.x * reading.x + reading.y * reading.y + reading.z * reading.z)
        )
        assertThat(state.sampleCount).isEqualTo(1)
        assertThat(state.lastUpdatedMillis).isEqualTo(reading.timestampMillis)
        assertThat(state.error).isNull()
    }

    @Test
    fun stopEndsStreaming() = runTest {
        val provider = FakeSensorStreamProvider()
        val viewModel = GyroViewModel(provider)

        viewModel.start()
        advanceUntilIdle()
        viewModel.stop()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.tracking).isFalse()
    }

    private class FakeSensorStreamProvider : SensorStreamProvider {
        private val readings = MutableSharedFlow<GyroReading>(replay = 1)
        override fun sensorStream(): Flow<GyroReading> = readings
        suspend fun emit(reading: GyroReading) = readings.emit(reading)
    }
}

package com.badwatch.app.domain

import com.badwatch.app.data.CaptureStore
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureWatch
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlin.math.exp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureControllerConcurrencyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun concurrentFinishCommandsReturnOneStableExportAndWriteOneFile() = runTest {
        val directory = temporaryFolder.newFolder("concurrent-finish")
        val stream = FakeSensorStream()
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val times = ArrayDeque(listOf(1_000L, 4_000L))
        val controller = controller(
            stream = stream,
            store = CaptureStore(directory),
            scope = controllerScope,
            now = { times.removeFirst() }
        )

        assertThat(controller.start(ShotType.Smash)).isTrue()
        runCurrent()
        stream.feedSwing()
        runCurrent()
        assertThat((controller.state.value as CaptureState.Capturing).keptCount).isEqualTo(1)

        val first = async { controller.finish() }
        val repeated = async { controller.finish() }
        runCurrent()

        assertThat(first.await()).isNotNull()
        assertThat(repeated.await()).isEqualTo(first.await())
        assertThat((controller.state.value as CaptureState.Saved).export).isEqualTo(first.await())
        assertThat(directory.listFiles { file -> file.extension == "json" }?.toList())
            .hasSize(1)
        controllerScope.cancel()
    }

    @Test
    fun failedWriteRetriesTheSamePendingCaptureIdentity() = runTest {
        val directory = temporaryFolder.newFolder("retry-finish")
        val attemptedPayloads = mutableListOf<String>()
        var failFirstWrite = true
        val store = CaptureStore(directory) { destination, payload ->
            attemptedPayloads += payload
            if (failFirstWrite) {
                failFirstWrite = false
                throw IOException("disk unavailable")
            }
            destination.writeText(payload)
        }
        val stream = FakeSensorStream()
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val times = ArrayDeque(listOf(2_000L, 5_000L))
        val controller = controller(
            stream = stream,
            store = store,
            scope = controllerScope,
            now = { times.removeFirst() }
        )

        controller.start(ShotType.Clear)
        runCurrent()
        stream.feedSwing()
        runCurrent()

        assertThat(controller.finish()).isNull()
        assertThat(controller.state.value).isInstanceOf(CaptureState.Failed::class.java)
        val retried = controller.finish()

        assertThat(retried).isNotNull()
        assertThat(attemptedPayloads).hasSize(2)
        val attemptedIds = attemptedPayloads.map { payload ->
            BadWatchJson.decodeFromString(CaptureExport.serializer(), payload).capture.id
        }
        assertThat(attemptedIds.toSet()).hasSize(1)
        assertThat(directory.listFiles { file -> file.extension == "json" }?.toList())
            .hasSize(1)
        controllerScope.cancel()
    }

    private fun controller(
        stream: SensorStream,
        store: CaptureStore,
        scope: CoroutineScope,
        now: () -> Long
    ) = CaptureController(
        sensorStream = stream,
        captureStore = store,
        runtimeSettings = FakeCaptureRuntimeSettings,
        appVersion = "test",
        scope = scope,
        now = now
    )

    private object FakeCaptureRuntimeSettings : CaptureRuntimeSettings {
        override suspend fun snapshot() = CaptureRuntimeMetadata(
            deviceId = "watch-test",
            participantId = "participant-test",
            profile = PlayerProfile(),
            dataUse = CaptureDataUse.LocalOnly,
            watch = CaptureWatch("test", "watch", 36)
        )
    }

    private class FakeSensorStream : SensorStream {
        private val flow = MutableSharedFlow<SensorSample>(extraBufferCapacity = 256)

        override fun samples(): Flow<SensorSample> = flow

        fun feedSwing() {
            var timestamp = 0L
            while (timestamp <= 2_000L) {
                val delta = (timestamp - 1_000L).toDouble()
                val magnitude = 7f * exp(-(delta * delta) / (2 * 45.0 * 45.0)).toFloat() + 0.05f
                check(
                    flow.tryEmit(
                        SensorSample(
                            timestampMillis = timestamp,
                            gyro = Vector3(0f, 0f, magnitude),
                            heartRateBpm = 140f
                        )
                    )
                )
                timestamp += 10L
            }
        }
    }
}

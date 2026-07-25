package com.badwatch.core.sync

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.badwatch.core.session.RallySegmenter
import com.badwatch.core.session.SessionRecorder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the wire format against values JSON cannot represent.
 *
 * This exists because of a real crash: heart rate used `Float.NaN` as its "no reading"
 * sentinel, and `kotlinx.serialization` throws on NaN. Every sample recorded before the
 * optical sensor gets a lock — which is the opening seconds of *every* session — carried
 * NaN, so saving killed the app process. It reached a device before anything caught it,
 * because the only sessions exercised until then happened to contain zero shots.
 */
class SerializationTest {

    @Test
    fun aCaptureWithNoHeartRateReadingsRoundTrips() {
        val export = CaptureExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            capture = CaptureSession(
                id = "capture",
                startedAtMillis = 0L,
                endedAtMillis = 1_000L,
                label = ShotType.Smash,
                swings = listOf(
                    LabeledSwing(
                        id = "swing",
                        label = ShotType.Smash,
                        peakTimestampMillis = 500L,
                        peakAngularVelocity = 8f,
                        // No heart-rate lock: the case that used to crash.
                        samples = (0 until 20).map { index ->
                            SensorSample(
                                timestampMillis = index * 10L,
                                gyro = Vector3(0.4f, 0.5f, -8f),
                                heartRateBpm = null
                            )
                        }
                    )
                )
            ),
            samplingRateHz = 100
        )

        val encoded = BadWatchJson.encodeToString(CaptureExport.serializer(), export)
        val decoded = BadWatchJson.decodeFromString(CaptureExport.serializer(), encoded)

        assertThat(encoded).doesNotContain("NaN")
        assertThat(decoded).isEqualTo(export)
        assertThat(decoded.capture.swings.single().samples.first().heartRateBpm).isNull()
    }

    @Test
    fun aSessionRecordedWithoutAHeartRateSensorRoundTrips() {
        val recorder = SessionRecorder()
        recorder.start(0L)

        var clock = 0L
        repeat(4) {
            SMASH_PROFILE.forEach { gyro ->
                recorder.onSample(
                    SensorSample(timestampMillis = clock, gyro = gyro, heartRateBpm = null)
                )
                clock += 40L
            }
            clock += 900L
        }

        val recorded = recorder.finish(clock)!!
        val export = SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = recorded.profile,
            session = recorded.session,
            rallyProfile = recorded.rallyProfile
        )

        val encoded = BadWatchJson.encodeToString(SessionExport.serializer(), export)

        assertThat(encoded).doesNotContain("NaN")
        assertThat(encoded).doesNotContain("Infinity")
        assertThat(BadWatchJson.decodeFromString(SessionExport.serializer(), encoded))
            .isEqualTo(export)
        // Absence is reported as absence, not as a fabricated baseline.
        assertThat(export.session.summary.averageHeartRate).isNull()
        assertThat(export.session.summary.maxHeartRate).isNull()
    }

    @Test
    fun ralliesWithoutHeartRateReportNullRatherThanZero() {
        val shots = (0 until 4).map { index ->
            ShotEvent(
                id = "shot-$index",
                type = ShotType.Clear,
                timestampMillis = index * 900L,
                confidence = 0.7f,
                peakAngularVelocity = 5f,
                heartRateBpm = null,
                swingDurationMillis = 240L
            )
        }

        val profile = RallySegmenter().segment(shots, sessionEndMillis = 5_000L)

        assertThat(profile.rallies.single().averageHeartRate).isNull()
    }

    private companion object {
        val SMASH_PROFILE = listOf(
            Vector3(0.2f, 0.4f, -1.2f),
            Vector3(0.4f, 0.6f, -2.8f),
            Vector3(0.5f, 0.7f, -4.5f),
            Vector3(0.6f, 0.9f, -5.4f),
            Vector3(0.8f, 1.1f, -6.8f),
            Vector3(0.5f, 0.6f, -4.0f),
            Vector3(0.3f, 0.5f, -1.5f)
        )
    }
}

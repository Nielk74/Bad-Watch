package com.badwatch.core.sync

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.HeartRateValueSource
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
            participantId = "participant",
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
            samplingRateHz = 100,
            dataUse = CaptureDataUse.SelfHostedModelTraining,
            protocol = CaptureProtocol(),
            watch = CaptureWatch("Google", "Pixel Watch", 36)
        )

        val encoded = BadWatchJson.encodeToString(CaptureExport.serializer(), export)
        val decoded = BadWatchJson.decodeFromString(CaptureExport.serializer(), encoded)

        assertThat(encoded).doesNotContain("NaN")
        assertThat(decoded).isEqualTo(export)
        assertThat(decoded.capture.swings.single().samples.first().heartRateBpm).isNull()
        assertThat(decoded.isEligibleForModelTrainingUpload).isTrue()
    }

    @Test
    fun aLegacyCaptureDefaultsToLocalOnlyAndCannotUpload() {
        val legacy = """
            {
              "schemaVersion": 1,
              "deviceId": "legacy-device",
              "appVersion": "0.2.0",
              "profile": {
                "handedness": "Right",
                "restingHeartRate": 60.0,
                "maxHeartRate": 190.0
              },
              "capture": {
                "id": "legacy-capture",
                "startedAtMillis": 0,
                "endedAtMillis": 1000,
                "label": "Smash",
                "swings": []
              },
              "samplingRateHz": 100
            }
        """.trimIndent()

        val decoded = BadWatchJson.decodeFromString(CaptureExport.serializer(), legacy)

        assertThat(decoded.participantId).isNull()
        assertThat(decoded.dataUse).isEqualTo(CaptureDataUse.LocalOnly)
        assertThat(decoded.protocol).isNull()
        assertThat(decoded.watch).isNull()
        assertThat(decoded.isEligibleForModelTrainingUpload).isFalse()
        assertThat(decoded.profile.restingHeartRate).isEqualTo(60f)
        assertThat(decoded.profile.maxHeartRate).isEqualTo(190f)
        assertThat(decoded.profile.restingHeartRateSource)
            .isEqualTo(HeartRateValueSource.Unconfigured)
        assertThat(decoded.profile.maxHeartRateSource)
            .isEqualTo(HeartRateValueSource.Unconfigured)
        assertThat(decoded.profile.hasConfiguredHeartRateReserve).isFalse()
    }

    @Test
    @Suppress("DEPRECATION")
    fun aSchemaOneSessionRetainsLegacyScoresWhenDecodingStoredJson() {
        val stored = """
            {
              "schemaVersion": 1,
              "deviceId": "legacy-device",
              "appVersion": "0.1.0",
              "profile": {
                "handedness": "Right",
                "restingHeartRate": 60.0,
                "maxHeartRate": 190.0
              },
              "session": {
                "id": "legacy-session",
                "startedAtMillis": 1000,
                "endedAtMillis": 2000,
                "summary": {
                  "totalShots": 0,
                  "shotCounts": {},
                  "durationMillis": 1000,
                  "averageHeartRate": null,
                  "maxHeartRate": null,
                  "recoveryScore": 0.6,
                  "fatigueScore": 0.7,
                  "effortScore": 0.8,
                  "heartRateZoneHistogram": {}
                },
                "shots": []
              },
              "rallyProfile": {
                "rallies": [],
                "totalWorkMillis": 0,
                "totalRestMillis": 1000
              }
            }
        """.trimIndent()

        val decoded = BadWatchJson.decodeFromString(SessionExport.serializer(), stored)

        assertThat(decoded.schemaVersion).isEqualTo(1)
        assertThat(decoded.session.summary.recoveryScore).isEqualTo(0.6f)
        assertThat(decoded.session.summary.fatigueScore).isEqualTo(0.7f)
        assertThat(decoded.session.summary.effortScore).isEqualTo(0.8f)
        assertThat(decoded.profile.hasConfiguredHeartRateReserve).isFalse()
        assertThat(decoded.diaryRevision).isEqualTo(0L)
        assertThat(decoded.diaryBaseRevision).isNull()
        assertThat(decoded.session.processAbsenceGaps).isEmpty()
        val roundTripped = BadWatchJson.decodeFromString(
            SessionExport.serializer(),
            BadWatchJson.encodeToString(SessionExport.serializer(), decoded)
        )
        assertThat(roundTripped).isEqualTo(decoded)
    }

    @Test
    fun consentWithoutContributorOrProtocolIsStillNotUploadable() {
        val base = CaptureExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            capture = CaptureSession(
                id = "capture",
                startedAtMillis = 0L,
                endedAtMillis = 1L,
                label = ShotType.Clear,
                swings = emptyList()
            ),
            samplingRateHz = 100,
            dataUse = CaptureDataUse.SelfHostedModelTraining
        )

        assertThat(base.isEligibleForModelTrainingUpload).isFalse()
        assertThat(base.copy(participantId = "person", protocol = CaptureProtocol())
            .isEligibleForModelTrainingUpload).isTrue()
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

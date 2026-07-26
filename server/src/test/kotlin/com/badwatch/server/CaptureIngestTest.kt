package com.badwatch.server

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureEnvelope
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.SyncResponse
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class CaptureIngestTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun acceptsLabelledDrillsAndReportsDatasetProgress() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("captures"))

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }

            val envelope = CaptureEnvelope(
                captures = listOf(
                    capture(ShotType.Smash, swings = 12, deviceId = "player-a"),
                    capture(ShotType.Clear, swings = 8, deviceId = "player-a"),
                    capture(ShotType.Smash, swings = 5, deviceId = "player-b")
                )
            )

            val response = client.post("/api/v1/captures") {
                contentType(ContentType.Application.Json)
                setBody(BadWatchJson.encodeToString(CaptureEnvelope.serializer(), envelope))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(
                BadWatchJson.decodeFromString(SyncResponse.serializer(), response.bodyAsText()).accepted
            ).hasSize(3)

            val listedResponse = client.get("/api/v1/captures")
            assertThat(listedResponse.status).isEqualTo(HttpStatusCode.OK)
            val listed = BadWatchJson.decodeFromString(
                ListSerializer(CaptureExport.serializer()),
                listedResponse.bodyAsText()
            )
            assertThat(listed).containsExactlyElementsIn(envelope.captures)

            val summary = BadWatchJson.decodeFromString(
                CaptureSummary.serializer(),
                client.get("/api/v1/captures/summary").bodyAsText()
            )

            assertThat(summary.drillCount).isEqualTo(3)
            assertThat(summary.totalSwings).isEqualTo(25)
            assertThat(summary.contributingDevices).isEqualTo(2)
            assertThat(summary.contributingParticipants).isEqualTo(2)
            assertThat(summary.swingsPerLabel.first { it.label == "Smash" }.swings).isEqualTo(17)
        }
    }

    @Test
    fun discardedSwingsDoNotCountTowardTheDataset() = runBlocking {
        val captures = CaptureRepository(temporaryFolder.newFolder("captures"))
        val export = capture(ShotType.Drive, swings = 10, deviceId = "player-a", discardCount = 4)

        captures.save(export)

        // Mislabelled or mishit swings are kept on disk for auditing but must never inflate
        // the count that decides whether there is enough data to train on.
        assertThat(captures.summary().totalSwings).isEqualTo(6)
        assertThat(captures.all().single().capture.swings).hasSize(10)
    }

    @Test
    fun rejectsEveryCaptureInAnUnknownEnvelopeSchemaWithoutCreatingARetryLoop() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("captures"))
        val export = capture(ShotType.Smash, swings = 2, deviceId = "schema-player")

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }

            val response = client.post("/api/v1/captures") {
                contentType(ContentType.Application.Json)
                setBody(
                    BadWatchJson.encodeToString(
                        CaptureEnvelope.serializer(),
                        CaptureEnvelope(schemaVersion = 99, captures = listOf(export))
                    )
                )
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).isEmpty()
            assertThat(acknowledgement.rejected[export.capture.id])
                .contains("Unsupported envelope schema 99")
            assertThat(captures.all()).isEmpty()
        }
    }

    @Test
    fun malformedCaptureSyncJsonReturnsBadRequestWithoutWriting() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("malformed-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("malformed-captures"))

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }

            val response = client.post("/api/v1/captures") {
                contentType(ContentType.Application.Json)
                setBody("""{"schemaVersion":1,"captures":[""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("Invalid capture sync JSON")
            assertThat(captures.all()).isEmpty()
        }
    }

    @Test
    fun rejectsLocalOnlyLegacyAndIncompleteRawCapturesPerRecord() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("consent-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("consent-captures"))
        val eligible = capture(ShotType.Smash, swings = 2, deviceId = "player-a")
        val localOnly = eligible.copy(
            capture = eligible.capture.copy(id = UUID.randomUUID().toString()),
            dataUse = CaptureDataUse.LocalOnly
        )
        val legacy = eligible.copy(
            capture = eligible.capture.copy(id = UUID.randomUUID().toString()),
            participantId = null,
            protocol = null
        )
        val missingProtocol = eligible.copy(
            capture = eligible.capture.copy(id = UUID.randomUUID().toString()),
            protocol = null
        )

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }

            val response = client.post("/api/v1/captures") {
                contentType(ContentType.Application.Json)
                setBody(
                    BadWatchJson.encodeToString(
                        CaptureEnvelope.serializer(),
                        CaptureEnvelope(captures = listOf(localOnly, legacy, missingProtocol))
                    )
                )
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val result = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(result.accepted).isEmpty()
            assertThat(result.rejected[localOnly.capture.id]).contains("not consented")
            assertThat(result.rejected[legacy.capture.id]).contains("participant id")
            assertThat(result.rejected[missingProtocol.capture.id]).contains("protocol")
            assertThat(captures.all()).isEmpty()
        }
    }

    private fun capture(
        label: ShotType,
        swings: Int,
        deviceId: String,
        discardCount: Int = 0
    ): CaptureExport {
        val labelled = (0 until swings).map { index ->
            LabeledSwing(
                id = UUID.randomUUID().toString(),
                label = label,
                peakTimestampMillis = 1_000L + index * 1_000L,
                peakAngularVelocity = 6.2f,
                samples = (0 until 20).map { sampleIndex ->
                    SensorSample(
                        timestampMillis = 1_000L + index * 1_000L + sampleIndex * 10L,
                        gyro = Vector3(0.4f, 0.5f, -3f),
                        heartRateBpm = 150f
                    )
                },
                discarded = index < discardCount
            )
        }
        return CaptureExport(
            deviceId = deviceId,
            participantId = deviceId,
            appVersion = "test",
            profile = PlayerProfile(),
            capture = CaptureSession(
                id = UUID.randomUUID().toString(),
                startedAtMillis = 1_000L,
                endedAtMillis = 60_000L,
                label = label,
                swings = labelled
            ),
            samplingRateHz = 100,
            dataUse = CaptureDataUse.SelfHostedModelTraining,
            protocol = CaptureProtocol()
        )
    }
}

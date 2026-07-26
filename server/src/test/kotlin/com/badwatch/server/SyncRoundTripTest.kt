package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the full contract the watch depends on: encode with `:core`'s serializers,
 * POST, and read the aggregate back. If the watch and server ever disagree about the wire
 * format, this test is where it surfaces.
 */
class SyncRoundTripTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun uploadedSessionsAppearInTheDashboardAggregate() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("sessions"))
        val sessions = listOf(
            SyntheticSessions.session(startedAtMillis = 1_000_000L, rallies = 8, shotsPerRally = 6),
            SyntheticSessions.session(startedAtMillis = 90_000_000L, rallies = 12, shotsPerRally = 9)
        )

        testApplication {
            application { badWatchModule(repository, token = null) }

            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(BadWatchJson.encodeToString(SyncEnvelope.serializer(), SyncEnvelope(sessions = sessions)))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val ack = BadWatchJson.decodeFromString(SyncResponse.serializer(), response.bodyAsText())
            assertThat(ack.accepted).hasSize(2)
            assertThat(ack.rejected).isEmpty()

            val dashboard = client.get("/api/v1/dashboard").bodyAsText()
            val data = BadWatchJson.decodeFromString(DashboardData.serializer(), dashboard)

            assertThat(data.sessionCount).isEqualTo(2)
            // Assert the aggregate agrees with its inputs rather than with the generator's
            // internals, so tuning the synthetic data cannot silently break this test.
            assertThat(data.totalShots)
                .isEqualTo(sessions.sumOf { it.session.summary.totalShots })
            assertThat(data.rallyHistogram.sumOf { it.count })
                .isEqualTo(sessions.sumOf { it.rallyProfile.rallyCount })
            assertThat(data.sessions.first().startedAtMillis).isEqualTo(90_000_000L)
            assertThat(data.sessions.map { it.id })
                .containsExactlyElementsIn(sessions.map { it.session.id })
        }
    }

    @Test
    fun rejectsEveryRecordInAnUnknownEnvelopeSchemaWithoutCreatingARetryLoop() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("sessions"))
        val export = SyntheticSessions.session(1_000L, 3, 5)

        testApplication {
            application { badWatchModule(repository, token = null) }

            val payload = BadWatchJson.encodeToString(
                SyncEnvelope.serializer(),
                SyncEnvelope(schemaVersion = 99, sessions = listOf(export))
            )
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).isEmpty()
            assertThat(acknowledgement.rejected[export.session.id])
                .contains("Unsupported envelope schema 99")
            assertThat(repository.all()).isEmpty()
        }
    }

    @Test
    fun malformedSessionSyncJsonReturnsBadRequestWithoutWriting() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("malformed-sessions"))

        testApplication {
            application { badWatchModule(repository, token = null) }

            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"schemaVersion":1,"sessions":[""")
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("Invalid session sync JSON")
            assertThat(repository.all()).isEmpty()
        }
    }

    @Test
    fun rejectsUploadsWithoutTheConfiguredToken() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("sessions"))
        val body = BadWatchJson.encodeToString(
            SyncEnvelope.serializer(),
            SyncEnvelope(sessions = listOf(SyntheticSessions.session(1_000L, 3, 5)))
        )

        testApplication {
            application { badWatchModule(repository, token = "s3cret") }

            assertThat(
                client.post("/api/v1/sessions") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status
            ).isEqualTo(HttpStatusCode.Unauthorized)

            assertThat(
                client.post("/api/v1/sessions") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer s3cret")
                    setBody(body)
                }.status
            ).isEqualTo(HttpStatusCode.OK)

            assertThat(repository.all()).hasSize(1)
        }
    }

    @Test
    fun sessionsSurviveARestart() {
        val directory = temporaryFolder.newFolder("sessions")
        val export: SessionExport = SyntheticSessions.session(5_000L, 4, 7)

        SessionRepository(directory).save(export)

        // A fresh repository reads from disk, proving persistence is not just the cache.
        val reopened = SessionRepository(directory)
        assertThat(reopened.all()).hasSize(1)
        assertThat(reopened.find(export.session.id)?.session?.summary?.totalShots)
            .isEqualTo(export.session.summary.totalShots)
        assertThat(reopened.find(export.session.id)?.session?.shots)
            .isEqualTo(export.session.shots)
    }

    @Test
    fun dashboardEndpointSupportsContextFiltersAndRejectsUnknownValues() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("filtered-sessions"))
        fun session(
            start: Long,
            mode: ActivityMode,
            completion: SessionCompletion
        ): SessionExport = SyntheticSessions.session(start, 3, 5).copy(
            context = SessionContext(
                activityMode = mode,
                completion = completion,
                recordingQuality = RecordingQuality.Complete
            )
        )
        repository.save(session(1_000L, ActivityMode.SinglesMatch, SessionCompletion.Completed))
        repository.save(session(2_000L, ActivityMode.SinglesMatch, SessionCompletion.StoppedEarly))
        repository.save(session(3_000L, ActivityMode.DoublesMatch, SessionCompletion.Completed))

        testApplication {
            application { badWatchModule(repository, token = null) }

            val response = client.get(
                "/api/v1/dashboard?activityMode=singlesmatch" +
                    "&completion=completed&recordingQuality=complete"
            )
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val dashboard = BadWatchJson.decodeFromString(
                DashboardData.serializer(),
                response.bodyAsText()
            )
            assertThat(dashboard.sessionCount).isEqualTo(1)
            assertThat(dashboard.sessions.single().context.activityMode)
                .isEqualTo(ActivityMode.SinglesMatch)
            assertThat(dashboard.appliedFilter.activityModes)
                .containsExactly(ActivityMode.SinglesMatch)
            // Groups describe the whole corpus, so a client can offer another comparison.
            assertThat(dashboard.comparisonGroups.sumOf { it.sessionCount }).isEqualTo(3)

            val invalid = client.get("/api/v1/dashboard?activityMode=quidditch")
            assertThat(invalid.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(invalid.bodyAsText()).contains("Unknown activityMode")
        }
    }
}

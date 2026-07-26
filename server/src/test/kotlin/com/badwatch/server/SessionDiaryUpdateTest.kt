package com.badwatch.server

import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.BodySide
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.DraftLevel
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.ReportedSoreness
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionConditionsSnapshot
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionDiaryLimits
import com.badwatch.core.sync.SessionEquipmentSnapshot
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.TrimCorrectionRevision
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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

class SessionDiaryUpdateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun authenticatedDiaryUpdateRoundTripsAndPreservesRecordedAndCorrectionData() = runBlocking {
        val directory = temporaryFolder.newFolder("diary-update")
        val repository = SessionRepository(directory)
        val original = reviewedSession()
        repository.save(original)
        val request = SessionDiaryUpdateRequest(
            activityMode = ActivityMode.DoublesMatch,
            comparisonTag = "  Tuesday league  ",
            opponent = "  Northside  ",
            partner = "Sam",
            hall = "Jean Bouin",
            goal = "Keep serves short",
            completion = SessionCompletion.Completed,
            recordingQuality = RecordingQuality.Partial,
            rpe = 8,
            sorenessReviewed = true,
            notes = "  Tight final game  ",
            equipment = SessionEquipmentSnapshot(
                racket = " Astrox 88D Pro ",
                string = "BG80",
                stringTensionLbs = 27.5f,
                shoes = "Power Cushion 65Z"
            ),
            conditions = SessionConditionsSnapshot(
                shuttleBrand = "AS-30",
                shuttleSpeed = "77",
                temperatureCelsius = 19.5f,
                draft = DraftLevel.Light
            )
        )
        val body = BadWatchJson.encodeToString(SessionDiaryUpdateRequest.serializer(), request)
        val path = "/api/v1/sessions/${original.session.id}/diary"

        testApplication {
            application { badWatchModule(repository, token = "owner") }

            val unauthorisedDetail = client.get(
                "/api/v1/sessions/${original.session.id}/detail"
            )
            assertThat(unauthorisedDetail.status).isEqualTo(HttpStatusCode.Unauthorized)

            val unauthorised = client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(unauthorised.status).isEqualTo(HttpStatusCode.Unauthorized)
            assertThat(repository.find(original.session.id)).isEqualTo(original)

            val response = client.put(path) {
                header(HttpHeaders.Authorization, "Bearer owner")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val updated = BadWatchJson.decodeFromString(
                SessionExport.serializer(),
                response.bodyAsText()
            )

            assertThat(updated.session).isEqualTo(original.session)
            assertThat(updated.rallyProfile).isEqualTo(original.rallyProfile)
            assertThat(updated.corrections).isEqualTo(original.corrections)
            assertThat(updated.notes).isEqualTo(original.notes)
            assertThat(updated.profile).isEqualTo(original.profile)
            assertThat(updated.diaryRevision).isEqualTo(original.diaryRevision + 1L)
            assertThat(updated.diaryBaseRevision).isEqualTo(updated.diaryRevision)
            assertThat(updated.context.activityMode).isEqualTo(ActivityMode.DoublesMatch)
            assertThat(updated.context.comparisonTag).isEqualTo("Tuesday league")
            assertThat(updated.context.opponent).isEqualTo("Northside")
            assertThat(updated.context.partner).isEqualTo("Sam")
            assertThat(updated.context.hall).isEqualTo("Jean Bouin")
            assertThat(updated.context.goal).isEqualTo("Keep serves short")
            assertThat(updated.context.completion).isEqualTo(SessionCompletion.Completed)
            assertThat(updated.context.recordingQuality).isEqualTo(RecordingQuality.Partial)
            assertThat(updated.context.diaryReviewStatus).isEqualTo(DiaryReviewStatus.Reviewed)
            assertThat(updated.context.equipment).isEqualTo(
                request.equipment.copy(racket = "Astrox 88D Pro")
            )
            assertThat(updated.context.conditions).isEqualTo(request.conditions)
            assertThat(updated.report.rpe).isEqualTo(8)
            assertThat(updated.report.notes).isEqualTo("Tight final game")
            assertThat(updated.report.soreness).isEqualTo(original.report.soreness)
            assertThat(updated.report.sorenessReviewed).isTrue()

            // Dashboard analytics contains the new typed snapshots, not just the detail API.
            val dashboard = client.get("/api/v1/dashboard") {
                header(HttpHeaders.Authorization, "Bearer owner")
            }
            val analytics = BadWatchJson.decodeFromString(
                DashboardData.serializer(),
                dashboard.bodyAsText()
            )
            assertThat(analytics.sessions.single().context.equipment)
                .isEqualTo(updated.context.equipment)
            assertThat(analytics.sessions.single().context.conditions)
                .isEqualTo(updated.context.conditions)

            val detailResponse = client.get(
                "/api/v1/sessions/${original.session.id}/detail"
            ) {
                header(HttpHeaders.Authorization, "Bearer owner")
            }
            assertThat(detailResponse.status).isEqualTo(HttpStatusCode.OK)
            val detail = BadWatchJson.decodeFromString(
                SessionDetailData.serializer(),
                detailResponse.bodyAsText()
            )
            assertThat(detail.raw).isEqualTo(updated)
            assertThat(detail.reviewed.session.summary.totalShots)
                .isEqualTo(detail.reviewed.effectiveMetrics.correctedDetectedHitCount)
            assertThat(detail.reviewed.session.summary.durationMillis)
                .isEqualTo(detail.reviewed.effectiveMetrics.window.durationMillis)
            assertThat(detail.reviewed.rallyProfile)
                .isNotEqualTo(detail.raw.rallyProfile)
        }

        // A new repository instance proves the endpoint used the durable atomic upsert path.
        assertThat(SessionRepository(directory).find(original.session.id))
            .isEqualTo(repository.find(original.session.id))
    }

    @Test
    fun invalidDiaryBoundsReturnBadRequestWithoutChangingTheSession() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("invalid-diary"))
        val original = reviewedSession().copy(report = PostSessionReport())
        repository.save(original)
        val path = "/api/v1/sessions/${original.session.id}/diary"

        testApplication {
            application { badWatchModule(repository, token = "owner") }

            val overlong = "x".repeat(SessionDiaryLimits.COMPARISON_TAG_MAX_LENGTH + 1)
            val invalidBodies = listOf(
                """{"comparisonTag":"$overlong"}""",
                """{"rpe":11}""",
                """{"equipment":{"stringTensionLbs":99}}""",
                """{"conditions":{"temperatureCelsius":-31}}""",
                """{"session":{"id":"forged"}}"""
            )
            invalidBodies.forEach { invalidBody ->
                val response = client.put(path) {
                    header(HttpHeaders.Authorization, "Bearer owner")
                    contentType(ContentType.Application.Json)
                    setBody(invalidBody)
                }
                assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
                assertThat(repository.find(original.session.id)).isEqualTo(original)
            }

            val missing = client.put("/api/v1/sessions/missing/diary") {
                header(HttpHeaders.Authorization, "Bearer owner")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertThat(missing.status).isEqualTo(HttpStatusCode.NotFound)
        }
    }

    private fun reviewedSession(): SessionExport {
        val base = SyntheticSessions.session(5_000L, rallies = 4, shotsPerRally = 7)
        val provenance = CorrectionProvenance(
            revisionId = "review-1",
            actor = CorrectionActor.Player,
            recordedAtMillis = 99_000L,
            reason = "Video review"
        )
        return base.copy(
            diaryBaseRevision = 0L,
            notes = mapOf("legacy" to "preserve"),
            context = SessionContext(activityMode = ActivityMode.SinglesMatch),
            report = PostSessionReport(
                rpe = 6,
                soreness = listOf(ReportedSoreness(BodyArea.Shoulder, 2, BodySide.Right)),
                notes = "Original note",
                sorenessReviewed = true
            ),
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = listOf(base.session.shots.first().id),
                        missedHitCount = 1,
                        provenance = provenance
                    )
                ),
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 100L,
                        trimFromEndMillis = 200L,
                        provenance = provenance.copy(revisionId = "trim-1")
                    )
                )
            )
        )
    }
}

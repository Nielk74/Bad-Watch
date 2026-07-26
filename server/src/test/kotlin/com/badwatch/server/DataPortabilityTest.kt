package com.badwatch.server

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.Rally
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.BodySide
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.DraftLevel
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.ReportedSoreness
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionConditionsSnapshot
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionEquipmentSnapshot
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.TrimCorrectionRevision
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

class DataPortabilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun archiveEncodingIsDeterministicAndLossless() {
        val firstSession = sessionExport()
        val secondSession = firstSession.copy(
            session = firstSession.session.copy(
                id = "session-b",
                summary = firstSession.session.summary.copy(
                    // Intentionally reverse insertion order; canonical JSON sorts map keys.
                    shotCounts = linkedMapOf(ShotType.Smash to 1, ShotType.Clear to 1)
                )
            )
        )
        val capture = captureExport()

        val normal = DataPortability.encodeArchive(
            DataPortability.archive(listOf(firstSession, secondSession), listOf(capture))
        )
        val reversed = DataPortability.encodeArchive(
            DataPortability.archive(listOf(secondSession, firstSession), listOf(capture))
        )

        assertThat(reversed).isEqualTo(normal)
        assertThat(normal).doesNotContain("exportedAt")
        assertThat(normal).endsWith("\n")
        val decoded = BadWatchJson.decodeFromString(BadWatchArchive.serializer(), normal)
        assertThat(decoded.sessions.map { it.session.id })
            .containsExactly("session-a", "session-b").inOrder()
        assertThat(decoded.sessions.first().context).isEqualTo(firstSession.context)
        assertThat(decoded.sessions.first().corrections).isEqualTo(firstSession.corrections)
        assertThat(decoded.captures.single()).isEqualTo(capture)
    }

    @Test
    fun archiveNeverContainsRawCaptureWithoutRecordingTimeConsent() {
        val localOnly = captureExport().copy(dataUse = CaptureDataUse.LocalOnly)

        val exported = DataPortability.archive(emptyList(), listOf(localOnly))
        val forgedRestore = BadWatchArchive(
            format = BadWatchArchive.FORMAT,
            archiveVersion = BadWatchArchive.ARCHIVE_VERSION,
            schemaVersion = SessionExport.SCHEMA_VERSION,
            captures = listOf(localOnly)
        )

        assertThat(exported.captures).isEmpty()
        assertThat(DataPortability.validationErrors(forgedRestore).joinToString())
            .contains("not consented")
    }

    @Test
    fun restoreValidationRejectsIdsThatCouldEscapeTheDataDirectory() {
        val valid = sessionExport()
        val unsafe = valid.copy(session = valid.session.copy(id = "../outside"))
        val archive = DataPortability.archive(listOf(unsafe), emptyList())

        assertThat(DataPortability.validationErrors(archive).joinToString())
            .contains("unsafe id")
    }

    @Test
    fun authenticatedArchiveRestoreNormalizesLineageAndIsIdempotent() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("restore-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("restore-captures"))
        val archive = DataPortability.archive(listOf(sessionExport()), listOf(captureExport()))
        val body = DataPortability.encodeArchive(archive)
        val normalizedSession = archive.sessions.single().copy(
            diaryBaseRevision = archive.sessions.single().diaryRevision
        )

        testApplication {
            application { badWatchModule(sessions, token = "owner", captureRepository = captures) }

            assertThat(
                client.post("/api/v1/import/archive") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status
            ).isEqualTo(HttpStatusCode.Unauthorized)

            val first = client.post("/api/v1/import/archive") {
                header(HttpHeaders.Authorization, "Bearer owner")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(first.status).isEqualTo(HttpStatusCode.OK)
            val created = BadWatchJson.decodeFromString(
                ArchiveRestoreResponse.serializer(),
                first.bodyAsText()
            )
            assertThat(created.sessions.created).isEqualTo(1)
            assertThat(created.captures.created).isEqualTo(1)
            assertThat(sessions.all().single()).isEqualTo(normalizedSession)
            assertThat(captures.all().single()).isEqualTo(archive.captures.single())

            val second = client.post("/api/v1/import/archive") {
                header(HttpHeaders.Authorization, "Bearer owner")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            val unchanged = BadWatchJson.decodeFromString(
                ArchiveRestoreResponse.serializer(),
                second.bodyAsText()
            )
            assertThat(unchanged.sessions.unchanged).isEqualTo(1)
            assertThat(unchanged.captures.unchanged).isEqualTo(1)
            assertThat(sessions.all()).hasSize(1)
            assertThat(captures.all()).hasSize(1)

            val downloaded = client.get("/api/v1/export/archive") {
                header(HttpHeaders.Authorization, "Bearer owner")
            }
            assertThat(downloaded.status).isEqualTo(HttpStatusCode.OK)
            assertThat(downloaded.headers[HttpHeaders.ContentDisposition])
                .contains("badwatch-archive.json")
            assertThat(downloaded.bodyAsText()).isEqualTo(
                DataPortability.encodeArchive(archive.copy(sessions = listOf(normalizedSession)))
            )

            val changedSession = normalizedSession.copy(
                report = normalizedSession.report.copy(notes = "Reviewed again"),
                diaryRevision = normalizedSession.diaryRevision + 1L,
                diaryBaseRevision = normalizedSession.diaryRevision
            )
            val changedBody = DataPortability.encodeArchive(
                DataPortability.archive(listOf(changedSession), archive.captures)
            )
            val replacedResponse = client.post("/api/v1/import/archive") {
                header(HttpHeaders.Authorization, "Bearer owner")
                contentType(ContentType.Application.Json)
                setBody(changedBody)
            }
            val replaced = BadWatchJson.decodeFromString(
                ArchiveRestoreResponse.serializer(),
                replacedResponse.bodyAsText()
            )
            assertThat(replaced.sessions.replaced).isEqualTo(1)
            assertThat(replaced.captures.unchanged).isEqualTo(1)
            assertThat(sessions.all().single().report.notes).isEqualTo("Reviewed again")
            assertThat(sessions.all().single().diaryBaseRevision)
                .isEqualTo(changedSession.diaryRevision)
        }
    }

    @Test
    fun invalidArchiveIsRejectedBeforeAnyRecordIsWritten() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("invalid-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("invalid-captures"))
        val session = sessionExport()
        val invalid = DataPortability.archive(listOf(session, session), emptyList())

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }

            val response = client.post("/api/v1/import/archive") {
                contentType(ContentType.Application.Json)
                setBody(BadWatchJson.encodeToString(BadWatchArchive.serializer(), invalid))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("Duplicate session id")
            assertThat(sessions.all()).isEmpty()
            assertThat(captures.all()).isEmpty()

            val missingHeader = client.post("/api/v1/import/archive") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertThat(missingHeader.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(sessions.all()).isEmpty()
        }
    }

    @Test
    fun sessionCsvMatchesTheReviewedGoldenFile() {
        val csv = SessionCsvExporter.encode(listOf(sessionExport()))
        val golden = checkNotNull(javaClass.getResource("/golden/session-export.csv"))
            .readText()

        assertThat(csv.replace("\r\n", "\n")).isEqualTo(golden)
        assertThat(csv).contains("\r\n")
        assertThat(csv).endsWith("\r\n")
    }

    @Test
    fun reviewedCsvExportsImmutableRecoveryCoverageWithDeterministicSeconds() {
        val base = sessionExport()
        val recovered = base.copy(
            session = base.session.copy(
                processAbsenceGaps = listOf(
                    ProcessAbsenceGap(
                        startedAtMillis = base.session.startedAtMillis + 30_000L,
                        endedAtMillis = base.session.startedAtMillis + 40_000L
                    )
                )
            )
        )

        val csv = SessionCsvExporter.encode(listOf(recovered))

        assertThat(csv).contains(
            "effective_duration_seconds,process_absence_count,unobserved_seconds," +
                "observed_seconds,model_detected_hits"
        )
        assertThat(csv).contains(",Light,65.5,64,1,10,54,2,2,")
        assertThat(csv).contains(",Complete,")
    }

    @Test
    fun csvEndpointIsAuthenticatedAndUsesAnAttachmentFilename() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("csv-sessions"))
        sessions.save(sessionExport())

        testApplication {
            application { badWatchModule(sessions, token = "owner") }

            assertThat(client.get("/api/v1/export/sessions.csv").status)
                .isEqualTo(HttpStatusCode.Unauthorized)
            val response = client.get("/api/v1/export/sessions.csv") {
                header(HttpHeaders.Authorization, "Bearer owner")
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.headers[HttpHeaders.ContentType]).startsWith("text/csv")
            assertThat(response.headers[HttpHeaders.ContentDisposition])
                .contains("badwatch-sessions.csv")
            assertThat(response.bodyAsText()).isEqualTo(SessionCsvExporter.encode(sessions.all()))
        }
    }

    private fun sessionExport(): SessionExport {
        val startedAt = 1_700_000_000_000L
        val endedAt = startedAt + 65_500L
        val shots = listOf(
            shot("hit-1", ShotType.Clear, startedAt + 10_000L, 142f),
            shot("hit-2", ShotType.Smash, startedAt + 20_000L, 149f)
        )
        val summary = TrainingSummary(
            totalShots = 2,
            shotCounts = linkedMapOf(ShotType.Clear to 1, ShotType.Smash to 1),
            durationMillis = 65_500L,
            averageHeartRate = 145.5f,
            maxHeartRate = 166f,
            recoveryScore = 0f,
            fatigueScore = 0f,
            effortScore = 0f,
            heartRateZoneHistogram = mapOf(HeartRateZone.Tempo to 2),
            heartRateSampleCount = 52,
            heartRateCoverage = 0.8f,
            averageHeartRateReserve = 0.68f,
            cardiovascularLoad = 0.74f
        )
        val provenance = CorrectionProvenance(
            revisionId = "review-1",
            actor = CorrectionActor.Player,
            recordedAtMillis = endedAt + 1_000L,
            reason = "Reviewed against memory"
        )
        return SessionExport(
            deviceId = "watch-1",
            appVersion = "1.2.3",
            profile = PlayerProfile(),
            session = TrainingSession(
                id = "session-a",
                startedAtMillis = startedAt,
                endedAtMillis = endedAt,
                summary = summary,
                shots = shots
            ),
            rallyProfile = RallyProfile(
                rallies = listOf(
                    Rally(
                        index = 0,
                        startMillis = shots.first().timestampMillis,
                        endMillis = shots.last().timestampMillis,
                        shotCount = 2,
                        shotCounts = summary.shotCounts,
                        peakAngularVelocity = 8f,
                        averageHeartRate = 145.5f,
                        restBeforeMillis = 0L
                    )
                ),
                totalWorkMillis = 10_000L,
                totalRestMillis = 55_500L
            ),
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                comparisonTag = "Tuesday league",
                opponent = "Doe, Jane",
                hall = "Gym \"A\"",
                goal = "Hold the base",
                completion = SessionCompletion.Completed,
                recordingQuality = RecordingQuality.Complete,
                equipment = SessionEquipmentSnapshot(
                    racket = "Astrox 88D Pro",
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
            ),
            report = PostSessionReport(
                rpe = 7,
                soreness = listOf(
                    ReportedSoreness(
                        bodyArea = BodyArea.Shoulder,
                        severity = 3,
                        side = BodySide.Right
                    )
                ),
                notes = "Good \"length\", steady",
                sorenessReviewed = true
            ),
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = listOf("hit-2"),
                        missedHitCount = 2,
                        provenance = provenance
                    )
                ),
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 1_000L,
                        trimFromEndMillis = 500L,
                        provenance = provenance.copy(revisionId = "trim-1")
                    )
                )
            )
        )
    }

    private fun shot(id: String, type: ShotType, timestamp: Long, heartRate: Float) = ShotEvent(
        id = id,
        type = type,
        timestampMillis = timestamp,
        confidence = 0.7f,
        peakAngularVelocity = 7f,
        heartRateBpm = heartRate,
        swingDurationMillis = 180L
    )

    private fun captureExport(): CaptureExport = CaptureExport(
        deviceId = "watch-1",
        participantId = "participant-1",
        appVersion = "1.2.3",
        profile = PlayerProfile(),
        capture = CaptureSession(
            id = "capture-a",
            startedAtMillis = 1_700_000_100_000L,
            endedAtMillis = 1_700_000_110_000L,
            label = ShotType.Smash,
            swings = emptyList()
        ),
        samplingRateHz = 100,
        dataUse = CaptureDataUse.SelfHostedModelTraining,
        protocol = CaptureProtocol()
    )
}

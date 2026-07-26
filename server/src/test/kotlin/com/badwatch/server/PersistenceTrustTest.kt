package com.badwatch.server

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureEnvelope
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncEnvelope
import com.badwatch.core.sync.SyncResponse
import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersistenceTrustTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun staleWatchUploadIsAcceptedWithoutClobberingNewerDiaryOrCorrections() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("stale-watch"))
        val base = SyntheticSessions.session(10_000L, rallies = 3, shotsPerRally = 4)
        val first = correction(base, "review-1", missed = 1)
        val second = correction(base, "review-2", missed = 2)
        val stored = base.copy(
            context = SessionContext(activityMode = ActivityMode.SinglesMatch, hall = "New hall"),
            report = PostSessionReport(notes = "Newer server diary"),
            corrections = SessionCorrections(hitRevisions = listOf(first, second)),
            diaryRevision = 2L,
            diaryBaseRevision = 2L
        )
        repository.save(stored)
        val stale = base.copy(
            context = SessionContext(activityMode = ActivityMode.SinglesMatch, hall = "Old hall"),
            report = PostSessionReport(notes = "Stale watch diary"),
            corrections = SessionCorrections(hitRevisions = listOf(first)),
            diaryRevision = 1L,
            diaryBaseRevision = 1L
        )

        testApplication {
            application { badWatchModule(repository, token = null) }
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(syncBody(stale))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).containsExactly(base.session.id)
            assertThat(acknowledgement.rejected).isEmpty()
        }

        assertThat(repository.find(base.session.id)).isEqualTo(stored)
    }

    @Test
    fun staleOfflineBranchCannotLeapfrogBrowserEditAndOnlyThatIdIsRejected() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("diary-lineage-conflict"))
        val base = SyntheticSessions.session(15_000L, rallies = 3, shotsPerRally = 4)
            .copy(
                report = PostSessionReport(notes = "Acknowledged watch diary"),
                diaryRevision = 1L,
                diaryBaseRevision = 1L
            )
        repository.save(base)
        val browser = checkNotNull(
            repository.updateDiary(base.session.id, 1L) {
                it.copy(report = PostSessionReport(notes = "Browser edit"))
            }
        )
        val staleWatch = base.copy(
            report = PostSessionReport(notes = "Second offline watch edit"),
            diaryRevision = 3L,
            // Both offline edits descend from the last server acknowledgement, revision 1.
            diaryBaseRevision = 1L
        )
        val independent = SyntheticSessions.session(16_000L, rallies = 2, shotsPerRally = 3)

        testApplication {
            application { badWatchModule(repository, token = null) }
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(syncBody(staleWatch, independent))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).containsExactly(independent.session.id)
            assertThat(acknowledgement.rejected[base.session.id])
                .contains("divergent diary branch based on revision 1")
        }

        assertThat(repository.find(base.session.id)).isEqualTo(browser)
        assertThat(repository.find(independent.session.id))
            .isEqualTo(independent.copy(diaryBaseRevision = 0L))
    }

    @Test
    fun multipleOfflineDiaryEditsMergeWhenTheyShareTheUnchangedServerBase() {
        val repository = SessionRepository(temporaryFolder.newFolder("offline-diary-merge"))
        val base = SyntheticSessions.session(17_000L, rallies = 3, shotsPerRally = 4)
            .copy(
                report = PostSessionReport(notes = "Server head"),
                diaryRevision = 4L,
                diaryBaseRevision = 4L
            )
        repository.save(base)
        val afterSeveralOfflineEdits = base.copy(
            context = SessionContext(activityMode = ActivityMode.DoublesMatch, hall = "Away hall"),
            report = PostSessionReport(notes = "Third offline edit"),
            diaryRevision = 7L,
            diaryBaseRevision = 4L
        )

        assertThat(repository.upsert(afterSeveralOfflineEdits)).isEqualTo(StoreResult.Replaced)

        val merged = repository.find(base.session.id)!!
        assertThat(merged.context).isEqualTo(afterSeveralOfflineEdits.context)
        assertThat(merged.report).isEqualTo(afterSeveralOfflineEdits.report)
        assertThat(merged.diaryRevision).isEqualTo(7L)
        assertThat(merged.diaryBaseRevision).isEqualTo(7L)
    }

    @Test
    fun legacyStoredPayloadWithoutLineageDecodesAndCanStartBrowserVersioning() {
        val directory = temporaryFolder.newFolder("legacy-diary-lineage")
        val legacy = SyntheticSessions.session(18_000L, rallies = 2, shotsPerRally = 3)
            .copy(context = SessionContext(hall = "Legacy hall"))
        val encoded = BadWatchJson.encodeToString(SessionExport.serializer(), legacy)
            .replace(",\"diaryBaseRevision\":null", "")
        assertThat(encoded).doesNotContain("diaryBaseRevision")
        File(directory, "${legacy.session.id}.json").writeText(encoded)

        val repository = SessionRepository(directory)
        assertThat(repository.find(legacy.session.id)?.diaryBaseRevision).isNull()
        val updated = repository.updateDiary(legacy.session.id, baseDiaryRevision = null) {
            it.copy(report = PostSessionReport(notes = "First versioned edit"))
        }!!

        assertThat(updated.diaryRevision).isEqualTo(1L)
        assertThat(updated.diaryBaseRevision).isEqualTo(1L)
        assertThat(SessionRepository(directory).find(legacy.session.id)).isEqualTo(updated)
    }

    @Test
    fun independentDiaryAndCorrectionAdvancesMergeWithoutLosingEither() {
        val repository = SessionRepository(temporaryFolder.newFolder("domain-merge"))
        val base = SyntheticSessions.session(20_000L, rallies = 3, shotsPerRally = 4)
        val first = correction(base, "review-1", missed = 1)
        val second = correction(base, "review-2", missed = 2)
        val stored = base.copy(
            report = PostSessionReport(notes = "Latest diary"),
            diaryRevision = 5L,
            diaryBaseRevision = 5L,
            corrections = SessionCorrections(hitRevisions = listOf(first))
        )
        repository.save(stored)

        val result = repository.upsert(
            base.copy(
                report = PostSessionReport(notes = "Older diary"),
                diaryRevision = 4L,
                diaryBaseRevision = 4L,
                corrections = SessionCorrections(hitRevisions = listOf(first, second))
            )
        )

        assertThat(result).isEqualTo(StoreResult.Replaced)
        val merged = repository.find(base.session.id)!!
        assertThat(merged.report.notes).isEqualTo("Latest diary")
        assertThat(merged.diaryRevision).isEqualTo(5L)
        assertThat(merged.corrections.hitRevisions).containsExactly(first, second).inOrder()
        assertThat(merged.session).isEqualTo(base.session)
        assertThat(merged.rallyProfile).isEqualTo(base.rallyProfile)
    }

    @Test
    fun divergentCorrectionBranchIsRejectedWithoutBlockingIndependentBatchRecords() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("correction-conflict"))
        val base = SyntheticSessions.session(30_000L, rallies = 3, shotsPerRally = 4)
        val first = correction(base, "review-1", missed = 1)
        val stored = base.copy(
            diaryBaseRevision = 0L,
            corrections = SessionCorrections(
                hitRevisions = listOf(first, correction(base, "server-branch", missed = 2))
            )
        )
        repository.save(stored)
        val incoming = base.copy(
            diaryBaseRevision = 0L,
            corrections = SessionCorrections(
                hitRevisions = listOf(first, correction(base, "watch-branch", missed = 3))
            )
        )
        val independent = SyntheticSessions.session(35_000L, rallies = 2, shotsPerRally = 3)

        testApplication {
            application { badWatchModule(repository, token = null) }
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(syncBody(incoming, independent))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).containsExactly(independent.session.id)
            assertThat(acknowledgement.rejected[incoming.session.id])
                .contains("divergent hit-correction history")
        }
        assertThat(repository.find(base.session.id)).isEqualTo(stored)
        assertThat(repository.find(independent.session.id))
            .isEqualTo(independent.copy(diaryBaseRevision = 0L))
    }

    @Test
    fun immutableEvidenceCollisionIsTheOnlyKindOfPermanentRecordRejection() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("raw-conflict"))
        val stored = SyntheticSessions.session(40_000L, rallies = 3, shotsPerRally = 4)
            .copy(diaryBaseRevision = 0L)
        repository.save(stored)
        val colliding = stored.copy(
            session = stored.session.copy(
                summary = stored.session.summary.copy(
                    totalShots = stored.session.summary.totalShots + 1
                )
            )
        )

        testApplication {
            application { badWatchModule(repository, token = null) }
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(syncBody(colliding))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val acknowledgement = BadWatchJson.decodeFromString(
                SyncResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(acknowledgement.accepted).isEmpty()
            assertThat(acknowledgement.rejected[stored.session.id])
                .contains("immutable recorded evidence")
        }
        assertThat(repository.find(stored.session.id)).isEqualTo(stored)
    }

    @Test
    fun repositoryIoFailuresReturnRetryableServerErrorsInsteadOfRejectedMarkers() = runBlocking {
        val blockedParent = temporaryFolder.newFile("not-a-session-directory")
        val repository = SessionRepository(File(blockedParent, "sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("healthy-captures"))
        val export = SyntheticSessions.session(50_000L, rallies = 3, shotsPerRally = 4)

        testApplication {
            application { badWatchModule(repository, token = null, captureRepository = captures) }
            val response = client.post("/api/v1/sessions") {
                contentType(ContentType.Application.Json)
                setBody(syncBody(export))
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
            assertThat(response.bodyAsText()).doesNotContain("rejected")
        }
    }

    @Test
    fun captureRepositoryIoFailuresAlsoReturnRetryableServerErrors() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("healthy-sessions"))
        val blockedParent = temporaryFolder.newFile("not-a-capture-directory")
        val captures = CaptureRepository(File(blockedParent, "captures"))
        val export = captureExport()

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }
            val response = client.post("/api/v1/captures") {
                contentType(ContentType.Application.Json)
                setBody(
                    BadWatchJson.encodeToString(
                        CaptureEnvelope.serializer(),
                        CaptureEnvelope(captures = listOf(export))
                    )
                )
            }

            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
            assertThat(response.bodyAsText()).doesNotContain("rejected")
        }
    }

    @Test
    fun staleBrowserDiarySaveGetsConflictAndCannotOverwriteTheFirstSave() = runBlocking {
        val repository = SessionRepository(temporaryFolder.newFolder("browser-conflict"))
        val original = SyntheticSessions.session(60_000L, rallies = 3, shotsPerRally = 4)
            .copy(diaryRevision = 3L, diaryBaseRevision = 3L)
        repository.save(original)
        val path = "/api/v1/sessions/${original.session.id}/diary"

        testApplication {
            application { badWatchModule(repository, token = null) }
            val firstRequest = SessionDiaryUpdateRequest(
                baseDiaryRevision = 3L,
                activityMode = ActivityMode.DoublesMatch,
                notes = "First browser save"
            )
            val first = client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(BadWatchJson.encodeToString(SessionDiaryUpdateRequest.serializer(), firstRequest))
            }
            assertThat(first.status).isEqualTo(HttpStatusCode.OK)
            val saved = BadWatchJson.decodeFromString(SessionExport.serializer(), first.bodyAsText())
            assertThat(saved.diaryRevision).isEqualTo(4L)
            assertThat(saved.diaryBaseRevision).isEqualTo(4L)

            val stale = client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(
                    BadWatchJson.encodeToString(
                        SessionDiaryUpdateRequest.serializer(),
                        firstRequest.copy(notes = "Stale overwrite")
                    )
                )
            }
            assertThat(stale.status).isEqualTo(HttpStatusCode.Conflict)
        }

        val stored = repository.find(original.session.id)!!
        assertThat(stored.report.notes).isEqualTo("First browser save")
        assertThat(stored.diaryRevision).isEqualTo(4L)
    }

    @Test
    fun archiveConflictIsPreflightedBeforeAnyRecordIsWritten() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("archive-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("archive-captures"))
        val stored = SyntheticSessions.session(70_000L, rallies = 3, shotsPerRally = 4)
            .copy(diaryBaseRevision = 0L)
        sessions.save(stored)
        val newRecord = SyntheticSessions.session(80_000L, rallies = 2, shotsPerRally = 3)
        val collision = stored.copy(
            session = stored.session.copy(endedAtMillis = stored.session.endedAtMillis + 1L)
        )
        val body = DataPortability.encodeArchive(
            DataPortability.archive(listOf(newRecord, collision), emptyList())
        )

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }
            val response = client.post("/api/v1/import/archive") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
        }

        assertThat(sessions.find(newRecord.session.id)).isNull()
        assertThat(sessions.find(stored.session.id)).isEqualTo(stored)
    }

    @Test
    fun staleArchiveRestoreIsAnIdempotentNoOpForNewerDiaryAndCorrectionTail() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("stale-archive-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("stale-archive-captures"))
        val base = SyntheticSessions.session(90_000L, rallies = 3, shotsPerRally = 4)
        val first = correction(base, "review-1", missed = 1)
        val stored = base.copy(
            report = PostSessionReport(notes = "Newer stored diary"),
            diaryRevision = 4L,
            diaryBaseRevision = 4L,
            corrections = SessionCorrections(
                hitRevisions = listOf(first, correction(base, "review-2", missed = 2))
            )
        )
        sessions.save(stored)
        val stale = base.copy(
            report = PostSessionReport(notes = "Older archive diary"),
            diaryRevision = 2L,
            diaryBaseRevision = 2L,
            corrections = SessionCorrections(hitRevisions = listOf(first))
        )
        val body = DataPortability.encodeArchive(
            DataPortability.archive(listOf(stale), emptyList())
        )

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }
            val response = client.post("/api/v1/import/archive") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            val result = BadWatchJson.decodeFromString(
                ArchiveRestoreResponse.serializer(),
                response.bodyAsText()
            )
            assertThat(result.sessions.unchanged).isEqualTo(1)
        }

        assertThat(sessions.find(stored.session.id)).isEqualTo(stored)
    }

    @Test
    fun archiveLineageConflictIsPreflightedBeforeAnyRecordIsWritten() = runBlocking {
        val sessions = SessionRepository(temporaryFolder.newFolder("archive-lineage-sessions"))
        val captures = CaptureRepository(temporaryFolder.newFolder("archive-lineage-captures"))
        val acknowledged = SyntheticSessions.session(95_000L, rallies = 3, shotsPerRally = 4)
            .copy(diaryRevision = 1L, diaryBaseRevision = 1L)
        sessions.save(acknowledged)
        val browser = sessions.updateDiary(acknowledged.session.id, 1L) {
            it.copy(report = PostSessionReport(notes = "Browser wins"))
        }!!
        val staleBranch = acknowledged.copy(
            report = PostSessionReport(notes = "Offline branch"),
            diaryRevision = 3L,
            diaryBaseRevision = 1L
        )
        val newRecord = SyntheticSessions.session(96_000L, rallies = 2, shotsPerRally = 3)
        val body = DataPortability.encodeArchive(
            DataPortability.archive(listOf(newRecord, staleBranch), emptyList())
        )

        testApplication {
            application { badWatchModule(sessions, token = null, captureRepository = captures) }
            val response = client.post("/api/v1/import/archive") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertThat(response.status).isEqualTo(HttpStatusCode.Conflict)
            assertThat(response.bodyAsText()).contains("divergent diary branch")
        }

        assertThat(sessions.find(newRecord.session.id)).isNull()
        assertThat(sessions.find(acknowledged.session.id)).isEqualTo(browser)
    }

    private fun correction(
        export: SessionExport,
        revisionId: String,
        missed: Int
    ): HitCorrectionRevision = HitCorrectionRevision(
        falseHitIds = listOf(export.session.shots.first().id),
        missedHitCount = missed,
        provenance = CorrectionProvenance(
            revisionId = revisionId,
            actor = CorrectionActor.Player,
            recordedAtMillis = 100_000L + missed
        )
    )

    private fun syncBody(vararg exports: SessionExport): String = BadWatchJson.encodeToString(
        SyncEnvelope.serializer(),
        SyncEnvelope(sessions = exports.toList())
    )

    private fun captureExport(): CaptureExport = CaptureExport(
        deviceId = "device",
        participantId = "participant",
        appVersion = "test",
        profile = PlayerProfile(),
        capture = CaptureSession(
            id = "capture-io",
            startedAtMillis = 1_000L,
            endedAtMillis = 2_000L,
            label = ShotType.Smash,
            swings = emptyList()
        ),
        samplingRateHz = 100,
        dataUse = CaptureDataUse.SelfHostedModelTraining,
        protocol = CaptureProtocol()
    )
}

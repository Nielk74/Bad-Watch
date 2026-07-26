package com.badwatch.app.sync

import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.SessionStore
import com.badwatch.app.data.writeDurableAtomically
import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncResponse
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncWorkerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun explicitSessionAndCaptureRejectionsArePersistedAndNotUploadedAgain() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("captures"))
        sessions.save(sessionExport("session-1"))
        captures.save(captureExport("capture-1"))
        var sessionUploads = 0
        var captureUploads = 0

        val first = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { batch ->
                captureUploads++
                assertThat(batch.map { it.capture.id }).containsExactly("capture-1")
                Result.success(
                    SyncResponse(rejected = mapOf("capture-1" to "Capture consent invalid"))
                )
            },
            uploadSessions = { batch ->
                sessionUploads++
                assertThat(batch.map { it.session.id }).containsExactly("session-1")
                Result.success(
                    SyncResponse(rejected = mapOf("session-1" to "Session schema invalid"))
                )
            }
        )

        assertThat(first).isEqualTo(PendingSyncOutcome.Complete)
        assertThat(sessions.refresh().single().syncRejection?.reason)
            .isEqualTo("Session schema invalid")
        assertThat(captures.refresh().single().syncRejection?.reason)
            .isEqualTo("Capture consent invalid")

        val second = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { error("Rejected capture must not be retried unchanged") },
            uploadSessions = { error("Rejected session must not be retried unchanged") }
        )

        assertThat(second).isEqualTo(PendingSyncOutcome.Complete)
        assertThat(sessionUploads).isEqualTo(1)
        assertThat(captureUploads).isEqualTo(1)
    }

    @Test
    fun emptySessionAcknowledgementStillRequestsRetry() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("empty-ack-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("empty-ack-captures"))
        sessions.save(sessionExport("session-empty"))

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { Result.success(SyncResponse()) },
            uploadSessions = { Result.success(SyncResponse()) }
        )

        assertThat(outcome).isEqualTo(
            PendingSyncOutcome.IncompleteAcknowledgement(
                sessionIds = setOf("session-empty")
            )
        )
        assertThat(sessions.unsynced()).hasSize(1)
    }

    @Test
    fun captureFailureDoesNotPreventSessionAcceptance() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("secondary-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("secondary-captures"))
        sessions.save(sessionExport("session-ok"))
        captures.save(captureExport("capture-failed"))
        var captureFailure: Throwable? = null

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { Result.failure(IOException("offline capture endpoint")) },
            uploadSessions = {
                Result.success(SyncResponse(accepted = listOf("session-ok")))
            },
            onCaptureFailure = { captureFailure = it }
        )

        assertThat(outcome).isInstanceOf(PendingSyncOutcome.Failed::class.java)
        assertThat((outcome as PendingSyncOutcome.Failed).cause.message)
            .contains("offline capture endpoint")
        assertThat(captureFailure?.message).contains("offline capture endpoint")
        assertThat(sessions.refresh().single().synced).isTrue()
        assertThat(captures.unsynced()).hasSize(1)
    }

    @Test
    fun captureOnlyTransportFailureProducesRetryingFailure() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("capture-only-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("capture-only-captures"))
        captures.save(captureExport("capture-offline"))
        val failure = IOException("capture transport unavailable")
        var reported: Throwable? = null

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { Result.failure(failure) },
            uploadSessions = { error("There is no session batch") },
            onCaptureFailure = { reported = it }
        )

        assertThat(outcome).isEqualTo(PendingSyncOutcome.Failed(failure))
        assertThat(reported).isSameInstanceAs(failure)
        assertThat(captures.unsynced().map { it.export.capture.id })
            .containsExactly("capture-offline")
    }

    @Test
    fun emptyCaptureAcknowledgementRequestsRetry() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("empty-capture-ack-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("empty-capture-ack-captures"))
        captures.save(captureExport("capture-empty"))

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { Result.success(SyncResponse()) },
            uploadSessions = { error("There is no session batch") }
        )

        assertThat(outcome).isEqualTo(
            PendingSyncOutcome.IncompleteAcknowledgement(
                captureIds = setOf("capture-empty")
            )
        )
        assertThat(captures.unsynced().map { it.export.capture.id })
            .containsExactly("capture-empty")
    }

    @Test
    fun partialCaptureAcknowledgementPersistsAcceptedAndRetriesOnlyMissing() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("partial-capture-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("partial-captures"))
        captures.save(captureExport("capture-accepted"))
        captures.save(captureExport("capture-missing"))

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = {
                Result.success(SyncResponse(accepted = listOf("capture-accepted")))
            },
            uploadSessions = { error("There is no session batch") }
        )

        assertThat(outcome).isEqualTo(
            PendingSyncOutcome.IncompleteAcknowledgement(
                captureIds = setOf("capture-missing")
            )
        )
        val stored = captures.refresh().associateBy { it.export.capture.id }
        assertThat(stored.getValue("capture-accepted").synced).isTrue()
        assertThat(stored.getValue("capture-missing").synced).isFalse()
        assertThat(captures.unsynced().map { it.export.capture.id })
            .containsExactly("capture-missing")
    }

    @Test
    fun partialSessionAcknowledgementPersistsAcceptedAndRetriesOnlyMissing() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("partial-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("partial-session-captures"))
        sessions.save(sessionExport("session-accepted"))
        sessions.save(sessionExport("session-missing"))

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = { error("There is no capture batch") },
            uploadSessions = {
                Result.success(SyncResponse(accepted = listOf("session-accepted")))
            }
        )

        assertThat(outcome).isEqualTo(
            PendingSyncOutcome.IncompleteAcknowledgement(
                sessionIds = setOf("session-missing")
            )
        )
        val stored = sessions.refresh().associateBy { it.export.session.id }
        assertThat(stored.getValue("session-accepted").synced).isTrue()
        assertThat(stored.getValue("session-missing").synced).isFalse()
        assertThat(sessions.unsynced().map { it.export.session.id })
            .containsExactly("session-missing")
    }

    @Test
    fun completeMixedAcceptanceAndRejectionFinishesWithoutRetry() = runTest {
        val sessions = SessionStore(temporaryFolder.newFolder("mixed-sessions"))
        val captures = CaptureStore(temporaryFolder.newFolder("mixed-captures"))
        sessions.save(sessionExport("session-accepted"))
        sessions.save(sessionExport("session-rejected"))
        captures.save(captureExport("capture-accepted"))
        captures.save(captureExport("capture-rejected"))

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = {
                Result.success(
                    SyncResponse(
                        accepted = listOf("capture-accepted"),
                        rejected = mapOf("capture-rejected" to "Capture rejected")
                    )
                )
            },
            uploadSessions = {
                Result.success(
                    SyncResponse(
                        accepted = listOf("session-accepted"),
                        rejected = mapOf("session-rejected" to "Session rejected")
                    )
                )
            }
        )

        assertThat(outcome).isEqualTo(PendingSyncOutcome.Complete)
        assertThat(captures.unsynced()).isEmpty()
        assertThat(sessions.unsynced()).isEmpty()
        val storedCaptures = captures.refresh().associateBy { it.export.capture.id }
        val storedSessions = sessions.refresh().associateBy { it.export.session.id }
        assertThat(storedCaptures.getValue("capture-accepted").synced).isTrue()
        assertThat(storedCaptures.getValue("capture-rejected").syncRejection?.reason)
            .isEqualTo("Capture rejected")
        assertThat(storedSessions.getValue("session-accepted").synced).isTrue()
        assertThat(storedSessions.getValue("session-rejected").syncRejection?.reason)
            .isEqualTo("Session rejected")
    }

    @Test
    fun captureMarkerStorageFailureStillAcceptsSessionAndRequestsRetry() = runTest {
        var failCaptureMarkers = false
        val captures = CaptureStore(
            directory = temporaryFolder.newFolder("failed-marker-captures"),
            atomicWriter = { file, text ->
                if (failCaptureMarkers && file.name.endsWith(".synced")) {
                    throw IOException("capture marker write failed")
                }
                writeDurableAtomically(file, text)
            }
        )
        val sessions = SessionStore(temporaryFolder.newFolder("failed-marker-sessions"))
        captures.save(captureExport("capture-marker-failed"))
        sessions.save(sessionExport("session-still-accepted"))
        failCaptureMarkers = true
        var reported: Throwable? = null

        val outcome = syncPendingRecords(
            captureStore = captures,
            sessionStore = sessions,
            uploadCaptures = {
                Result.success(SyncResponse(accepted = listOf("capture-marker-failed")))
            },
            uploadSessions = {
                Result.success(SyncResponse(accepted = listOf("session-still-accepted")))
            },
            onCaptureFailure = { reported = it }
        )

        assertThat(outcome).isInstanceOf(PendingSyncOutcome.Failed::class.java)
        assertThat((outcome as PendingSyncOutcome.Failed).cause.message)
            .contains("capture marker write failed")
        assertThat(reported?.message).contains("capture marker write failed")
        assertThat(captures.unsynced().map { it.export.capture.id })
            .containsExactly("capture-marker-failed")
        assertThat(sessions.refresh().single().synced).isTrue()
    }

    private fun sessionExport(id: String): SessionExport = SessionExport(
        deviceId = "device",
        appVersion = "test",
        profile = PlayerProfile(),
        session = TrainingSession(
            id = id,
            startedAtMillis = 1_000L,
            endedAtMillis = 61_000L,
            summary = TrainingSummary(
                totalShots = 0,
                shotCounts = emptyMap(),
                durationMillis = 60_000L,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap()
            ),
            shots = emptyList()
        ),
        rallyProfile = RallyProfile.EMPTY
    )

    private fun captureExport(id: String): CaptureExport = CaptureExport(
        deviceId = "device",
        participantId = "participant",
        appVersion = "test",
        profile = PlayerProfile(),
        capture = CaptureSession(
            id = id,
            startedAtMillis = 2_000L,
            endedAtMillis = 3_000L,
            label = ShotType.Smash,
            swings = emptyList()
        ),
        samplingRateHz = 100,
        dataUse = CaptureDataUse.SelfHostedModelTraining,
        protocol = CaptureProtocol()
    )
}

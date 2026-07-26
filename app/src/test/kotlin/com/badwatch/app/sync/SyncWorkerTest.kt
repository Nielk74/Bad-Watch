package com.badwatch.app.sync

import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.SessionStore
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

        assertThat(outcome).isEqualTo(PendingSyncOutcome.EmptyAcknowledgement)
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

        assertThat(outcome).isEqualTo(PendingSyncOutcome.Complete)
        assertThat(captureFailure?.message).contains("offline capture endpoint")
        assertThat(sessions.refresh().single().synced).isTrue()
        assertThat(captures.unsynced()).hasSize(1)
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

package com.badwatch.app.domain

import com.badwatch.app.data.ActiveSessionJournal
import com.badwatch.app.data.ActiveSessionJournalEntry
import com.badwatch.app.data.SessionStore
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.model.Vector3
import com.badwatch.core.session.RallySegmenter
import com.badwatch.core.session.SessionRecorder
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionControllerReviewedInsightTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completedCorrectionRegeneratesInsightsFromReviewedTimestampedHits() = runTest {
        val root = temporaryFolder.newFolder("reviewed-insight")
        val store = SessionStore(File(root, "sessions"))
        val raw = exchangeExport()
        store.save(raw)

        // Reconciliation is the production path for a process that died after the session file
        // was durably written but before its active checkpoint was cleared.
        val checkpointRecorder = SessionRecorder(sessionId = raw.session.id)
        checkpointRecorder.start(raw.session.startedAtMillis)
        checkpointRecorder.onSample(
            SensorSample(
                timestampMillis = raw.session.startedAtMillis + 1L,
                gyro = Vector3(0f, 0f, 0f),
                heartRateBpm = null
            )
        )
        val journal = ActiveSessionJournal(File(root, "active/session.json"))
        journal.save(
            ActiveSessionJournalEntry(
                checkpoint = checkpointRecorder.checkpoint()!!,
                deviceId = raw.deviceId,
                appVersion = raw.appVersion,
                updatedAtMillis = raw.session.endedAtMillis
            )
        )

        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val controller = SessionController(
            sensorStream = EmptySensorStream,
            sessionStore = store,
            runtimeSettings = FakeRuntimeSettings,
            activeSessionJournal = journal,
            appVersion = "test",
            scope = controllerScope
        )

        assertThat(controller.start()).isEqualTo(SessionStartResult.AlreadySaved(raw))
        assertThat((controller.state.value as SessionState.Completed).insights.map { it.id })
            .contains("rest-ratio-high")

        val falseHitIds = raw.rallyProfile.rallies.map { rally ->
            raw.session.shots.first { it.timestampMillis == rally.endMillis }.id
        }
        val revised = controller.updateCompletedSession(
            context = raw.context,
            report = raw.report,
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = falseHitIds,
                        missedHitCount = 100,
                        provenance = CorrectionProvenance(
                            revisionId = "review",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = raw.session.endedAtMillis + 1L
                        )
                    )
                )
            )
        )!!

        val completed = controller.state.value as SessionState.Completed
        assertThat(completed.insights).isEmpty()
        assertThat(revised.session).isEqualTo(raw.session)
        assertThat(revised.rallyProfile).isEqualTo(raw.rallyProfile)
        assertThat(store.refresh().single().export.session).isEqualTo(raw.session)
        controllerScope.cancel()
    }

    private fun exchangeExport(): SessionExport {
        val start = 1_000L
        val shots = buildList {
            repeat(5) { exchange ->
                val exchangeStart = start + 1_000L + exchange * 11_000L
                repeat(2) { shot ->
                    val index = exchange * 2 + shot
                    add(
                        ShotEvent(
                            id = "hit-$index",
                            type = ShotType.Unknown,
                            timestampMillis = exchangeStart + shot * 1_000L,
                            confidence = 0.5f,
                            peakAngularVelocity = 5f,
                            heartRateBpm = null,
                            swingDurationMillis = 180L
                        )
                    )
                }
            }
        }
        val end = start + 60_000L
        val session = TrainingSession(
            id = "reviewed-session",
            startedAtMillis = start,
            endedAtMillis = end,
            summary = TrainingSummary(
                totalShots = shots.size,
                shotCounts = mapOf(ShotType.Unknown to shots.size),
                durationMillis = end - start,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap<HeartRateZone, Int>()
            ),
            shots = shots
        )
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = session,
            rallyProfile = RallySegmenter().segment(shots, sessionEndMillis = end),
            context = SessionContext(activityMode = ActivityMode.SinglesMatch)
        )
    }

    private object FakeRuntimeSettings : SessionRuntimeSettings {
        override suspend fun currentProfile(): PlayerProfile = PlayerProfile()
        override suspend fun stableDeviceId(): String = "device"
    }

    private object EmptySensorStream : SensorStream {
        override fun samples(): Flow<SensorSample> = emptyFlow()
    }
}

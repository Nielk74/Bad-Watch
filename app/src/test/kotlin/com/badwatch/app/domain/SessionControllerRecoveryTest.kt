package com.badwatch.app.domain

import com.badwatch.app.data.ActiveSessionJournal
import com.badwatch.app.data.ActiveSessionJournalEntry
import com.badwatch.app.data.SessionStore
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import com.badwatch.core.session.SessionRecorder
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionControllerRecoveryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun processDeathResumesStableIdentityAndMarksExportPartial() = runTest {
        val root = temporaryFolder.newFolder("process-death")
        val journalFile = File(root, "active/session.json")
        val store = SessionStore(File(root, "sessions"))
        var clock = 1_000L

        val firstStream = FakeSensorStream()
        val firstJournal = ActiveSessionJournal(journalFile)
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val firstController = controller(
            stream = firstStream,
            store = store,
            journal = firstJournal,
            scope = firstScope,
            now = { clock }
        )
        assertThat(firstController.start())
            .isEqualTo(SessionStartResult.Started(recovered = false, startedAtMillis = 1_000L))
        runCurrent()

        clock = 13_500L
        firstStream.emit(sample(clock, heartRate = 142f))
        runCurrent()
        val beforeDeath = firstJournal.load()!!
        assertThat(beforeDeath.checkpoint.samplesProcessed).isEqualTo(1L)
        val stableId = beforeDeath.checkpoint.sessionId

        // The application scope vanishes with the process. No graceful Stop or Discard runs.
        firstScope.cancel()
        runCurrent()

        clock = 25_000L
        val recoveredStream = FakeSensorStream()
        val recoveredScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val recoveredController = controller(
            stream = recoveredStream,
            store = store,
            journal = ActiveSessionJournal(journalFile),
            scope = recoveredScope,
            now = { clock }
        )

        assertThat(recoveredController.start())
            .isEqualTo(SessionStartResult.Started(recovered = true, startedAtMillis = 1_000L))
        runCurrent()
        val recording = recoveredController.state.value as SessionState.Recording
        assertThat(recording.snapshot.startedAtMillis).isEqualTo(1_000L)
        assertThat(recording.snapshot.durationMillis).isEqualTo(24_000L)
        assertThat(ActiveSessionJournal(journalFile).load()!!.checkpoint.aggregator.processAbsenceGaps)
            .containsExactly(ProcessAbsenceGap(13_500L, 25_000L))

        clock = 25_100L
        recoveredStream.emit(sample(clock, heartRate = 151f))
        runCurrent()
        clock = 30_000L
        val saved = recoveredController.stopAndSave()!!

        assertThat(saved.session.id).isEqualTo(stableId)
        assertThat(saved.session.startedAtMillis).isEqualTo(1_000L)
        assertThat(saved.session.endedAtMillis).isEqualTo(30_000L)
        assertThat(saved.session.summary.durationMillis).isEqualTo(29_000L)
        assertThat(saved.session.processAbsenceGaps)
            .containsExactly(ProcessAbsenceGap(13_500L, 25_000L))
        assertThat(saved.session.heartRateTrace).hasSize(2)
        assertThat(saved.session.summary.heartRateCoverage)
            .isWithin(0.0001f)
            .of(2f / 29f)
        assertThat(saved.context.recordingQuality).isEqualTo(RecordingQuality.Partial)
        assertThat((recoveredController.state.value as SessionState.Completed).insights).isEmpty()
        assertThat(store.refresh()).hasSize(1)
        assertThat(ActiveSessionJournal(journalFile).load()).isNull()
        recoveredScope.cancel()
    }

    @Test
    fun repeatedProcessDeathsAccumulateDistinctDurableGaps() = runTest {
        val root = temporaryFolder.newFolder("repeated-process-death")
        val journalFile = File(root, "active/session.json")
        val store = SessionStore(File(root, "sessions"))
        var clock = 1_000L

        val firstStream = FakeSensorStream()
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = controller(
            stream = firstStream,
            store = store,
            journal = ActiveSessionJournal(journalFile),
            scope = firstScope,
            now = { clock }
        )
        assertThat(first.start())
            .isEqualTo(SessionStartResult.Started(recovered = false, startedAtMillis = 1_000L))
        runCurrent()
        clock = 13_500L
        firstStream.emit(sample(clock, heartRate = 140f))
        runCurrent()
        val stableId = ActiveSessionJournal(journalFile).load()!!.checkpoint.sessionId
        firstScope.cancel()
        runCurrent()

        clock = 25_000L
        val secondStream = FakeSensorStream()
        val secondJournal = ActiveSessionJournal(journalFile)
        val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val second = controller(
            stream = secondStream,
            store = store,
            journal = secondJournal,
            scope = secondScope,
            now = { clock }
        )
        assertThat(second.start())
            .isEqualTo(SessionStartResult.Started(recovered = true, startedAtMillis = 1_000L))
        runCurrent()
        assertThat(secondJournal.load()!!.checkpoint.aggregator.processAbsenceGaps)
            .containsExactly(ProcessAbsenceGap(13_500L, 25_000L))
        clock = 40_000L
        secondStream.emit(sample(clock, heartRate = 145f))
        runCurrent()
        assertThat(secondJournal.load()!!.updatedAtMillis).isEqualTo(40_000L)
        secondScope.cancel()
        runCurrent()

        clock = 50_000L
        val thirdStream = FakeSensorStream()
        val thirdJournal = ActiveSessionJournal(journalFile)
        val thirdScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val third = controller(
            stream = thirdStream,
            store = store,
            journal = thirdJournal,
            scope = thirdScope,
            now = { clock }
        )
        assertThat(third.start())
            .isEqualTo(SessionStartResult.Started(recovered = true, startedAtMillis = 1_000L))
        runCurrent()
        val recoveredCheckpoint = thirdJournal.load()!!
        assertThat(recoveredCheckpoint.checkpoint.sessionId).isEqualTo(stableId)
        assertThat(recoveredCheckpoint.recoveryCount).isEqualTo(2)
        assertThat(recoveredCheckpoint.checkpoint.aggregator.processAbsenceGaps).containsExactly(
            ProcessAbsenceGap(13_500L, 25_000L),
            ProcessAbsenceGap(40_000L, 50_000L)
        ).inOrder()

        clock = 50_100L
        thirdStream.emit(sample(clock, heartRate = 150f))
        runCurrent()
        clock = 55_000L
        val saved = third.stopAndSave()!!

        assertThat(saved.session.id).isEqualTo(stableId)
        assertThat(saved.session.startedAtMillis).isEqualTo(1_000L)
        assertThat(saved.session.endedAtMillis).isEqualTo(55_000L)
        assertThat(saved.session.summary.durationMillis).isEqualTo(54_000L)
        assertThat(saved.session.summary.heartRateSampleCount).isEqualTo(3)
        assertThat(saved.session.summary.heartRateCoverage)
            .isWithin(0.0001f)
            .of(3f / 54f)
        assertThat(saved.session.processAbsenceGaps).containsExactly(
            ProcessAbsenceGap(13_500L, 25_000L),
            ProcessAbsenceGap(40_000L, 50_000L)
        ).inOrder()
        assertThat(saved.context.recordingQuality).isEqualTo(RecordingQuality.Partial)
        assertThat(thirdJournal.load()).isNull()
        thirdScope.cancel()
    }

    @Test
    fun concurrentDuplicateStopsReturnTheSameSingleDurableExport() = runTest {
        val root = temporaryFolder.newFolder("duplicate-stop")
        val stream = FakeSensorStream()
        val store = SessionStore(File(root, "sessions"))
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        var clock = 1_000L
        val controller = controller(
            stream = stream,
            store = store,
            journal = ActiveSessionJournal(File(root, "active/session.json")),
            scope = controllerScope,
            now = { clock }
        )
        controller.start()
        runCurrent()
        clock = 2_000L
        stream.emit(sample(clock, heartRate = 145f))
        runCurrent()

        val first = async { controller.stopAndSave() }
        val second = async { controller.stopAndSave() }
        runCurrent()

        assertThat(first.await()).isEqualTo(second.await())
        assertThat(store.refresh()).hasSize(1)
        assertThat(File(root, "sessions").listFiles { file -> file.extension == "json" })
            .asList()
            .hasSize(1)
        controllerScope.cancel()
    }

    @Test
    fun startFailsBeforeCollectingWhenInitialRecoveryCheckpointCannotBeWritten() = runTest {
        val root = temporaryFolder.newFolder("unwritable-journal")
        val invalidParent = File(root, "regular-file").also { it.writeText("not a directory") }
        val stream = FakeSensorStream()
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val controller = controller(
            stream = stream,
            store = SessionStore(File(root, "sessions")),
            journal = ActiveSessionJournal(File(invalidParent, "journal.json")),
            scope = controllerScope,
            now = { 1_000L }
        )

        val result = controller.start()
        runCurrent()

        assertThat(result).isInstanceOf(SessionStartResult.Failed::class.java)
        assertThat((result as SessionStartResult.Failed).message).contains("recovery checkpoint")
        assertThat(controller.state.value).isInstanceOf(SessionState.Failed::class.java)
        assertThat(controller.isRecording).isFalse()
        assertThat(stream.subscriptionCount).isEqualTo(0)
        controllerScope.cancel()
    }

    @Test
    fun failedSensorStreamKeepsJournalUntilDiscardThenNextStartIsFresh() = runTest {
        val root = temporaryFolder.newFolder("failed-stream-discard")
        val journalFile = File(root, "active/session.json")
        val journal = ActiveSessionJournal(journalFile)
        val store = SessionStore(File(root, "sessions"))
        val stream = ThrowOnceSensorStream(sample(2_000L, heartRate = 147f))
        val controllerJob = SupervisorJob()
        val controllerScope = CoroutineScope(
            controllerJob + StandardTestDispatcher(testScheduler)
        )
        var clock = 1_000L
        val controller = SessionController(
            sensorStream = stream,
            sessionStore = store,
            runtimeSettings = FakeRuntimeSettings,
            activeSessionJournal = journal,
            appVersion = "test-version",
            scope = controllerScope,
            now = { clock }
        )

        assertThat(controller.start())
            .isEqualTo(SessionStartResult.Started(recovered = false, startedAtMillis = 1_000L))
        clock = 2_000L
        val failedCollection = controllerJob.children.single()
        runCurrent()
        failedCollection.join()

        assertThat(controller.state.value).isInstanceOf(SessionState.Failed::class.java)
        assertThat(controller.isRecording).isFalse()
        val failedCheckpoint = journal.load()!!
        val failedSessionId = failedCheckpoint.checkpoint.sessionId
        assertThat(failedCheckpoint.checkpoint.samplesProcessed).isEqualTo(1L)
        assertThat(store.refresh()).isEmpty()

        // Dismiss is deliberately non-destructive: the player can still retry this checkpoint.
        controller.acknowledge()
        assertThat(controller.state.value).isEqualTo(SessionState.Idle)
        assertThat(journal.load()!!.checkpoint.sessionId).isEqualTo(failedSessionId)

        // The confirmed failed-screen action reuses the normal durable discard transition.
        assertThat(controller.discard()).isTrue()
        assertThat(journal.load()).isNull()
        assertThat(store.refresh()).isEmpty()

        clock = 3_000L
        assertThat(controller.start())
            .isEqualTo(SessionStartResult.Started(recovered = false, startedAtMillis = 3_000L))
        runCurrent()
        val freshCheckpoint = journal.load()!!
        assertThat(freshCheckpoint.checkpoint.sessionId).isNotEqualTo(failedSessionId)
        assertThat(controller.state.value).isInstanceOf(SessionState.Recording::class.java)

        controller.discard()
        controllerScope.cancel()
    }

    @Test
    fun crashAfterDurableSaveReconcilesWithoutOpeningOrDuplicating() = runTest {
        val root = temporaryFolder.newFolder("saved-before-clear")
        val journalFile = File(root, "active/session.json")
        val store = SessionStore(File(root, "sessions"))
        val recorder = SessionRecorder(profile = PlayerProfile(), sessionId = "stable-session")
        recorder.start(1_000L)
        recorder.onSample(sample(2_000L, heartRate = 140f))
        val checkpoint = recorder.checkpoint()!!
        val recorded = recorder.finish(3_000L)!!
        val export = SessionExport(
            deviceId = "device-test",
            appVersion = "original-version",
            profile = recorded.profile,
            session = recorded.session,
            rallyProfile = recorded.rallyProfile
        )
        store.save(export)
        ActiveSessionJournal(journalFile).save(
            ActiveSessionJournalEntry(
                checkpoint = checkpoint,
                deviceId = export.deviceId,
                appVersion = export.appVersion,
                updatedAtMillis = 2_000L
            )
        )

        val stream = FakeSensorStream()
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        val controller = controller(
            stream = stream,
            store = store,
            journal = ActiveSessionJournal(journalFile),
            scope = controllerScope,
            now = { 4_000L }
        )

        assertThat(controller.start()).isEqualTo(SessionStartResult.AlreadySaved(export))
        assertThat((controller.state.value as SessionState.Completed).export).isEqualTo(export)
        assertThat(stream.subscriptionCount).isEqualTo(0)
        assertThat(store.refresh()).hasSize(1)
        assertThat(ActiveSessionJournal(journalFile).load()).isNull()
        controllerScope.cancel()
    }

    @Test
    fun completedDiaryUpdatePreservesRawRecordingAndRefreshesControllerState() = runTest {
        val root = temporaryFolder.newFolder("diary-update")
        val stream = FakeSensorStream()
        val store = SessionStore(File(root, "sessions"))
        val controllerScope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler)
        )
        var clock = 1_000L
        val controller = controller(
            stream = stream,
            store = store,
            journal = ActiveSessionJournal(File(root, "active/session.json")),
            scope = controllerScope,
            now = { clock }
        )
        controller.start()
        runCurrent()
        clock = 2_000L
        stream.emit(sample(clock, heartRate = 144f))
        runCurrent()
        val raw = controller.stopAndSave()!!
        val reviewedContext = SessionContext(
            activityMode = ActivityMode.Drill,
            comparisonTag = "rear-court",
            diaryReviewStatus = DiaryReviewStatus.Reviewed
        )
        val reviewedReport = PostSessionReport(rpe = 7, sorenessReviewed = true)

        val revised = controller.updateCompletedSession(
            context = reviewedContext,
            report = reviewedReport
        )!!

        assertThat(revised.session).isEqualTo(raw.session)
        assertThat(revised.rallyProfile).isEqualTo(raw.rallyProfile)
        assertThat(revised.context).isEqualTo(reviewedContext)
        assertThat(revised.report).isEqualTo(reviewedReport)
        assertThat((controller.state.value as SessionState.Completed).export).isEqualTo(revised)
        assertThat(store.refresh().single().export).isEqualTo(revised)
        controllerScope.cancel()
    }

    private fun controller(
        stream: FakeSensorStream,
        store: SessionStore,
        journal: ActiveSessionJournal,
        scope: CoroutineScope,
        now: () -> Long
    ): SessionController = SessionController(
        sensorStream = stream,
        sessionStore = store,
        runtimeSettings = FakeRuntimeSettings,
        activeSessionJournal = journal,
        appVersion = "test-version",
        scope = scope,
        now = now
    )

    private fun sample(timestamp: Long, heartRate: Float): SensorSample = SensorSample(
        timestampMillis = timestamp,
        gyro = Vector3(0.1f, 0.2f, 0.3f),
        heartRateBpm = heartRate,
        heartRateSampleTimestampMillis = timestamp
    )

    private object FakeRuntimeSettings : SessionRuntimeSettings {
        override suspend fun currentProfile(): PlayerProfile = PlayerProfile()
        override suspend fun stableDeviceId(): String = "device-test"
    }

    private class FakeSensorStream : SensorStream {
        private val mutableSamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 16)
        val subscriptionCount: Int get() = mutableSamples.subscriptionCount.value

        override fun samples(): Flow<SensorSample> = mutableSamples

        suspend fun emit(sample: SensorSample) {
            mutableSamples.emit(sample)
        }
    }

    /** First subscription fails after one real sample; later subscriptions remain healthy. */
    private class ThrowOnceSensorStream(
        private val firstSample: SensorSample
    ) : SensorStream {
        private var subscriptions = 0
        private val healthySamples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 16)

        override fun samples(): Flow<SensorSample> {
            subscriptions++
            return if (subscriptions == 1) {
                flow {
                    emit(firstSample)
                    throw IllegalStateException("Sensor stream stopped unexpectedly")
                }
            } else {
                healthySamples
            }
        }
    }
}

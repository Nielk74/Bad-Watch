package com.badwatch.core.session

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.badwatch.core.sync.BadWatchJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the whole on-watch recording path on the JVM — sensor samples in, persisted
 * session out — so regressions in the pipeline do not require an emulator to catch.
 */
class SessionRecorderTest {

    @Test
    fun recordsShotsAndProducesASession() {
        val recorder = SessionRecorder(profile = PlayerProfile())
        recorder.start(START)

        var clock = START
        repeat(6) {
            clock = feedSmash(recorder, clock)
            clock += REST_BETWEEN_SHOTS
        }

        val recorded = recorder.finish(clock)

        assertThat(recorded).isNotNull()
        assertThat(recorded!!.session.shots).isNotEmpty()
        assertThat(recorded.session.summary.totalShots).isEqualTo(recorded.session.shots.size)
        assertThat(recorded.session.shots.map { it.type }).contains(ShotType.Smash)
        assertThat(recorded.session.endedAtMillis).isEqualTo(clock)
    }

    @Test
    fun groupsDetectedShotsIntoRallies() {
        val recorder = SessionRecorder(profile = PlayerProfile())
        recorder.start(START)

        var clock = START
        // Two bursts of shots separated by a 15 s rest — two rallies.
        repeat(2) {
            repeat(4) {
                clock = feedSmash(recorder, clock)
                clock += REST_BETWEEN_SHOTS
            }
            clock += 15_000L
        }

        val recorded = recorder.finish(clock)!!

        assertThat(recorded.rallyProfile.rallyCount).isEqualTo(2)
        assertThat(recorded.rallyProfile.totalRestMillis)
            .isGreaterThan(recorded.rallyProfile.totalWorkMillis)
    }

    @Test
    fun ignoresSamplesBeforeStartAndAfterFinish() {
        val recorder = SessionRecorder(profile = PlayerProfile())

        // Not started yet: samples must be dropped rather than silently starting a session.
        feedSmash(recorder, START)
        assertThat(recorder.samplesProcessed).isEqualTo(0L)
        assertThat(recorder.isRunning).isFalse()

        recorder.start(START)
        val after = feedSmash(recorder, START)
        assertThat(recorder.samplesProcessed).isGreaterThan(0L)

        recorder.finish(after)
        val processedAtFinish = recorder.samplesProcessed
        feedSmash(recorder, after + 1_000L)
        assertThat(recorder.samplesProcessed).isEqualTo(processedAtFinish)
    }

    @Test
    fun finishWithoutAnySamplesReturnsNothingToPersist() {
        val recorder = SessionRecorder(profile = PlayerProfile())
        recorder.start(START)

        assertThat(recorder.finish(START + 60_000L)).isNull()
    }

    @Test
    fun abortDiscardsEverything() {
        val recorder = SessionRecorder(profile = PlayerProfile())
        recorder.start(START)
        feedSmash(recorder, START)

        recorder.abort()

        assertThat(recorder.isRunning).isFalse()
        assertThat(recorder.shotCount).isEqualTo(0)
        assertThat(recorder.finish(START + 1_000L)).isNull()
    }

    @Test
    fun serializedCheckpointRestoresStableIdentityAndAccumulatorState() {
        val original = SessionRecorder(
            profile = PlayerProfile(),
            sessionId = "stable-session"
        )
        original.start(START)
        var clock = START
        repeat(3) {
            clock = feedSmash(original, clock) + REST_BETWEEN_SHOTS
        }
        val before = original.checkpoint()!!
        val encoded = BadWatchJson.encodeToString(
            SessionRecorderCheckpoint.serializer(),
            before
        )
        val decoded = BadWatchJson.decodeFromString(
            SessionRecorderCheckpoint.serializer(),
            encoded
        )

        val restored = SessionRecorder.restore(decoded)
        repeat(3) {
            clock = feedSmash(restored, clock) + REST_BETWEEN_SHOTS
        }
        val recorded = restored.finish(clock)!!

        assertThat(recorded.session.id).isEqualTo("stable-session")
        assertThat(recorded.session.startedAtMillis).isEqualTo(START)
        assertThat(recorded.session.shots.size).isGreaterThan(before.aggregator.shots.size)
        assertThat(restored.samplesProcessed).isGreaterThan(before.samplesProcessed)
    }

    /** Feeds one smash-shaped swing; returns the clock after the swing. */
    private fun feedSmash(recorder: SessionRecorder, startMillis: Long): Long {
        var clock = startMillis
        SMASH_PROFILE.forEach { gyro ->
            recorder.onSample(
                SensorSample(
                    timestampMillis = clock,
                    gyro = gyro,
                    heartRateBpm = 148f
                )
            )
            clock += SAMPLE_INTERVAL
        }
        return clock
    }

    private companion object {
        const val START = 1_000_000L
        const val SAMPLE_INTERVAL = 40L
        const val REST_BETWEEN_SHOTS = 900L

        /** A downward-dominant angular-velocity arc: the signature of an overhead smash. */
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

package com.badwatch.core.session

import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class TrainingSessionAggregatorTest {

    private val aggregator = TrainingSessionAggregator(
        baselineHeartRate = 60f,
        maxHeartRate = 190f
    )

    @Test
    fun buildsSummaryWithShots() {
        val start = 1_000L
        aggregator.reset(start)
        val samples = (0 until 20).map { index ->
            SensorSample(
                timestampMillis = start + index * 100L,
                gyro = Vector3(0.2f, 0.3f, 0.4f),
                heartRateBpm = 110f + index
            )
        }

        samples.forEach { aggregator.onSample(it) }

        val shot = ShotEvent(
            id = UUID.randomUUID().toString(),
            type = ShotType.Smash,
            timestampMillis = start + 2_000L,
            confidence = 0.8f,
            peakAngularVelocity = 6.5f,
            heartRateBpm = 140f,
            swingDurationMillis = 260L,
            fatigueEstimate = 0.6f
        )

        aggregator.onShot(shot)

        val snapshot = aggregator.snapshot(start + 2_500L)
        assertThat(snapshot.totalShots).isEqualTo(1)
        assertThat(snapshot.lastShot?.type).isEqualTo(ShotType.Smash)
        assertThat(snapshot.durationMillis).isEqualTo(2_500L)

        val session = aggregator.buildSession(start + 2_500L)
        assertThat(session.shots).hasSize(1)
        assertThat(session.summary.totalShots).isEqualTo(1)
        assertThat(session.summary.durationMillis).isEqualTo(2_500L)
        assertThat(session.summary.averageHeartRate).isGreaterThan(110f)
    }
}

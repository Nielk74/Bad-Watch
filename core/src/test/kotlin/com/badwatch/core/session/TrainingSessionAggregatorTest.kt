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
        maxHeartRate = 190f,
        restingHeartRateConfigured = true,
        maxHeartRateConfigured = true
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

    @Test
    fun `heart rate is counted once per optical reading carried by fused samples`() {
        val start = 10_000L
        aggregator.reset(start)

        // The 100 Hz gyro stream carries the same 1 Hz optical reading one hundred times.
        repeat(100) { index ->
            aggregator.onSample(
                SensorSample(
                    timestampMillis = start + index * 10L,
                    gyro = Vector3.ZERO,
                    heartRateBpm = 120f,
                    heartRateSampleTimestampMillis = start
                )
            )
        }
        repeat(100) { index ->
            aggregator.onSample(
                SensorSample(
                    timestampMillis = start + 1_000L + index * 10L,
                    gyro = Vector3.ZERO,
                    heartRateBpm = 160f,
                    heartRateSampleTimestampMillis = start + 1_000L
                )
            )
        }

        val session = aggregator.buildSession(start + 2_000L)

        assertThat(session.summary.heartRateSampleCount).isEqualTo(2)
        assertThat(session.summary.averageHeartRate).isEqualTo(140f)
        assertThat(session.summary.maxHeartRate).isEqualTo(160f)
        assertThat(session.heartRateTrace.map { it.beatsPerMinute })
            .containsExactly(120f, 160f)
            .inOrder()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `heart rate summary covers the whole session instead of only its tail`() {
        val start = 20_000L
        aggregator.reset(start)

        // Ten minutes, with a deliberately different first and second half. The old
        // 120-entry buffer retained only ~1.2 seconds of fused samples and returned ~180.
        repeat(600) { second ->
            val bpm = if (second < 300) 100f else 180f
            aggregator.onSample(
                SensorSample(
                    timestampMillis = start + second * 1_000L,
                    gyro = Vector3.ZERO,
                    heartRateBpm = bpm,
                    heartRateSampleTimestampMillis = start + second * 1_000L
                )
            )
        }

        val session = aggregator.buildSession(start + 600_000L)

        assertThat(session.summary.heartRateSampleCount).isEqualTo(600)
        assertThat(session.summary.averageHeartRate).isWithin(0.01f).of(140f)
        assertThat(session.summary.heartRateCoverage).isWithin(0.01f).of(1f)
        assertThat(session.summary.cardiovascularLoad).isNotNull()
        assertThat(session.summary.recoveryScore).isEqualTo(0f)
        assertThat(session.summary.fatigueScore).isEqualTo(0f)
        assertThat(session.summary.effortScore).isEqualTo(0f)
        assertThat(session.heartRateTrace).hasSize(600)
    }

    @Test
    fun `cardiovascular load is withheld when heart rate coverage is sparse`() {
        val start = 30_000L
        aggregator.reset(start)
        aggregator.onSample(
            SensorSample(
                timestampMillis = start,
                gyro = Vector3.ZERO,
                heartRateBpm = 150f,
                heartRateSampleTimestampMillis = start
            )
        )

        val session = aggregator.buildSession(start + 60_000L)

        assertThat(session.summary.heartRateCoverage).isLessThan(0.02f)
        assertThat(session.summary.cardiovascularLoad).isNull()
    }

    @Test
    fun `unconfigured profile preserves measured bpm but withholds personalized physiology`() {
        val unconfigured = TrainingSessionAggregator(
            baselineHeartRate = 60f,
            maxHeartRate = 190f
        )
        val start = 40_000L
        unconfigured.reset(start)
        listOf(120f, 160f).forEachIndexed { index, bpm ->
            unconfigured.onSample(
                SensorSample(
                    timestampMillis = start + index * 1_000L,
                    gyro = Vector3.ZERO,
                    heartRateBpm = bpm,
                    heartRateSampleTimestampMillis = start + index * 1_000L
                )
            )
        }

        val snapshot = unconfigured.snapshot(start + 2_000L)
        val session = unconfigured.buildSession(start + 2_000L)

        assertThat(snapshot.averageHeartRate).isEqualTo(140f)
        assertThat(snapshot.maxHeartRate).isEqualTo(160f)
        assertThat(snapshot.averageHeartRateReserve).isNull()
        assertThat(snapshot.dominantZone).isNull()
        assertThat(session.summary.averageHeartRate).isEqualTo(140f)
        assertThat(session.summary.maxHeartRate).isEqualTo(160f)
        assertThat(session.summary.heartRateSampleCount).isEqualTo(2)
        assertThat(session.summary.heartRateCoverage).isEqualTo(1f)
        assertThat(session.summary.heartRateZoneHistogram).isEmpty()
        assertThat(session.summary.averageHeartRateReserve).isNull()
        assertThat(session.summary.cardiovascularLoad).isNull()
    }

    @Test
    fun `configured maximum authorizes zones but resting placeholder does not authorize reserve`() {
        val maxOnly = TrainingSessionAggregator(
            baselineHeartRate = 60f,
            maxHeartRate = 190f,
            restingHeartRateConfigured = false,
            maxHeartRateConfigured = true
        )
        val start = 50_000L
        maxOnly.reset(start)
        repeat(2) { index ->
            maxOnly.onSample(
                SensorSample(
                    timestampMillis = start + index * 1_000L,
                    gyro = Vector3.ZERO,
                    heartRateBpm = 150f,
                    heartRateSampleTimestampMillis = start + index * 1_000L
                )
            )
        }

        val snapshot = maxOnly.snapshot(start + 2_000L)
        val session = maxOnly.buildSession(start + 2_000L)

        assertThat(snapshot.dominantZone).isNotNull()
        assertThat(session.summary.heartRateZoneHistogram).isNotEmpty()
        assertThat(snapshot.averageHeartRateReserve).isNull()
        assertThat(session.summary.averageHeartRateReserve).isNull()
        assertThat(session.summary.cardiovascularLoad).isNull()
    }
}

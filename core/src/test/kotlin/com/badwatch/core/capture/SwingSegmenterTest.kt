package com.badwatch.core.capture

import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.exp

class SwingSegmenterTest {

    private val segmenter = SwingSegmenter()

    @Test
    fun emitsOneWindowPerSwing() {
        val swings = feed(peakTimesMillis = listOf(1_000L, 3_000L, 5_000L), peakMagnitude = 6f)

        assertThat(swings).hasSize(3)
        assertThat(swings.map { it.label }).containsExactly(
            ShotType.Smash, ShotType.Smash, ShotType.Smash
        )
    }

    @Test
    fun centresTheWindowOnThePeak() {
        val swings = feed(peakTimesMillis = listOf(2_000L), peakMagnitude = 7f)

        val swing = swings.single()
        // The detected peak should land within one sample of where we put it.
        assertThat(swing.peakTimestampMillis).isIn(1_990L..2_010L)
        assertThat(swing.peakAngularVelocity).isWithin(0.2f).of(7f)
        // And the window should straddle it on both sides.
        assertThat(swing.samples.first().timestampMillis).isLessThan(swing.peakTimestampMillis)
        assertThat(swing.samples.last().timestampMillis).isGreaterThan(swing.peakTimestampMillis)
    }

    @Test
    fun ignoresMotionBelowTheStrokeThreshold() {
        // Idle wrist movement: well under the 2 rad/s floor.
        val swings = feed(peakTimesMillis = listOf(1_000L, 3_000L), peakMagnitude = 0.8f)

        assertThat(swings).isEmpty()
    }

    @Test
    fun doesNotSplitOneStrokeIntoTwoWindows() {
        // A single stroke has a rounded peak; nearby samples must not each become a swing.
        val swings = feed(peakTimesMillis = listOf(1_000L), peakMagnitude = 8f)

        assertThat(swings).hasSize(1)
    }

    @Test
    fun separatesTwoStrokesInQuickSuccession() {
        // 600 ms apart — fast for a drill, but two genuine strokes.
        val swings = feed(peakTimesMillis = listOf(1_000L, 1_600L), peakMagnitude = 6f)

        assertThat(swings).hasSize(2)
    }

    @Test
    fun labelsWindowsWithTheDrillStroke() {
        val swings = feed(
            peakTimesMillis = listOf(1_000L),
            peakMagnitude = 5f,
            label = ShotType.BackhandDrive
        )

        assertThat(swings.single().label).isEqualTo(ShotType.BackhandDrive)
    }

    @Test
    fun resetClearsPendingState() {
        feed(peakTimesMillis = listOf(1_000L), peakMagnitude = 6f)
        segmenter.reset()

        // After a reset the refractory period must not suppress an immediately following
        // swing at an earlier timestamp (a new capture session restarts the clock).
        val swings = feed(peakTimesMillis = listOf(500L), peakMagnitude = 6f)
        assertThat(swings).hasSize(1)
    }

    /**
     * Synthesises a 100 Hz stream containing Gaussian angular-velocity bursts at the given
     * times, which is a fair approximation of a racket swing's |ω| envelope.
     */
    private fun feed(
        peakTimesMillis: List<Long>,
        peakMagnitude: Float,
        label: ShotType = ShotType.Smash
    ): List<LabeledSwing> {
        val start = (peakTimesMillis.min() - 1_000L).coerceAtLeast(0L)
        val end = peakTimesMillis.max() + 1_000L
        val emitted = mutableListOf<LabeledSwing>()

        var t = start
        while (t <= end) {
            val magnitude = peakTimesMillis.maxOf { peak ->
                val dt = (t - peak).toDouble()
                // ~90 ms wide burst, matching a real stroke.
                peakMagnitude * exp(-(dt * dt) / (2 * 45.0 * 45.0)).toFloat()
            } + 0.05f // small resting-hand baseline
            val sample = SensorSample(
                timestampMillis = t,
                gyro = Vector3(0f, 0f, magnitude),
                heartRateBpm = 140f
            )
            segmenter.addSample(sample, label)?.let { emitted += it }
            t += 10L
        }
        return emitted
    }
}

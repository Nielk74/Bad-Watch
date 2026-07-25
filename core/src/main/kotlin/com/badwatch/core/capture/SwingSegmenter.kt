package com.badwatch.core.capture

import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import java.util.ArrayDeque
import java.util.UUID

/**
 * Cuts a continuous sensor stream into individual swing windows.
 *
 * Deliberately independent of [com.badwatch.core.classifier.ShotClassifier]: segmentation
 * must not depend on the thing we are trying to replace, or the training set inherits the
 * heuristics' blind spots. The only assumption made here is physical — a badminton stroke
 * produces a sharp, isolated peak in angular-velocity magnitude — which holds for every
 * stroke type regardless of which one it is.
 *
 * The stream is processed with a delay of [postWindowMillis]: a sample can only be
 * confirmed as a local maximum once enough of the signal after it has arrived. That latency
 * is irrelevant for data collection, which is not real-time.
 *
 * @param peakThreshold Minimum |ω| (rad/s) to consider a peak. Below this is hand movement,
 *   not a stroke. Gentle net shots sit near 2 rad/s, so the default is deliberately low.
 * @param preWindowMillis How much of the backswing to keep before the peak.
 * @param postWindowMillis How much of the follow-through to keep after the peak.
 * @param minimumGapMillis Refractory period, so one stroke cannot produce two windows.
 */
class SwingSegmenter(
    private val peakThreshold: Float = 2.0f,
    private val preWindowMillis: Long = 200L,
    private val postWindowMillis: Long = 200L,
    private val minimumGapMillis: Long = 350L
) {
    private val buffer = ArrayDeque<SensorSample>()
    // Nullable rather than a sentinel: `timestamp - Long.MIN_VALUE` overflows to a
    // negative gap, which silently suppresses every swing.
    private var lastEmittedPeakMillis: Long? = null

    fun reset() {
        buffer.clear()
        lastEmittedPeakMillis = null
    }

    /**
     * Feeds one sample.
     *
     * @return a completed swing window when one is confirmed, otherwise null.
     */
    fun addSample(sample: SensorSample, label: ShotType): LabeledSwing? {
        buffer.addLast(sample)
        trim(sample.timestampMillis)

        val latest = sample.timestampMillis
        // Only samples at least postWindowMillis old can be judged: we need the signal on
        // both sides of a candidate before calling it a local maximum.
        val candidate = buffer
            .filter { it.timestampMillis <= latest - postWindowMillis }
            .maxByOrNull { it.gyro.magnitude() }
            ?: return null

        val magnitude = candidate.gyro.magnitude()
        if (magnitude < peakThreshold) return null
        lastEmittedPeakMillis?.let { previous ->
            if (candidate.timestampMillis - previous < minimumGapMillis) return null
        }

        // Confirm it dominates its whole neighbourhood, not just the older half.
        val neighbourhood = buffer.filter {
            it.timestampMillis in
                (candidate.timestampMillis - preWindowMillis)..(candidate.timestampMillis + postWindowMillis)
        }
        if (neighbourhood.any { it.gyro.magnitude() > magnitude }) return null

        lastEmittedPeakMillis = candidate.timestampMillis
        return LabeledSwing(
            id = UUID.randomUUID().toString(),
            label = label,
            peakTimestampMillis = candidate.timestampMillis,
            peakAngularVelocity = magnitude,
            samples = neighbourhood
        )
    }

    private fun trim(latestTimestamp: Long) {
        // Keep enough history to build a full window around a candidate that is still
        // waiting for its post-peak samples.
        val horizon = latestTimestamp - (preWindowMillis + 2 * postWindowMillis)
        while (buffer.isNotEmpty() && buffer.first().timestampMillis < horizon) {
            buffer.removeFirst()
        }
    }
}

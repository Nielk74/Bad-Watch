package com.badwatch.core.pipeline

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import java.util.ArrayDeque

/**
 * Sliding-window pipeline that feeds sensor samples into the classifier.
 */
class ShotDetectionPipeline(
    private val classifier: ShotClassifier,
    private val windowDurationMillis: Long = 260,
    private val minimumGapMillis: Long = 420
) {
    private val buffer = ArrayDeque<SensorSample>()
    private var lastEmittedAt: Long = 0L

    fun reset() {
        buffer.clear()
        lastEmittedAt = 0L
    }

    fun addSample(sample: SensorSample): ShotEvent? {
        buffer.addLast(sample)
        trimOldSamples(sample.timestampMillis)
        val window = buffer.toList()
        val candidate = classifier.classify(window) ?: return null
        if (candidate.timestampMillis - lastEmittedAt < minimumGapMillis) {
            return null
        }
        lastEmittedAt = candidate.timestampMillis
        return candidate
    }

    private fun trimOldSamples(latestTimestamp: Long) {
        val windowStart = latestTimestamp - windowDurationMillis
        while (buffer.isNotEmpty() && buffer.first().timestampMillis < windowStart) {
            buffer.removeFirst()
        }
    }
}

package com.badwatch.core.pipeline

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.session.ShotDetectionPipelineCheckpoint

/**
 * Sliding-window pipeline that feeds sensor samples into the classifier.
 *
 * The window is a [SampleWindow] ring buffer: no allocation per sample. The previous
 * implementation called `ArrayDeque.toList()` on every sample, an allocation per sample
 * at 100 Hz across three sensors.
 */
class ShotDetectionPipeline(
    private val classifier: ShotClassifier,
    private val windowDurationMillis: Long = 260,
    private val minimumGapMillis: Long = 420
) {
    private val buffer = SampleWindow()
    private var lastEmittedAt: Long = 0L

    fun reset() {
        buffer.clear()
        lastEmittedAt = 0L
    }

    fun checkpoint(): ShotDetectionPipelineCheckpoint = ShotDetectionPipelineCheckpoint(
        recentSamples = buffer.asList().toList(),
        lastEmittedAtMillis = lastEmittedAt
    )

    fun restore(checkpoint: ShotDetectionPipelineCheckpoint) {
        buffer.clear()
        checkpoint.recentSamples.forEach(buffer::addLast)
        lastEmittedAt = checkpoint.lastEmittedAtMillis
    }

    fun addSample(sample: SensorSample): ShotEvent? {
        buffer.addLast(sample)
        buffer.trimBefore(sample.timestampMillis - windowDurationMillis)
        val candidate = classifier.classify(buffer.asList()) ?: return null
        if (candidate.timestampMillis - lastEmittedAt < minimumGapMillis) {
            return null
        }
        lastEmittedAt = candidate.timestampMillis
        return candidate
    }
}

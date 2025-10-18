package com.badwatch.core.pipeline

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShotDetectionPipelineTest {

    private val classifier = ShotClassifier()
    private val pipeline = ShotDetectionPipeline(classifier)

    @Test
    fun emitsSingleEventForClusteredSamples() {
        val samples = (0 until 30).map { index ->
            val vector = if (index in 8..18) {
                Vector3(0.6f, 0.8f, -6.8f)
            } else {
                Vector3(0.1f, 0.1f, -0.2f)
            }
            SensorSample(
                timestampMillis = index * 30L,
                gyro = vector,
                heartRateBpm = 118f + index * 0.1f,
                accuracy = 3
            )
        }

        var events = 0
        samples.forEach { sample ->
            pipeline.addSample(sample)?.let {
                events += 1
            }
        }

        assertThat(events).isEqualTo(1)
    }

    @Test
    fun respectsGapBetweenEvents() {
        pipeline.reset()
        val firstBurst = createBurst(start = 0L)
        val secondBurst = createBurst(start = 250L)

        val allSamples = firstBurst + secondBurst
        var events = 0
        allSamples.forEach { sample ->
            pipeline.addSample(sample)?.let {
                events += 1
            }
        }

        // Gap is shorter than minimum gap, so only one event expected.
        assertThat(events).isEqualTo(1)
    }

    private fun createBurst(start: Long): List<SensorSample> =
        (0 until 12).map { index ->
            SensorSample(
                timestampMillis = start + index * 20L,
                gyro = Vector3(0.6f, 0.8f, -6.5f),
                heartRateBpm = 120f + index * 0.5f,
                accuracy = 3
            )
        }
}

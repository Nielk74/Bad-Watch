package com.badwatch.core.classifier

import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShotClassifierTest {

    private val classifier = ShotClassifier()

    @Test
    fun classifySmash() {
        val samples = buildSamples(
            vectors = listOf(
                Vector3(0.2f, 0.4f, -1.2f),
                Vector3(0.4f, 0.6f, -2.8f),
                Vector3(0.5f, 0.7f, -4.5f),
                Vector3(0.6f, 0.9f, -5.4f),
                Vector3(0.8f, 1.1f, -6.8f),
                Vector3(0.5f, 0.6f, -4.0f),
                Vector3(0.3f, 0.5f, -1.5f)
            ),
            heartRateStart = 118f,
            heartRateEnd = 124f
        )

        val event = classifier.classify(samples)

        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo(ShotType.Smash)
        assertThat(event.confidence).isGreaterThan(0.6f)
    }

    @Test
    fun aFastClearIsNotCalledASmash() {
        // Regression: `verticalComponentRatio` is built from abs(z), so a clear fast enough
        // to clear the smash threshold matched the smash rule — which is tested first — and
        // was reported as a smash. Direction is the only thing separating the two strokes.
        val fastClear = buildSamples(
            vectors = listOf(
                Vector3(0.2f, 0.4f, 1.2f),
                Vector3(0.4f, 0.6f, 3.0f),
                Vector3(0.5f, 0.7f, 4.8f),
                Vector3(0.6f, 0.9f, 6.4f),
                Vector3(0.8f, 1.1f, 7.2f),
                Vector3(0.5f, 0.6f, 4.4f),
                Vector3(0.3f, 0.5f, 1.6f)
            ),
            heartRateStart = 120f,
            heartRateEnd = 126f
        )

        assertThat(classifier.classify(fastClear)!!.type).isEqualTo(ShotType.Clear)
    }

    @Test
    fun anAmbiguouslyVerticalSwingIsNotForcedIntoAnOverheadStroke() {
        // Equal up and down rotation: no honest direction to assign.
        val ambiguous = buildSamples(
            vectors = listOf(
                Vector3(0.4f, 0.5f, 6.4f),
                Vector3(0.4f, 0.5f, -6.4f),
                Vector3(0.4f, 0.5f, 6.4f),
                Vector3(0.4f, 0.5f, -6.4f),
                Vector3(0.4f, 0.5f, 6.4f),
                Vector3(0.4f, 0.5f, -6.4f)
            ),
            heartRateStart = 120f,
            heartRateEnd = 122f
        )

        val type = classifier.classify(ambiguous)?.type
        assertThat(type).isNotEqualTo(ShotType.Smash)
        assertThat(type).isNotEqualTo(ShotType.Clear)
    }

    @Test
    fun classifyClear() {
        val samples = buildSamples(
            vectors = listOf(
                Vector3(0.1f, 0.2f, 1.0f),
                Vector3(0.3f, 0.3f, 2.6f),
                Vector3(0.5f, 0.4f, 3.8f),
                Vector3(0.6f, 0.5f, 4.8f),
                Vector3(0.4f, 0.3f, 3.2f),
                Vector3(0.2f, 0.1f, 1.5f)
            ),
            heartRateStart = 110f,
            heartRateEnd = 113f
        )

        val event = classifier.classify(samples)

        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo(ShotType.Clear)
    }

    @Test
    fun classifyDrive() {
        val samples = buildSamples(
            vectors = listOf(
                Vector3(1.8f, 2.1f, 0.4f),
                Vector3(2.5f, 2.7f, 0.5f),
                Vector3(3.0f, 3.1f, 0.4f),
                Vector3(2.2f, 2.4f, 0.3f),
                Vector3(1.5f, 1.6f, 0.2f)
            ),
            heartRateStart = 112f,
            heartRateEnd = 114f
        )

        val event = classifier.classify(samples)

        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo(ShotType.Drive)
    }

    @Test
    fun classifyDrop() {
        val samples = buildSamples(
            vectors = listOf(
                Vector3(0.4f, 0.2f, 1.5f),
                Vector3(0.5f, 0.3f, 2.0f),
                Vector3(0.6f, 0.4f, 2.6f),
                Vector3(0.5f, 0.3f, 2.0f),
                Vector3(0.3f, 0.1f, 1.1f)
            ),
            heartRateStart = 108f,
            heartRateEnd = 108.5f
        )

        val event = classifier.classify(samples)

        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo(ShotType.Drop)
    }

    @Test
    fun classifyBackhandDrive() {
        val samples = buildSamples(
            vectors = listOf(
                Vector3(-1.2f, 0.8f, 0.4f),
                Vector3(-2.4f, 0.6f, 0.5f),
                Vector3(-3.1f, 0.5f, 0.4f),
                Vector3(-2.0f, 0.3f, 0.3f),
                Vector3(-1.1f, 0.2f, 0.2f)
            ),
            heartRateStart = 115f,
            heartRateEnd = 116f
        )

        val event = classifier.classify(samples)

        assertThat(event).isNotNull()
        assertThat(event!!.type).isEqualTo(ShotType.BackhandDrive)
    }

    private fun buildSamples(
        vectors: List<Vector3>,
        heartRateStart: Float,
        heartRateEnd: Float,
        startTimestamp: Long = 0L,
        stepMillis: Long = 40L
    ): List<SensorSample> {
        if (vectors.isEmpty()) return emptyList()
        val hrStep = if (vectors.size <= 1) 0f else (heartRateEnd - heartRateStart) / (vectors.size - 1)
        return vectors.mapIndexed { index, vector3 ->
            SensorSample(
                timestampMillis = startTimestamp + index * stepMillis,
                gyro = vector3,
                heartRateBpm = heartRateStart + hrStep * index,
                accuracy = 3
            )
        }
    }
}

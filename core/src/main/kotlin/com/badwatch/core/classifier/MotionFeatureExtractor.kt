package com.badwatch.core.classifier

import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

data class MotionFeatures(
    val peakAngularVelocity: Float,
    val averageAngularVelocity: Float,
    val verticalComponentRatio: Float,
    val horizontalComponentRatio: Float,
    val pronationScore: Float,
    val heartRateDelta: Float,
    val swingDurationMillis: Long,
    val directionalTrend: Float,
    val stabilityScore: Float
)

object MotionFeatureExtractor {
    fun extract(samples: List<SensorSample>): MotionFeatures {
        if (samples.isEmpty()) {
            return MotionFeatures(
                peakAngularVelocity = 0f,
                averageAngularVelocity = 0f,
                verticalComponentRatio = 0f,
                horizontalComponentRatio = 0f,
                pronationScore = 0f,
                heartRateDelta = 0f,
                swingDurationMillis = 0L,
                directionalTrend = 0f,
                stabilityScore = 0f
            )
        }

        var peak = 0f
        var sum = 0f
        var verticalSum = 0f
        var horizontalSum = 0f
        var pronationAccumulator = 0f
        var previous: Vector3? = null
        var stabilityAccumulator = 0f

        samples.forEach { sample ->
            val magnitude = sample.gyro.magnitude()
            peak = max(peak, magnitude)
            sum += magnitude
            verticalSum += abs(sample.gyro.z)
            horizontalSum += abs(sample.gyro.x) + abs(sample.gyro.y)
            pronationAccumulator += sample.gyro.x - sample.gyro.y

            previous?.let { prev ->
                val diff = sqrt(
                    (sample.gyro.x - prev.x) * (sample.gyro.x - prev.x) +
                        (sample.gyro.y - prev.y) * (sample.gyro.y - prev.y) +
                        (sample.gyro.z - prev.z) * (sample.gyro.z - prev.z)
                )
                stabilityAccumulator += diff
            }
            previous = sample.gyro
        }

        val duration = samples.last().timestampMillis - samples.first().timestampMillis
        val count = samples.size
        val avg = if (count == 0) 0f else sum / count
        val totalComponents = verticalSum + horizontalSum
        val verticalRatio = if (totalComponents == 0f) 0f else verticalSum / totalComponents
        val horizontalRatio = if (totalComponents == 0f) 0f else horizontalSum / totalComponents
        val pren = pronationAccumulator / max(1, count)
        val heartRateDelta = samples.last().heartRateBpm - samples.first().heartRateBpm
        val directionalTrend = computeDirectionalTrend(samples)
        val stabilityScore = if (count <= 1) 1f else 1f - (stabilityAccumulator / count).coerceIn(0f, 1f)

        return MotionFeatures(
            peakAngularVelocity = peak,
            averageAngularVelocity = avg,
            verticalComponentRatio = verticalRatio,
            horizontalComponentRatio = horizontalRatio,
            pronationScore = pren,
            heartRateDelta = heartRateDelta,
            swingDurationMillis = duration,
            directionalTrend = directionalTrend,
            stabilityScore = stabilityScore
        )
    }

    private fun computeDirectionalTrend(samples: List<SensorSample>): Float {
        val first = samples.first().gyro
        val last = samples.last().gyro
        val deltaZ = last.z - first.z
        val magnitude = sqrt(deltaZ * deltaZ + 1e-3f)
        val trend = if (magnitude == 0f) 0f else deltaZ / magnitude
        return trend * sign(samples.sumOf { it.gyro.z.toDouble() }.toFloat().coerceIn(-1f, 1f))
    }
}

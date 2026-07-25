package com.badwatch.core.classifier

import com.badwatch.core.model.Handedness
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
    /**
     * Signed vertical rotation, roughly -1..1. Negative is the downward arc of an overhead
     * smash; positive is the upward arc of a clear or lift.
     *
     * [verticalComponentRatio] is built from `abs(z)` and so cannot tell a smash from a
     * clear — they are the same magnitude in opposite directions. Every overhead rule needs
     * this, not that.
     */
    val verticalDirection: Float,
    val horizontalComponentRatio: Float,
    val pronationScore: Float,
    val heartRateDelta: Float,
    val swingDurationMillis: Long,
    val directionalTrend: Float,
    val stabilityScore: Float
)

object MotionFeatureExtractor {
    /**
     * @param handedness Racket hand. The pronation axis mirrors between hands, so the
     *   left-handed pronation score is negated to keep a single set of thresholds valid.
     */
    fun extract(
        samples: List<SensorSample>,
        handedness: Handedness = Handedness.Right
    ): MotionFeatures {
        if (samples.isEmpty()) {
            return MotionFeatures(
                peakAngularVelocity = 0f,
                averageAngularVelocity = 0f,
                verticalComponentRatio = 0f,
                verticalDirection = 0f,
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
        var signedVerticalSum = 0f
        var horizontalSum = 0f
        var pronationAccumulator = 0f
        var previous: Vector3? = null
        var stabilityAccumulator = 0f

        samples.forEach { sample ->
            val magnitude = sample.gyro.magnitude()
            peak = max(peak, magnitude)
            sum += magnitude
            verticalSum += abs(sample.gyro.z)
            signedVerticalSum += sample.gyro.z
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
        val verticalDirection =
            if (totalComponents == 0f) 0f else signedVerticalSum / totalComponents
        val horizontalRatio = if (totalComponents == 0f) 0f else horizontalSum / totalComponents
        val handSign = if (handedness == Handedness.Left) -1f else 1f
        val pren = handSign * pronationAccumulator / max(1, count)
        // Only meaningful when both ends of the window have a reading.
        val firstHeartRate = samples.first().heartRateBpm
        val lastHeartRate = samples.last().heartRateBpm
        val heartRateDelta = if (firstHeartRate != null && lastHeartRate != null) {
            lastHeartRate - firstHeartRate
        } else {
            0f
        }
        val directionalTrend = computeDirectionalTrend(samples)
        val stabilityScore = if (count <= 1) 1f else 1f - (stabilityAccumulator / count).coerceIn(0f, 1f)

        return MotionFeatures(
            peakAngularVelocity = peak,
            averageAngularVelocity = avg,
            verticalComponentRatio = verticalRatio,
            verticalDirection = verticalDirection,
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

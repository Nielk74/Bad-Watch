package com.badwatch.core.classifier

import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ShotClassifier(
    private val smashThreshold: Float = 6.0f,
    private val clearThreshold: Float = 4.5f,
    private val driveThreshold: Float = 3.0f,
    private val dropThreshold: Float = 2.0f,
    private val minConfidence: Float = 0.0f
) {

    fun classify(samples: List<SensorSample>): ShotEvent? {
        if (samples.size < MIN_WINDOW_SIZE) return null
        val features = MotionFeatureExtractor.extract(samples)
        val type = determineType(features)
        if (type == ShotType.Unknown) {
            return null
        }
        val confidence = computeConfidence(type, features)
        if (confidence < minConfidence) return null
        val last = samples.last()
        return ShotEvent(
            id = UUID.randomUUID().toString(),
            type = type,
            timestampMillis = last.timestampMillis,
            confidence = confidence,
            peakAngularVelocity = features.peakAngularVelocity,
            heartRateBpm = last.heartRateBpm,
            swingDurationMillis = features.swingDurationMillis
        )
    }

    private fun determineType(features: MotionFeatures): ShotType {
        val peak = features.peakAngularVelocity
        val vertical = features.verticalComponentRatio
        val horizontal = features.horizontalComponentRatio
        val pronation = features.pronationScore
        val trend = features.directionalTrend

        return when {
            peak >= smashThreshold && vertical >= 0.5f -> ShotType.Smash

            peak >= clearThreshold && vertical >= 0.4f && trend >= 0.05f ->
                ShotType.Clear

            peak >= driveThreshold && horizontal >= 0.6f && pronation <= -0.3f ->
                ShotType.BackhandDrive

            peak >= dropThreshold &&
                vertical in 0.3f..0.8f &&
                features.swingDurationMillis in 140L..420L ->
                ShotType.Drop

            peak >= driveThreshold && horizontal >= 0.6f ->
                ShotType.Drive

            else -> ShotType.Unknown
        }
    }

    private fun computeConfidence(type: ShotType, features: MotionFeatures): Float {
        val peak = features.peakAngularVelocity
        return when (type) {
            ShotType.Smash -> {
                val peakScore = (peak - smashThreshold) / smashThreshold
                val trendScore = abs(features.directionalTrend)
                val hrScore = (features.heartRateDelta / 5f).coerceIn(0f, 1f)
                (0.4f * peakScore + 0.3f * trendScore + 0.3f * hrScore).coerceIn(0f, 1f)
            }

            ShotType.Clear -> {
                val peakScore = (peak - clearThreshold) / clearThreshold
                val verticalScore = features.verticalComponentRatio
                val durationScore = (features.swingDurationMillis / 400f).coerceIn(0f, 1f)
                (0.4f * peakScore + 0.3f * verticalScore + 0.3f * durationScore).coerceIn(0f, 1f)
            }

            ShotType.Drive -> {
                val peakScore = (peak - driveThreshold) / driveThreshold
                val horizontalScore = features.horizontalComponentRatio
                val stability = features.stabilityScore
                (0.4f * peakScore + 0.4f * horizontalScore + 0.2f * stability).coerceIn(0f, 1f)
            }

            ShotType.Drop -> {
                val peakScore = (peak - dropThreshold) / dropThreshold
                val verticalScore = features.verticalComponentRatio
                val stability = features.stabilityScore
                (0.3f * peakScore + 0.3f * verticalScore + 0.4f * stability).coerceIn(0f, 1f)
            }

            ShotType.BackhandDrive -> {
                val peakScore = (peak - dropThreshold) / dropThreshold
                val pronationScore = abs(features.pronationScore)
                val horizontalScore = features.horizontalComponentRatio
                (0.3f * peakScore + 0.5f * pronationScore + 0.2f * horizontalScore).coerceIn(0f, 1f)
            }

            ShotType.Unknown -> 0f
        }.let { max(min(it, 1f), 0f) }
    }

    companion object {
        private const val MIN_WINDOW_SIZE = 5
    }
}

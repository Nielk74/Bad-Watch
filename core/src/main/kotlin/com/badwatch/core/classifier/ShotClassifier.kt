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
    private val driveThreshold: Float = 3.2f,
    private val dropThreshold: Float = 2.0f,
    private val minConfidence: Float = 0.55f
) {

    fun classify(samples: List<SensorSample>): ShotEvent? {
        if (samples.size < MIN_WINDOW_SIZE) return null
        val features = MotionFeatureExtractor.extract(samples)
        val type = determineType(features)
        if (type == ShotType.Unknown) return null
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
            peak >= smashThreshold &&
                vertical > 0.55f &&
                trend < -0.15f &&
                features.heartRateDelta >= 2f ->
                ShotType.Smash

            peak in clearThreshold..(smashThreshold + 1f) &&
                vertical > 0.5f &&
                trend > 0.1f ->
                ShotType.Clear

            peak >= driveThreshold &&
                horizontal > 0.6f &&
                abs(pronation) < 0.35f ->
                ShotType.Drive

            peak >= dropThreshold &&
                peak < clearThreshold &&
                features.swingDurationMillis in 160L..360L &&
                vertical in 0.35f..0.6f &&
                abs(pronation) < 0.6f ->
                ShotType.Drop

            peak >= dropThreshold &&
                horizontal > 0.6f &&
                pronation <= -0.4f ->
                ShotType.BackhandDrive

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
                (0.5f * peakScore + 0.3f * trendScore + 0.2f * hrScore).coerceIn(0f, 1f)
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
        private const val MIN_WINDOW_SIZE = 8
    }
}

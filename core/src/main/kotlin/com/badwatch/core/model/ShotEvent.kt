package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Shot classification result emitted by the detection pipeline.
 */
@Serializable
data class ShotEvent(
    val id: String,
    val type: ShotType,
    val timestampMillis: Long,
    val confidence: Float,
    val peakAngularVelocity: Float,
    val heartRateBpm: Float,
    val swingDurationMillis: Long,
    val fatigueEstimate: Float = 0f
)

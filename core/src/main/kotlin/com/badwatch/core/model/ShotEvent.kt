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
    /** Heart rate at contact, or null when the sensor had no lock. */
    val heartRateBpm: Float?,
    val swingDurationMillis: Long,
    val fatigueEstimate: Float = 0f
)

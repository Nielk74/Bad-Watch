package com.badwatch.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TrainingSummary(
    val totalShots: Int,
    val shotCounts: Map<ShotType, Int>,
    val durationMillis: Long,
    /** Null when the session recorded no heart-rate readings at all. */
    val averageHeartRate: Float?,
    val maxHeartRate: Float?,
    val recoveryScore: Float,
    val fatigueScore: Float,
    val effortScore: Float,
    val heartRateZoneHistogram: Map<HeartRateZone, Int>
)

@Serializable
data class TrainingSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val summary: TrainingSummary,
    val shots: List<ShotEvent>
)

data class TrainingSessionSnapshot(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val currentHeartRate: Float?,
    val averageHeartRate: Float?,
    val maxHeartRate: Float?,
    val totalShots: Int,
    val lastShot: ShotEvent?,
    val shotCounts: Map<ShotType, Int>,
    val fatigueScore: Float,
    val effortScore: Float,
    val recoveryScore: Float,
    val dominantZone: HeartRateZone,
    // Most recent gyroscope sample (rad/s) along device axes
    val lastGyro: Vector3
)

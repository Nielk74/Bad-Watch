package com.badwatch.core.model

import kotlinx.serialization.Serializable

/** Minimum optical-signal coverage required to extrapolate HR reserve across a session. */
const val MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE: Float = 0.6f

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
    val heartRateZoneHistogram: Map<HeartRateZone, Int>,
    /** Number of distinct optical-sensor readings, not fused 100 Hz motion samples. */
    val heartRateSampleCount: Int = 0,
    /** Approximate share of elapsed seconds for which a heart-rate reading was available. */
    val heartRateCoverage: Float = 0f,
    /** Mean heart-rate reserve, 0..1, when heart-rate data exists. */
    val averageHeartRateReserve: Float? = null,
    /** Duration in minutes multiplied by mean heart-rate reserve. Transparent internal load. */
    val cardiovascularLoad: Float? = null
)

/** A distinct optical heart-rate observation retained for the post-session profile. */
@Serializable
data class HeartRatePoint(
    val timestampMillis: Long,
    val beatsPerMinute: Float
)

@Serializable
data class TrainingSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val summary: TrainingSummary,
    val shots: List<ShotEvent>,
    /** Low-rate trace (normally ~1 Hz), deduplicated from the fused motion stream. */
    val heartRateTrace: List<HeartRatePoint> = emptyList()
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
    val lastGyro: Vector3,
    val heartRateSampleCount: Int = 0,
    val heartRateCoverage: Float = 0f,
    val averageHeartRateReserve: Float? = null
)

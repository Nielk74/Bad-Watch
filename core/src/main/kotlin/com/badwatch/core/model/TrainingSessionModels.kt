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
    /** Legacy schema-1 field. No validated recovery model exists; new producers write zero. */
    @Deprecated("No validated recovery model; retained only for schema-1 decoding")
    val recoveryScore: Float,
    /** Legacy schema-1 field. Wrist data cannot diagnose fatigue; new producers write zero. */
    @Deprecated("No validated fatigue model; retained only for schema-1 decoding")
    val fatigueScore: Float,
    /** Legacy schema-1 field. Use reported RPE or an explicitly named HR metric instead. */
    @Deprecated("Ambiguous legacy effort score; retained only for schema-1 decoding")
    val effortScore: Float,
    /** Populated only when the player's maximum heart rate has an explicit source. */
    val heartRateZoneHistogram: Map<HeartRateZone, Int>,
    /** Number of distinct optical-sensor readings, not fused 100 Hz motion samples. */
    val heartRateSampleCount: Int = 0,
    /** Approximate share of elapsed seconds for which a heart-rate reading was available. */
    val heartRateCoverage: Float = 0f,
    /** Mean heart-rate reserve, 0..1, only when both profile endpoints are configured. */
    val averageHeartRateReserve: Float? = null,
    /**
     * Duration in minutes multiplied by mean heart-rate reserve. Requires configured resting
     * and maximum heart rate as well as sufficient optical-signal coverage.
     */
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
    val heartRateTrace: List<HeartRatePoint> = emptyList(),
    /** Process-absence provenance; never subtracted from whole-session elapsed duration. */
    val processAbsenceGaps: List<ProcessAbsenceGap> = emptyList()
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
    /** Deprecated compatibility field; live aggregation always emits zero. */
    @Deprecated("No validated fatigue model; retained for source compatibility")
    val fatigueScore: Float,
    /** Deprecated compatibility field; use averageHeartRateReserve or reported RPE. */
    @Deprecated("Ambiguous legacy effort score; retained for source compatibility")
    val effortScore: Float,
    /** Deprecated compatibility field; live aggregation always emits zero. */
    @Deprecated("No validated recovery model; retained for source compatibility")
    val recoveryScore: Float,
    /** Null without measured heart rate or an explicitly configured maximum heart rate. */
    val dominantZone: HeartRateZone?,
    // Most recent gyroscope sample (rad/s) along device axes
    val lastGyro: Vector3,
    val heartRateSampleCount: Int = 0,
    val heartRateCoverage: Float = 0f,
    /** Null until both resting and maximum heart rate have explicit provenance. */
    val averageHeartRateReserve: Float? = null
)

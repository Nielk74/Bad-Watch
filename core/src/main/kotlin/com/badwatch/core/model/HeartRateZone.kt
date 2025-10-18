package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Simple heart-rate zones derived from the percentage of an estimated max HR.
 */
@Serializable
enum class HeartRateZone {
    WarmUp,
    Endurance,
    Tempo,
    Threshold,
    VO2Max
}

/**
 * Maps a heart-rate reading onto a training zone.
 */
fun heartRateZoneFor(valueBpm: Float, maxBpm: Float): HeartRateZone {
    if (valueBpm.isNaN() || maxBpm <= 0f) return HeartRateZone.WarmUp
    val ratio = valueBpm / maxBpm
    return when {
        ratio < 0.6f -> HeartRateZone.WarmUp
        ratio < 0.7f -> HeartRateZone.Endurance
        ratio < 0.8f -> HeartRateZone.Tempo
        ratio < 0.9f -> HeartRateZone.Threshold
        else -> HeartRateZone.VO2Max
    }
}

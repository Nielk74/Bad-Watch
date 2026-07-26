package com.badwatch.app.ui

/** Values intentionally allowed to change on the system's minute-scale ambient tick. */
internal data class AmbientHudModel(
    val clockText: String,
    val detectedHitCount: Int
)

internal fun ambientHudModel(
    ambientTimeMillis: Long,
    detectedHitCount: Int,
    formatLocalTime: (Long) -> String
): AmbientHudModel = AmbientHudModel(
    clockText = formatLocalTime(ambientTimeMillis),
    detectedHitCount = detectedHitCount.coerceAtLeast(0)
)

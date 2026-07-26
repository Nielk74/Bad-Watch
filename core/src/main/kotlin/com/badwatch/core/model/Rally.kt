package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * A single rally: a contiguous burst of shots bounded by rest.
 *
 * Badminton is an interval sport. Total session duration says almost nothing, because a
 * 60 minute session contains only ~20 minutes of actual play. Rally structure — how long
 * points last and how much rest sits between them — is what actually characterises a
 * session, so it is a first-class model rather than a derived statistic.
 */
@Serializable
data class Rally(
    val index: Int,
    val startMillis: Long,
    val endMillis: Long,
    val shotCount: Int,
    val shotCounts: Map<ShotType, Int>,
    val peakAngularVelocity: Float,
    /** Null when no shot in the rally carried a heart-rate reading. */
    val averageHeartRate: Float?,
    /** Gap between the end of the previous rally and the start of this one. */
    val restBeforeMillis: Long
) {
    val durationMillis: Long get() = endMillis - startMillis

    /** Shots per second within the rally — a proxy for how frantic the exchange was. */
    val shotRate: Float
        get() = if (durationMillis <= 0L) 0f else shotCount * 1000f / durationMillis
}

/**
 * Session-level rally structure. This is the headline analysis of a badminton session.
 */
@Serializable
data class RallyProfile(
    val rallies: List<Rally>,
    val totalWorkMillis: Long,
    val totalRestMillis: Long
) {
    val rallyCount: Int get() = rallies.size

    val averageRallyDurationMillis: Long
        get() = if (rallies.isEmpty()) 0L else rallies.sumOf { it.durationMillis } / rallies.size

    val averageShotsPerRally: Float
        get() = if (rallies.isEmpty()) 0f else rallies.sumOf { it.shotCount }.toFloat() / rallies.size

    val longestRally: Rally? get() = rallies.maxByOrNull { it.shotCount }

    /**
     * Work-to-rest ratio, expressed as the rest multiple (1:N).
     *
     * Singles sits near 1:2, doubles nearer 1:1.5. A club player drifting toward 1:4 is
     * resting far more than they think — usually the most surprising number in a recap.
     */
    val restRatio: Float
        get() = if (totalWorkMillis <= 0L) 0f else totalRestMillis.toFloat() / totalWorkMillis

    /**
     * Fraction of the analyzed detected-play span spent inside exchange windows, 0..1.
     *
     * This intentionally describes only [totalWorkMillis] + [totalRestMillis]. Callers that
     * present a whole-session share must use [workDensityOver] with the reviewed wall duration,
     * because leading time and known process absence are not part of detected rest.
     */
    val workDensity: Float
        get() {
            val total = totalWorkMillis + totalRestMillis
            return if (total <= 0L) 0f else totalWorkMillis.toFloat() / total
        }

    /** Fraction of a caller-supplied wall window spent inside detected exchange windows. */
    fun workDensityOver(durationMillis: Long): Float =
        if (durationMillis <= 0L) {
            0f
        } else {
            (totalWorkMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
        }

    companion object {
        val EMPTY = RallyProfile(emptyList(), 0L, 0L)
    }
}

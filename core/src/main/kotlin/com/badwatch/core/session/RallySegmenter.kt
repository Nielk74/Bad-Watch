package com.badwatch.core.session

import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.Rally
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.overlapDurationMillis
import com.badwatch.core.model.overlapsInterval

/**
 * Groups a session's shots into rallies.
 *
 * The rule is deliberately simple and explainable: consecutive shots separated by less
 * than [restThresholdMillis] belong to the same rally. Badminton rallies have a shot every
 * ~0.7-1.5 s, while the gap between points (pick up shuttle, walk back, serve) is rarely
 * under 5 s, so the two distributions separate cleanly without needing a model.
 *
 * @param restThresholdMillis Gap above which a new rally starts.
 * @param minimumShots Rallies with fewer shots are discarded as detector noise. A genuine
 *   one-shot rally (service error) exists, but with a heuristic classifier a lone shot is
 *   far more likely to be a false positive, so the default requires two.
 */
class RallySegmenter(
    private val restThresholdMillis: Long = 4_000L,
    private val minimumShots: Int = 2
) {

    fun segment(
        shots: List<ShotEvent>,
        sessionEndMillis: Long? = null,
        processAbsenceGaps: List<ProcessAbsenceGap> = emptyList()
    ): RallyProfile {
        if (shots.isEmpty()) return RallyProfile.EMPTY

        val ordered = shots.sortedBy { it.timestampMillis }
        val groups = mutableListOf<MutableList<ShotEvent>>()
        var current = mutableListOf(ordered.first())

        for (shot in ordered.drop(1)) {
            val gap = shot.timestampMillis - current.last().timestampMillis
            val crossesProcessAbsence = processAbsenceGaps.overlapsInterval(
                startMillis = current.last().timestampMillis,
                endMillis = shot.timestampMillis
            )
            if (gap > restThresholdMillis || crossesProcessAbsence) {
                groups += current
                current = mutableListOf(shot)
            } else {
                current += shot
            }
        }
        groups += current

        val kept = groups.filter { it.size >= minimumShots }
        if (kept.isEmpty()) return RallyProfile.EMPTY

        var previousEnd: Long? = null
        val rallies = kept.mapIndexed { index, group ->
            // A rally starts fractionally before its first detected shot (the serve motion
            // itself) but we have no better anchor, so the first shot is the boundary.
            val start = group.first().timestampMillis
            val end = group.last().timestampMillis
            val rally = Rally(
                index = index,
                startMillis = start,
                endMillis = end,
                shotCount = group.size,
                shotCounts = group.groupingBy { it.type }.eachCount(),
                peakAngularVelocity = group.maxOf { it.peakAngularVelocity },
                averageHeartRate = group.mapNotNull { it.heartRateBpm }
                    .filter { it > 0f }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat(),
                restBeforeMillis = previousEnd?.let { previous ->
                    val wallGap = (start - previous).coerceAtLeast(0L)
                    wallGap - processAbsenceGaps.overlapDurationMillis(previous, start)
                } ?: 0L
            )
            previousEnd = end
            rally
        }

        val totalWork = rallies.sumOf { it.durationMillis }
        // Rest is measured between rallies, plus any trailing time to the session end.
        val betweenRest = rallies.drop(1).sumOf { it.restBeforeMillis }
        val trailingRest = sessionEndMillis
            ?.let { sessionEnd ->
                val lastRallyEnd = rallies.last().endMillis
                val wallGap = (sessionEnd - lastRallyEnd).coerceAtLeast(0L)
                wallGap - processAbsenceGaps.overlapDurationMillis(lastRallyEnd, sessionEnd)
            }
            ?: 0L

        return RallyProfile(
            rallies = rallies,
            totalWorkMillis = totalWork,
            totalRestMillis = betweenRest + trailingRest
        )
    }
}

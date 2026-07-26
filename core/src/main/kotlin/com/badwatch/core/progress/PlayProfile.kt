package com.badwatch.core.progress

import com.badwatch.core.sync.SessionComparisonKey
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.comparisonKey
import com.badwatch.core.sync.isPlayerInferenceEligible
import com.badwatch.core.sync.reviewedAnalysis
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * A descriptive, context-specific history profile—not a badminton skill level.
 *
 * One racket-wrist watch cannot observe tactics, movement quality, opponent strength or
 * point outcome. The useful product is therefore a stable set of measured/inferred session
 * dimensions for one comparable activity, only after enough repeated evidence exists.
 */
@Serializable
sealed interface PlayProfile {
    val requiredSessionCount: Int get() = PlayProfileBuilder.MINIMUM_SESSIONS
    val requiredDistinctDays: Int get() = PlayProfileBuilder.MINIMUM_DAYS

    @Serializable
    data class Building(
        val bestAvailableKey: SessionComparisonKey? = null,
        val comparableSessionCount: Int = 0,
        val distinctDayCount: Int = 0
    ) : PlayProfile {
        val sessionsRemaining: Int
            get() = (requiredSessionCount - comparableSessionCount).coerceAtLeast(0)
        val daysRemaining: Int get() = (requiredDistinctDays - distinctDayCount).coerceAtLeast(0)
    }

    @Serializable
    data class Ready(
        val comparisonKey: SessionComparisonKey,
        val sessionCount: Int,
        val distinctDayCount: Int,
        /** Reviewed timestamped racket-wrist contacts divided by the effective minutes. */
        val medianDetectedHitsPerMinute: Float,
        /** Reviewed inferred active intervals divided by the effective session time. */
        val medianEstimatedActiveShare: Float,
        /** Median session-level mean size of reviewed inferred hit bursts. */
        val medianHitsPerBurst: Float,
        /**
         * Mean HR reserve as a percentage; null unless optical coverage is sufficient and
         * both physiological profile inputs have explicit provenance.
         */
        val medianHeartRateReservePercent: Int?,
        /** Recent vs prior detected-hit rate; descriptive and not necessarily improvement. */
        val recentDetectedRateChangePercent: Int?
    ) : PlayProfile
}

object PlayProfileBuilder {
    const val MINIMUM_SESSIONS = 5
    const val MINIMUM_DAYS = 3
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val MINIMUM_HR_COVERAGE = 0.6f

    fun build(exports: List<SessionExport>): PlayProfile {
        val eligible = exports.filter { it.isPlayerInferenceEligible }
        val groups = eligible
            .groupBy { it.context.comparisonKey() }
            .filterKeys { it.baselineEligible }

        val best = groups.entries.maxWithOrNull(
            compareBy<Map.Entry<SessionComparisonKey, List<SessionExport>>> { it.value.size }
                .thenBy { it.value.maxOfOrNull { export -> export.session.startedAtMillis } ?: 0L }
        ) ?: return PlayProfile.Building()

        val ordered = best.value.sortedBy { it.session.startedAtMillis }.takeLast(10)
        val distinctDays = ordered.map { it.session.startedAtMillis / DAY_MILLIS }.distinct().size
        if (ordered.size < MINIMUM_SESSIONS || distinctDays < MINIMUM_DAYS) {
            return PlayProfile.Building(
                bestAvailableKey = best.key,
                comparableSessionCount = ordered.size,
                distinctDayCount = distinctDays
            )
        }

        val reviewed = ordered.map { export -> export to export.reviewedAnalysis() }
        val detectedRates = reviewed.mapNotNull { (_, analysis) ->
            analysis.session.summary.durationMillis.takeIf { it > 0L }?.let { duration ->
                analysis.session.summary.totalShots * 60_000f / duration
            }
        }
        val activeShares = reviewed.mapNotNull { (_, analysis) ->
            analysis.session.summary.durationMillis.takeIf { it > 0L }?.let { duration ->
                (analysis.rallyProfile.totalWorkMillis.toFloat() / duration).coerceIn(0f, 1f)
            }
        }
        val burstSizes = reviewed
            .map { (_, analysis) -> analysis.rallyProfile }
            .filter { it.rallyCount > 0 }
            .map { it.averageShotsPerRally }
        val heartRateReserve = reviewed.mapNotNull { (export, analysis) ->
            val summary = analysis.session.summary
            val average = summary.averageHeartRate
            val reserve = export.profile.maxHeartRate - export.profile.restingHeartRate
            if (!export.profile.hasConfiguredHeartRateReserve || average == null ||
                reserve <= 0f || summary.heartRateSampleCount <= 0 ||
                summary.heartRateCoverage < MINIMUM_HR_COVERAGE
            ) {
                null
            } else {
                ((average - export.profile.restingHeartRate) / reserve * 100f)
                    .coerceIn(0f, 100f)
            }
        }

        return PlayProfile.Ready(
            comparisonKey = best.key,
            sessionCount = ordered.size,
            distinctDayCount = distinctDays,
            medianDetectedHitsPerMinute = detectedRates.medianOrZero(),
            medianEstimatedActiveShare = activeShares.medianOrZero(),
            medianHitsPerBurst = burstSizes.medianOrZero(),
            medianHeartRateReservePercent = heartRateReserve
                .takeIf { it.size >= 3 }
                ?.medianOrZero()
                ?.roundToInt(),
            recentDetectedRateChangePercent = detectedRates.recentChangePercent()
        )
    }

    private fun List<Float>.medianOrZero(): Float {
        if (isEmpty()) return 0f
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private fun List<Float>.recentChangePercent(): Int? {
        if (size < 6) return null
        val prior = takeLast(6).take(3).medianOrZero()
        val recent = takeLast(3).medianOrZero()
        if (prior <= 0f) return null
        return ((recent - prior) / prior * 100f).roundToInt()
    }
}

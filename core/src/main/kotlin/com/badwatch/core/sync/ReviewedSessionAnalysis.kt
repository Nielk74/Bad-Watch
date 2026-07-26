package com.badwatch.core.sync

import com.badwatch.core.insight.InsightBaseline
import com.badwatch.core.insight.InsightBaselineBuilder
import com.badwatch.core.model.HeartRatePoint
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.model.heartRateZoneFor
import com.badwatch.core.session.RallySegmenter

/**
 * Deterministic analysis derived from the latest player review.
 *
 * The persisted [SessionExport.session] and [SessionExport.rallyProfile] remain immutable raw
 * evidence. This view first applies the bounded edge trim, then removes only false-hit IDs that
 * resolve to events inside that window, and finally rebuilds detected exchanges from the
 * remaining timestamped events. A reported missed-hit count has no timestamp, so it is exposed
 * only by [metrics] and never inserted into [detectedHits], [session], or [rallyProfile].
 */
data class ReviewedSessionAnalysis(
    val metrics: EffectiveSessionMetrics,
    val detectedHits: List<ShotEvent>,
    /** A derived session boundary/summary suitable for the insight engine, never persistence. */
    val session: TrainingSession,
    val rallyProfile: RallyProfile
) {
    val window: EffectiveSessionWindow get() = metrics.window
}

/** Builds the current reviewed view without modifying or replacing the stored raw export. */
fun SessionExport.reviewedAnalysis(): ReviewedSessionAnalysis {
    val metrics = effectiveMetrics()
    val hits = effectiveDetectedHits().sortedWith(
        compareBy<ShotEvent> { it.timestampMillis }.thenBy { it.id }
    )
    val window = metrics.window
    val trace = session.heartRateTrace.filter { point ->
        window.durationMillis > 0L &&
            point.timestampMillis >= window.startedAtMillis &&
            point.timestampMillis <= window.endedAtMillis
    }
    val reviewedSession = session.copy(
        startedAtMillis = window.startedAtMillis,
        endedAtMillis = window.endedAtMillis,
        summary = reviewedSummary(metrics, hits, trace),
        shots = hits,
        heartRateTrace = trace
    )

    return ReviewedSessionAnalysis(
        metrics = metrics,
        detectedHits = hits,
        session = reviewedSession,
        rallyProfile = RallySegmenter().segment(
            shots = hits,
            sessionEndMillis = window.endedAtMillis,
            processAbsenceGaps = session.processAbsenceGaps
        )
    )
}

/**
 * Builds a personal insight baseline from earlier, usable, like-for-like reviewed sessions.
 *
 * Centralising this filter keeps watch and server interpretations identical. Equal/future start
 * times are excluded, recordings with Partial/Unusable quality or process gaps overlapping their
 * reviewed window can never teach the baseline, and comparison keys must be explicitly eligible
 * before any personal-history language is allowed.
 */
fun SessionExport.reviewedInsightBaseline(
    history: Iterable<SessionExport>
): InsightBaseline {
    if (!isPlayerInferenceEligible) return InsightBaseline.NONE
    return InsightBaselineBuilder.build(
        history
            .asSequence()
            .filter { candidate -> candidate.session.startedAtMillis < session.startedAtMillis }
            .filter { candidate -> candidate.isPlayerInferenceEligible }
            .filter { candidate -> isComparableWith(candidate) }
            .map { candidate -> candidate.reviewedAnalysis().rallyProfile }
            .toList()
    )
}

private fun SessionExport.reviewedSummary(
    metrics: EffectiveSessionMetrics,
    hits: List<ShotEvent>,
    trace: List<HeartRatePoint>
): TrainingSummary {
    val window = metrics.window
    val rawWindowUnchanged = window.startedAtMillis == session.startedAtMillis &&
        window.endedAtMillis == session.endedAtMillis
    val heartRate = if (rawWindowUnchanged) {
        ReviewedHeartRate.fromRaw(session.summary, profile)
    } else {
        ReviewedHeartRate.fromTrace(trace, window.durationMillis, profile)
    }

    return session.summary.copy(
        // This is deliberately the corrected *detected* count. Reported misses remain only in
        // metrics.effectiveHitCount because no timestamp or provisional type exists for them.
        totalShots = hits.size,
        shotCounts = hits.groupingBy { it.type }.eachCount(),
        durationMillis = window.durationMillis,
        averageHeartRate = heartRate.average,
        maxHeartRate = heartRate.maximum,
        recoveryScore = 0f,
        fatigueScore = 0f,
        effortScore = 0f,
        heartRateZoneHistogram = heartRate.zoneHistogram,
        heartRateSampleCount = heartRate.sampleCount,
        heartRateCoverage = heartRate.coverage,
        averageHeartRateReserve = heartRate.averageReserve,
        cardiovascularLoad = heartRate.cardiovascularLoad
    )
}

private data class ReviewedHeartRate(
    val average: Float?,
    val maximum: Float?,
    val zoneHistogram: Map<HeartRateZone, Int>,
    val sampleCount: Int,
    val coverage: Float,
    val averageReserve: Float?,
    val cardiovascularLoad: Float?
) {
    companion object {
        fun fromRaw(summary: TrainingSummary, profile: PlayerProfile) = ReviewedHeartRate(
            average = summary.averageHeartRate,
            maximum = summary.maxHeartRate,
            zoneHistogram = summary.heartRateZoneHistogram.takeIf {
                profile.hasConfiguredMaxHeartRate
            }.orEmpty(),
            sampleCount = summary.heartRateSampleCount,
            coverage = summary.heartRateCoverage.coerceIn(0f, 1f),
            averageReserve = summary.averageHeartRateReserve.takeIf {
                profile.hasConfiguredHeartRateReserve
            },
            cardiovascularLoad = summary.cardiovascularLoad.takeIf {
                profile.hasConfiguredHeartRateReserve &&
                    summary.heartRateSampleCount > 0 &&
                    summary.heartRateCoverage >= MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
            }
        )

        fun fromTrace(
            trace: List<HeartRatePoint>,
            durationMillis: Long,
            profile: PlayerProfile
        ): ReviewedHeartRate {
            if (trace.isEmpty() || durationMillis <= 0L) {
                return ReviewedHeartRate(
                    average = null,
                    maximum = null,
                    zoneHistogram = emptyMap(),
                    sampleCount = 0,
                    coverage = 0f,
                    averageReserve = null,
                    cardiovascularLoad = null
                )
            }

            val average = trace.map { it.beatsPerMinute }.average().toFloat()
            val coverage = (trace.size / maxOf(1f, durationMillis / 1_000f)).coerceIn(0f, 1f)
            val reserve = average
                .takeIf { profile.hasConfiguredHeartRateReserve }
                ?.let { value ->
                    ((value - profile.restingHeartRate) /
                        (profile.maxHeartRate - profile.restingHeartRate)).coerceIn(0f, 1f)
                }
            val zones = if (profile.hasConfiguredMaxHeartRate) {
                trace.groupingBy { point ->
                    heartRateZoneFor(point.beatsPerMinute, profile.maxHeartRate)
                }.eachCount()
            } else {
                emptyMap()
            }

            return ReviewedHeartRate(
                average = average,
                maximum = trace.maxOf { it.beatsPerMinute },
                zoneHistogram = zones,
                sampleCount = trace.size,
                coverage = coverage,
                averageReserve = reserve,
                cardiovascularLoad = reserve
                    ?.takeIf { coverage >= MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE }
                    ?.times(durationMillis / 60_000f)
            )
        }
    }
}

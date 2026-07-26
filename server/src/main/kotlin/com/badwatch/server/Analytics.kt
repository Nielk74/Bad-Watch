package com.badwatch.server

import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.InsightBaseline
import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.EffectiveSessionMetrics
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.ReviewedSessionAnalysis
import com.badwatch.core.sync.SessionComparisonKey
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.comparisonKey
import com.badwatch.core.sync.isPlayerInferenceEligible
import com.badwatch.core.sync.reviewedAnalysis
import com.badwatch.core.sync.reviewedInsightBaseline
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Everything the dashboard renders, computed server-side.
 *
 * Aggregation lives here rather than in the browser so the page stays a thin renderer and
 * the same numbers are available to any other client (a CLI, a coach's export) from one
 * endpoint.
 */
@Serializable
data class DashboardData(
    val sessionCount: Int,
    val totalShots: Int,
    val totalPlayingMillis: Long,
    val totalElapsedMillis: Long,
    val averageRestRatio: Float,
    val averageRallyShots: Float,
    val longestRallyShots: Int,
    val shotDistribution: List<ShotSlice>,
    val rallyHistogram: List<HistogramBucket>,
    val sessions: List<SessionCard>,
    val volumeTrend: List<VolumePoint>,
    /** The selection used to produce every aggregate above. */
    val appliedFilter: SessionAnalyticsFilter = SessionAnalyticsFilter(),
    /** Available like-for-like groups across the baseline-safe, otherwise-unfiltered corpus. */
    val comparisonGroups: List<SessionComparisonGroup> = emptyList()
)

/**
 * Optional server-side diary selection. Empty sets mean "all" except recording quality: the
 * default aggregate omits explicitly unusable recordings, while a non-empty
 * [recordingQualities] set remains an exact audit selection. [comparisonTag] is matched after
 * trimming and case-folding with the same rules used by [SessionComparisonKey].
 */
@Serializable
data class SessionAnalyticsFilter(
    val activityModes: Set<ActivityMode> = emptySet(),
    val comparisonTag: String? = null,
    val completions: Set<SessionCompletion> = emptySet(),
    val recordingQualities: Set<RecordingQuality> = emptySet()
)

/** Count of sessions that can be considered together without crossing activity contexts. */
@Serializable
data class SessionComparisonGroup(
    val key: SessionComparisonKey,
    val sessionCount: Int,
    val baselineEligible: Boolean
)

@Serializable
data class ShotSlice(val type: String, val count: Int)

@Serializable
data class HistogramBucket(val label: String, val count: Int)

@Serializable
data class SessionCard(
    val id: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val totalShots: Int,
    val rallyCount: Int,
    val averageRallyShots: Float,
    val restRatio: Float,
    val workDensity: Float,
    /** Null when the session recorded no heart rate; the dashboard renders an em dash. */
    val averageHeartRate: Float?,
    val maxHeartRate: Float?,
    /** Time between the first and last detected hit in each inferred exchange. */
    val estimatedActiveMillis: Long,
    /** Share of elapsed seconds represented by a distinct optical heart-rate reading. */
    val heartRateCoverage: Float,
    /**
     * Elapsed minutes x mean heart-rate reserve; null unless optical HR was measured and
     * both physiological profile inputs have explicit provenance.
     */
    val cardiovascularLoad: Float?,
    val shotDistribution: List<ShotSlice>,
    /**
     * Derived by the same `:core` engine the watch uses, so a session never carries two
     * different readings depending on where you look at it.
     */
    val insights: List<Insight> = emptyList(),
    /** Reported context and subjective feedback are never inferred by server analytics. */
    val context: SessionContext = SessionContext(),
    val report: PostSessionReport = PostSessionReport(),
    /** Transparent view of edits while raw session/model output remains available by id. */
    val effectiveMetrics: EffectiveSessionMetrics? = null,
    val comparisonKey: SessionComparisonKey = SessionComparisonKey(ActivityMode.Unspecified),
    val correctionRevisionCount: Int = 0
)

/**
 * Browser detail envelope: reviewed values lead, while [raw] remains the immutable audit record.
 */
@Serializable
data class SessionDetailData(
    val raw: SessionExport,
    val reviewed: ReviewedSessionDetail
)

/** Timestamp-backed reviewed values safe to use for detail metrics, charts, and insights. */
@Serializable
data class ReviewedSessionDetail(
    val session: TrainingSession,
    val rallyProfile: RallyProfile,
    val effectiveMetrics: EffectiveSessionMetrics,
    val insights: List<Insight>
)

/**
 * A day on the training-volume trend.
 *
 * The chart deliberately uses only time inferred from detected racket-wrist contacts. It
 * does not turn uncalibrated stroke labels into tissue load, and it does not infer injury
 * risk or readiness from a rolling ratio.
 *
 * @property rolling7DayEstimatedActiveMillis Sum of inferred active time for this day and
 *   the previous six calendar days.
 * @property previous28DayWeeklyAverageEstimatedActiveMillis Weekly average over the four
 *   complete weeks immediately before the rolling seven-day window. Null until 35 calendar
 *   days of history exist; zeros on days without a recorded session remain part of history.
 */
@Serializable
data class VolumePoint(
    val dayEpochMillis: Long,
    val dailyElapsedMillis: Long,
    val dailyEstimatedActiveMillis: Long,
    val dailyDetectedHits: Int,
    val rolling7DayEstimatedActiveMillis: Long,
    val rolling7DayDetectedHits: Int,
    val previous28DayWeeklyAverageEstimatedActiveMillis: Long?
)

object Analytics {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun build(
        sessions: List<SessionExport>,
        filter: SessionAnalyticsFilter = SessionAnalyticsFilter()
    ): DashboardData {
        val appliedFilter = canonicalize(filter)
        val comparisonGroups = buildComparisonGroups(
            sessions.filter { it.isPlayerInferenceEligible }
        )
        val selectedSessions = sessions.filter { matchesFilter(it, appliedFilter) }
        if (selectedSessions.isEmpty()) {
            return DashboardData(
                sessionCount = 0,
                totalShots = 0,
                totalPlayingMillis = 0,
                totalElapsedMillis = 0,
                averageRestRatio = 0f,
                averageRallyShots = 0f,
                longestRallyShots = 0,
                shotDistribution = emptyList(),
                rallyHistogram = emptyList(),
                sessions = emptyList(),
                volumeTrend = emptyList(),
                appliedFilter = appliedFilter,
                comparisonGroups = comparisonGroups
            )
        }

        val reviewedSessions = selectedSessions.map { export ->
            export to export.reviewedAnalysis()
        }
        val cards = reviewedSessions
            .map { (export, analysis) -> toCard(export, sessions, analysis) }
            .sortedByDescending { it.startedAtMillis }

        val allRallies = reviewedSessions.flatMap { (_, analysis) ->
            analysis.rallyProfile.rallies
        }
        val distribution = reviewedSessions
            .flatMap { (_, analysis) -> analysis.detectedHits }
            .groupingBy { event -> event.type }
            .eachCount()

        return DashboardData(
            sessionCount = selectedSessions.size,
            totalShots = cards.sumOf { it.totalShots },
            totalPlayingMillis = cards.sumOf { it.estimatedActiveMillis },
            totalElapsedMillis = cards.sumOf { it.durationMillis },
            averageRestRatio = cards.map { it.restRatio }.filter { it > 0f }.averageOrZero(),
            averageRallyShots = if (allRallies.isEmpty()) 0f else
                allRallies.sumOf { it.shotCount }.toFloat() / allRallies.size,
            longestRallyShots = allRallies.maxOfOrNull { it.shotCount } ?: 0,
            shotDistribution = shotOrder
                .mapNotNull { type ->
                    distribution[type]?.takeIf { it > 0 }?.let { ShotSlice(type.name, it) }
                },
            rallyHistogram = buildRallyHistogram(allRallies.map { it.shotCount }),
            sessions = cards,
            volumeTrend = buildVolumeTrend(cards),
            appliedFilter = appliedFilter,
            comparisonGroups = comparisonGroups
        )
    }

    fun detail(export: SessionExport, history: List<SessionExport>): SessionDetailData {
        val analysis = export.reviewedAnalysis()
        val baseline = export.reviewedInsightBaseline(history)
        return SessionDetailData(
            raw = export,
            reviewed = ReviewedSessionDetail(
                session = analysis.session,
                rallyProfile = analysis.rallyProfile,
                effectiveMetrics = analysis.metrics,
                insights = insightsFor(export, analysis, baseline)
            )
        )
    }

    private val shotOrder = listOf(
        ShotType.Smash,
        ShotType.Clear,
        ShotType.Drop,
        ShotType.Drive,
        ShotType.BackhandDrive,
        ShotType.Unknown
    )

    private val insightEngine = SessionInsightEngine()

    private fun toCard(
        export: SessionExport,
        history: List<SessionExport>,
        analysis: ReviewedSessionAnalysis
    ): SessionCard {
        val summary = analysis.session.summary
        val rallies = analysis.rallyProfile
        val baseline = export.reviewedInsightBaseline(history)
        val effectiveMetrics = analysis.metrics
        return SessionCard(
            id = export.session.id,
            startedAtMillis = export.session.startedAtMillis,
            durationMillis = summary.durationMillis,
            totalShots = summary.totalShots,
            rallyCount = rallies.rallyCount,
            averageRallyShots = rallies.averageShotsPerRally,
            restRatio = rallies.restRatio,
            workDensity = rallies.workDensity,
            averageHeartRate = summary.averageHeartRate,
            maxHeartRate = summary.maxHeartRate,
            estimatedActiveMillis = rallies.totalWorkMillis,
            heartRateCoverage = summary.heartRateCoverage.coerceIn(0f, 1f),
            // Old sessions can contain contact-time HR values but no distinct optical-sensor
            // record. Numeric profile defaults also used to look configured. Only expose
            // internal load when both the measurement and profile provenance are explicit.
            cardiovascularLoad = summary.cardiovascularLoad?.takeIf {
                export.profile.hasConfiguredHeartRateReserve &&
                    summary.heartRateSampleCount > 0 &&
                    summary.heartRateCoverage >= MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
            },
            shotDistribution = shotOrder.mapNotNull { type ->
                summary.shotCounts[type]?.takeIf { it > 0 }?.let { ShotSlice(type.name, it) }
            },
            insights = insightsFor(export, analysis, baseline),
            context = export.context,
            report = export.report,
            effectiveMetrics = effectiveMetrics,
            comparisonKey = export.context.comparisonKey(),
            correctionRevisionCount = export.corrections.hitRevisions.size +
                export.corrections.trimRevisions.size
        )
    }

    private fun buildComparisonGroups(
        sessions: List<SessionExport>
    ): List<SessionComparisonGroup> = sessions
        .groupingBy { it.context.comparisonKey() }
        .eachCount()
        .map { (key, count) ->
            SessionComparisonGroup(
                key = key,
                sessionCount = count,
                baselineEligible = key.baselineEligible
            )
        }
        .sortedWith(compareBy({ it.key.activityMode.ordinal }, { it.key.comparisonTag.orEmpty() }))

    /**
     * Reviewed detector values remain visible for audit and aggregates, but editable diary quality
     * can never erase immutable process-absence provenance and re-enable an inferred observation.
     */
    private fun insightsFor(
        export: SessionExport,
        analysis: ReviewedSessionAnalysis,
        baseline: InsightBaseline
    ): List<Insight> = if (export.isPlayerInferenceEligible) {
        insightEngine.generate(
            session = analysis.session,
            rallyProfile = analysis.rallyProfile,
            baseline = baseline
        )
    } else {
        emptyList()
    }

    private fun matchesFilter(
        export: SessionExport,
        filter: SessionAnalyticsFilter
    ): Boolean {
        val context = export.context
        val canonicalTag = canonicalTag(filter.comparisonTag)
        return (filter.activityModes.isEmpty() || context.activityMode in filter.activityModes) &&
            (canonicalTag == null || context.comparisonKey().comparisonTag == canonicalTag) &&
            (filter.completions.isEmpty() || context.completion in filter.completions) &&
            (filter.recordingQualities.isEmpty() ||
                context.recordingQuality in filter.recordingQualities)
    }

    private fun canonicalize(filter: SessionAnalyticsFilter): SessionAnalyticsFilter =
        filter.copy(
            comparisonTag = canonicalTag(filter.comparisonTag),
            recordingQualities = filter.recordingQualities.ifEmpty {
                DEFAULT_AGGREGATE_RECORDING_QUALITIES
            }
        )

    private fun canonicalTag(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun buildRallyHistogram(shotCounts: List<Int>): List<HistogramBucket> {
        if (shotCounts.isEmpty()) return emptyList()
        val buckets = listOf(
            "1-4" to 1..4,
            "5-8" to 5..8,
            "9-12" to 9..12,
            "13-20" to 13..20,
            "21+" to 21..Int.MAX_VALUE
        )
        return buckets.map { (label, range) ->
            HistogramBucket(label, shotCounts.count { it in range })
        }
    }

    /**
     * Calendar-day volume with a transparent seven-day sum and prior four-week comparison.
     *
     * Days without a session count as zero rather than being skipped. The comparison is
     * descriptive only: no threshold is treated as a safe zone, readiness score, or injury
     * prediction. Elapsed time, inferred active time, and detected hits stay separate units.
     */
    private fun buildVolumeTrend(cards: List<SessionCard>): List<VolumePoint> {
        if (cards.isEmpty()) return emptyList()

        val byDay = cards.groupBy { startOfDay(it.startedAtMillis) }
            .mapValues { (_, sessions) ->
                DailyVolume(
                    elapsedMillis = sessions.sumOf { it.durationMillis },
                    estimatedActiveMillis = sessions.sumOf { it.estimatedActiveMillis },
                    detectedHits = sessions.sumOf { it.totalShots }
                )
            }

        val firstDay = byDay.keys.min()
        val lastDay = byDay.keys.max()
        val days = generateSequence(firstDay) { it + DAY_MILLIS }
            .takeWhile { it <= lastDay }
            .toList()

        return days.mapIndexed { index, day ->
            val daily = byDay[day] ?: DailyVolume.EMPTY
            val rolling7Days = days.slice(maxOf(0, index - 6)..index)
            // A fair prior comparison needs four complete weeks before the current 7-day
            // window. Until then, returning null is more honest than scaling partial history.
            val prior28Days = if (index >= 34) days.slice((index - 34)..(index - 7)) else null
            VolumePoint(
                dayEpochMillis = day,
                dailyElapsedMillis = daily.elapsedMillis,
                dailyEstimatedActiveMillis = daily.estimatedActiveMillis,
                dailyDetectedHits = daily.detectedHits,
                rolling7DayEstimatedActiveMillis = rolling7Days.sumOf {
                    (byDay[it] ?: DailyVolume.EMPTY).estimatedActiveMillis
                },
                rolling7DayDetectedHits = rolling7Days.sumOf {
                    (byDay[it] ?: DailyVolume.EMPTY).detectedHits
                },
                previous28DayWeeklyAverageEstimatedActiveMillis = prior28Days?.sumOf {
                    (byDay[it] ?: DailyVolume.EMPTY).estimatedActiveMillis
                }?.div(4)
            )
        }
    }

    private data class DailyVolume(
        val elapsedMillis: Long,
        val estimatedActiveMillis: Long,
        val detectedHits: Int
    ) {
        companion object {
            val EMPTY = DailyVolume(0L, 0L, 0)
        }
    }

    private fun startOfDay(epochMillis: Long): Long = epochMillis / DAY_MILLIS * DAY_MILLIS

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else (sum() / size)

    /** Convenience for the HTML layer, which wants whole percentages. */
    fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

    private val DEFAULT_AGGREGATE_RECORDING_QUALITIES = setOf(
        RecordingQuality.Unreviewed,
        RecordingQuality.Complete,
        RecordingQuality.Partial
    )
}

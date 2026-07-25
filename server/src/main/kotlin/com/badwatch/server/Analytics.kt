package com.badwatch.server

import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.InsightBaselineBuilder
import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
import com.badwatch.core.sync.SessionExport
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
    val volumeTrend: List<VolumePoint>
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
    /** Elapsed minutes x mean heart-rate reserve; null unless optical HR was measured. */
    val cardiovascularLoad: Float?,
    val shotDistribution: List<ShotSlice>,
    /**
     * Derived by the same `:core` engine the watch uses, so a session never carries two
     * different readings depending on where you look at it.
     */
    val insights: List<Insight> = emptyList()
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

    fun build(sessions: List<SessionExport>): DashboardData {
        if (sessions.isEmpty()) {
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
                volumeTrend = emptyList()
            )
        }

        val cards = sessions
            .map { toCard(it, sessions) }
            .sortedByDescending { it.startedAtMillis }

        val allRallies = sessions.flatMap { it.rallyProfile.rallies }
        val distribution = sessions
            .flatMap { it.session.summary.shotCounts.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, counts) -> counts.sum() }

        return DashboardData(
            sessionCount = sessions.size,
            totalShots = sessions.sumOf { it.session.summary.totalShots },
            totalPlayingMillis = sessions.sumOf { it.rallyProfile.totalWorkMillis },
            totalElapsedMillis = sessions.sumOf { it.session.summary.durationMillis },
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
            volumeTrend = buildVolumeTrend(cards)
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

    private fun toCard(export: SessionExport, history: List<SessionExport>): SessionCard {
        val summary = export.session.summary
        // Baseline uses only sessions that came *before* this one: judging a session against
        // data from its own future would make the same session read differently over time.
        val baseline = InsightBaselineBuilder.build(
            history
                .filter { it.session.startedAtMillis < export.session.startedAtMillis }
                .map { it.rallyProfile }
        )
        return SessionCard(
            id = export.session.id,
            startedAtMillis = export.session.startedAtMillis,
            durationMillis = summary.durationMillis,
            totalShots = summary.totalShots,
            rallyCount = export.rallyProfile.rallyCount,
            averageRallyShots = export.rallyProfile.averageShotsPerRally,
            restRatio = export.rallyProfile.restRatio,
            workDensity = export.rallyProfile.workDensity,
            averageHeartRate = summary.averageHeartRate,
            maxHeartRate = summary.maxHeartRate,
            estimatedActiveMillis = export.rallyProfile.totalWorkMillis,
            heartRateCoverage = summary.heartRateCoverage.coerceIn(0f, 1f),
            // Old sessions can contain contact-time HR values but no distinct optical-sensor
            // record. Only expose internal load when the recorder proves it measured HR.
            cardiovascularLoad = summary.cardiovascularLoad?.takeIf {
                summary.heartRateSampleCount > 0 &&
                    summary.heartRateCoverage >= MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
            },
            shotDistribution = shotOrder.mapNotNull { type ->
                summary.shotCounts[type]?.takeIf { it > 0 }?.let { ShotSlice(type.name, it) }
            },
            insights = insightEngine.generate(export.session, export.rallyProfile, baseline)
        )
    }

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
}

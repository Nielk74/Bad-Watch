package com.badwatch.server

import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.SessionExport
import kotlinx.serialization.Serializable
import kotlin.math.pow
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
    val loadTrend: List<LoadPoint>
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
    val averageHeartRate: Float,
    val maxHeartRate: Float,
    val shoulderLoad: Float,
    val shotDistribution: List<ShotSlice>
)

/**
 * A day on the load trend.
 *
 * @property acute Rolling 7-day load — what the body has just absorbed.
 * @property chronic Rolling 28-day load scaled to a weekly figure — what it is adapted to.
 * @property ratio Acute:chronic workload ratio. The sports-science literature places the
 *   elevated-injury-risk band above roughly 1.5, and undertraining below 0.8. It is a
 *   coarse signal, not a diagnosis, and the dashboard labels it as such.
 */
@Serializable
data class LoadPoint(
    val dayEpochMillis: Long,
    val dailyLoad: Float,
    val acute: Float,
    val chronic: Float,
    val ratio: Float
)

object Analytics {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Overhead strokes load the shoulder; midcourt and net strokes essentially do not. */
    private val OVERHEAD_SHOTS = setOf(ShotType.Smash, ShotType.Clear, ShotType.Drop)

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
                loadTrend = emptyList()
            )
        }

        val cards = sessions
            .map { toCard(it) }
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
            loadTrend = buildLoadTrend(cards)
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

    private fun toCard(export: SessionExport): SessionCard {
        val summary = export.session.summary
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
            shoulderLoad = shoulderLoad(export),
            shotDistribution = shotOrder.mapNotNull { type ->
                summary.shotCounts[type]?.takeIf { it > 0 }?.let { ShotSlice(type.name, it) }
            }
        )
    }

    /**
     * Shoulder load for one session.
     *
     * Cubed intensity, because the mechanical demand of an overhead stroke rises far faster
     * than its speed: fifty gentle clears and fifty full smashes are not the same session
     * for a rotator cuff, and a linear count treats them as identical. 6 rad/s is used as
     * the reference "hard smash" so a typical session lands in a readable double-digit range.
     */
    private fun shoulderLoad(export: SessionExport): Float =
        export.session.shots
            .filter { it.type in OVERHEAD_SHOTS }
            .sumOf { shot ->
                val intensity = (shot.peakAngularVelocity / 6f).coerceIn(0f, 2f)
                intensity.toDouble().pow(3.0)
            }
            .toFloat()

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
     * Daily acute:chronic workload ratio over the recorded history.
     *
     * Days without a session count as zero load rather than being skipped — rest is part of
     * the load picture, and omitting it would make every rolling average wrong.
     */
    private fun buildLoadTrend(cards: List<SessionCard>): List<LoadPoint> {
        if (cards.isEmpty()) return emptyList()

        val byDay = cards.groupBy { startOfDay(it.startedAtMillis) }
            .mapValues { (_, sessions) -> sessions.map { it.shoulderLoad }.sum() }

        val firstDay = byDay.keys.min()
        val lastDay = byDay.keys.max()
        val days = generateSequence(firstDay) { it + DAY_MILLIS }
            .takeWhile { it <= lastDay }
            .toList()

        return days.mapIndexed { index, day ->
            val daily = byDay[day] ?: 0f
            val acuteWindow = days.slice(maxOf(0, index - 6)..index)
            val chronicWindow = days.slice(maxOf(0, index - 27)..index)
            val acute = acuteWindow.sumOf { (byDay[it] ?: 0f).toDouble() }.toFloat()
            // Scale the 28-day total to a comparable weekly figure.
            val chronic = chronicWindow.sumOf { (byDay[it] ?: 0f).toDouble() }
                .toFloat() * 7f / chronicWindow.size
            LoadPoint(
                dayEpochMillis = day,
                dailyLoad = daily,
                acute = acute,
                chronic = chronic,
                ratio = if (chronic <= 0.01f) 0f else acute / chronic
            )
        }
    }

    private fun startOfDay(epochMillis: Long): Long = epochMillis / DAY_MILLIS * DAY_MILLIS

    private fun List<Float>.averageOrZero(): Float =
        if (isEmpty()) 0f else (sum() / size)

    /** Convenience for the HTML layer, which wants whole percentages. */
    fun percent(fraction: Float): Int = (fraction * 100).roundToInt()
}

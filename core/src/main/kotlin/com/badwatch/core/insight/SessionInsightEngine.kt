package com.badwatch.core.insight

import com.badwatch.core.model.Rally
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.TrainingSession
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Derives session insights from signals we actually trust.
 *
 * **Deliberately ignores stroke type.** The classifier is uncalibrated heuristics, so any
 * insight built on "you hit too few backhands" would be fabricated confidence. Rally
 * structure is an estimate built from gaps between detected racket-wrist hits, while heart
 * rate comes from a sensor. Copy must describe those observations without claiming a cause,
 * an opponent's shots, or a technique fault that the watch cannot see.
 *
 * When Phase 2 lands and stroke labels become trustworthy, stroke-based rules can join —
 * as additions, not replacements.
 *
 * Every rule follows the same discipline:
 *  - it states a threshold up front and cites the number it fired on;
 *  - it declines to fire when the sample is too small to mean anything;
 *  - it prefers the player's own history to sport-wide norms.
 */
class SessionInsightEngine(
    private val minimumRallies: Int = MINIMUM_RALLIES,
    private val maximumInsights: Int = 3
) {

    fun generate(
        session: TrainingSession,
        rallyProfile: RallyProfile,
        baseline: InsightBaseline = InsightBaseline.NONE
    ): List<Insight> {
        // Below this there is no session to describe. Saying nothing beats inventing.
        if (rallyProfile.rallyCount < minimumRallies) return emptyList()

        return listOfNotNull(
            restRatioInsight(rallyProfile, baseline),
            enduranceDecayInsight(rallyProfile),
            longestRallyInsight(rallyProfile, baseline),
            intensityInsight(session, rallyProfile),
            heartRateDriftInsight(session, rallyProfile)
        )
            // Strongest observations first, so a meaningful change is not buried under a
            // personal best. Medical, fatigue, readiness, and injury claims are outside the
            // evidence this engine receives.
            .sortedByDescending { it.severity.ordinal }
            .take(maximumInsights)
    }

    /**
     * Estimated active-to-rest ratio against the player's own norm. With no personal history,
     * a deliberately high threshold only reports the observed gaps; it does not compare a
     * mixed training session to elite match norms.
     */
    private fun restRatioInsight(profile: RallyProfile, baseline: InsightBaseline): Insight? {
        val ratio = profile.restRatio
        if (ratio <= 0f) return null

        val evidence = "1:${format(ratio)} estimated active:quiet across " +
            "${profile.rallyCount} detected exchanges"

        if (baseline.hasEnoughHistory && baseline.medianRestRatio != null) {
            val usual = baseline.medianRestRatio
            val change = (ratio - usual) / usual
            // 25% is comfortably outside session-to-session noise.
            if (change > 0.25f) {
                return Insight(
                    id = "rest-ratio-up",
                    headline = "Longer gaps than usual",
                    detail = "Gaps between detected exchanges were ${percent(change)}% longer " +
                        "than your typical 1:${format(usual)} active:quiet pattern.",
                    severity = InsightSeverity.Notable,
                    evidence = evidence,
                    localizationArgs = mapOf(
                        "ratio" to oneDecimal(ratio),
                        "exchanges" to profile.rallyCount.toString(),
                        "change" to percent(change).toString(),
                        "usual" to oneDecimal(usual)
                    )
                )
            }
            if (change < -0.25f) {
                return Insight(
                    id = "rest-ratio-down",
                    headline = "Denser session than usual",
                    detail = "Detected exchanges had ${percent(abs(change))}% less quiet time " +
                        "than your typical 1:${format(usual)} active:quiet pattern.",
                    severity = InsightSeverity.Info,
                    evidence = evidence,
                    localizationArgs = mapOf(
                        "ratio" to oneDecimal(ratio),
                        "exchanges" to profile.rallyCount.toString(),
                        "change" to percent(abs(change)).toString(),
                        "usual" to oneDecimal(usual)
                    )
                )
            }
            return null
        }

        // No history: describe only a conspicuous observation, not whether it is good.
        if (ratio > 3.5f) {
            return Insight(
                id = "rest-ratio-high",
                headline = "Long gaps between exchanges",
                    detail = "The detected pattern was 1:${format(ratio)} estimated active to quiet time. " +
                    "Use this as a baseline for your next comparable session.",
                severity = InsightSeverity.Notable,
                evidence = evidence,
                localizationArgs = mapOf(
                    "ratio" to oneDecimal(ratio),
                    "exchanges" to profile.rallyCount.toString()
                )
            )
        }
        return null
    }

    /**
     * Change in detected-hit count across the session. This is an observation only: opponent,
     * drill structure, missed detections and tactics can all change exchange length.
     */
    private fun enduranceDecayInsight(profile: RallyProfile): Insight? {
        // Thirds of fewer than four rallies each are too noisy to compare.
        if (profile.rallyCount < 12) return null

        val third = profile.rallyCount / 3
        val opening = profile.rallies.take(third).averageShots()
        val closing = profile.rallies.takeLast(third).averageShots()
        if (opening <= 0f) return null

        val change = (closing - opening) / opening
        if (change > -0.3f) return null

        return Insight(
            id = "endurance-decay",
            headline = "Detected exchanges shortened",
            detail = "The last $third exchanges averaged ${format(closing)} detected hits " +
                "against ${format(opening)} at the start — ${percent(abs(change))}% fewer. " +
                "The watch cannot tell whether tactics, errors, opponents or missed hits caused it.",
            severity = InsightSeverity.Notable,
            evidence = "${format(opening)} → ${format(closing)} detected hits per exchange",
            localizationArgs = mapOf(
                "third" to third.toString(),
                "closing" to oneDecimal(closing),
                "opening" to oneDecimal(opening),
                "change" to percent(abs(change)).toString()
            )
        )
    }

    private fun longestRallyInsight(profile: RallyProfile, baseline: InsightBaseline): Insight? {
        val longest = profile.longestRally ?: return null

        if (baseline.hasEnoughHistory && baseline.bestRallyShots != null) {
            if (longest.shotCount > baseline.bestRallyShots) {
                return Insight(
                    id = "longest-rally-best",
                    headline = "Longest detected exchange yet",
                    detail = "A ${longest.shotCount}-hit exchange, above your previous best of " +
                        "${baseline.bestRallyShots}.",
                    severity = InsightSeverity.Notable,
                    evidence = "${longest.shotCount} detected hits over ${seconds(longest.durationMillis)}s",
                    localizationArgs = mapOf(
                        "hits" to longest.shotCount.toString(),
                        "previous" to baseline.bestRallyShots.toString(),
                        "seconds" to seconds(longest.durationMillis).toString()
                    )
                )
            }
            return null
        }

        // No history: only flag rallies long enough to be objectively notable.
        if (longest.shotCount >= 20) {
            return Insight(
                id = "longest-rally",
                headline = "That was a long one",
                detail = "Your longest detected exchange ran ${longest.shotCount} hits over " +
                    "${seconds(longest.durationMillis)} seconds.",
                severity = InsightSeverity.Info,
                evidence = "${longest.shotCount} detected hits",
                localizationArgs = mapOf(
                    "hits" to longest.shotCount.toString(),
                    "seconds" to seconds(longest.durationMillis).toString()
                )
            )
        }
        return null
    }

    /**
     * How much of the session sat inside detected exchange windows.
     *
     * Reported only when it is low, because a healthy number here is not news.
     */
    private fun intensityInsight(session: TrainingSession, profile: RallyProfile): Insight? {
        val wholeSessionDensity = profile.workDensityOver(session.summary.durationMillis)
        if (wholeSessionDensity >= 0.2f) return null
        val minutes = (profile.totalWorkMillis / 60_000.0).roundToInt()
        val total = (session.summary.durationMillis / 60_000.0).roundToInt()
        if (total < 15) return null

        return Insight(
            id = "low-work-density",
            headline = "Low detected activity density",
            detail = "$minutes of $total minutes sat inside detected exchange windows. " +
                "Warm-up, drills without hits and missed detections can lower this estimate.",
            severity = InsightSeverity.Notable,
            evidence = "${percent(wholeSessionDensity)}% estimated active time",
            localizationArgs = mapOf(
                "activeMinutes" to minutes.toString(),
                "totalMinutes" to total.toString(),
                "activePercent" to percent(wholeSessionDensity).toString()
            )
        )
    }

    /**
     * Average heart rate in detected exchanges, early session vs late. This is not a recovery
     * or fatigue diagnosis: comparable exchange intensity is not yet measured.
     */
    private fun heartRateDriftInsight(session: TrainingSession, profile: RallyProfile): Insight? {
        // Sparse optical coverage can cluster readings at one end of a session and create
        // a convincing but meaningless drift. Old exports default to zero coverage and are
        // intentionally ineligible for this interpretation.
        if (session.summary.heartRateCoverage < MINIMUM_HEART_RATE_COVERAGE) return null
        val withHeartRate = profile.rallies.filter { it.averageHeartRate != null }
        if (withHeartRate.size < 8) return null

        val half = withHeartRate.size / 2
        val early = withHeartRate.take(half).mapNotNull { it.averageHeartRate }.average()
        val late = withHeartRate.takeLast(half).mapNotNull { it.averageHeartRate }.average()
        val drift = late - early

        if (drift < 8.0) return null

        return Insight(
            id = "cardiac-drift",
            headline = "Heart rate rose later",
            detail = "Average heart rate during detected exchanges rose ${drift.roundToInt()} bpm " +
                "from the first half to the second. Compare with how the session felt; the " +
                "watch does not yet know whether the exchanges were equally intense.",
            severity = InsightSeverity.Notable,
            evidence = "${early.roundToInt()} → ${late.roundToInt()} bpm across detected exchanges",
            localizationArgs = mapOf(
                "drift" to drift.roundToInt().toString(),
                "early" to early.roundToInt().toString(),
                "late" to late.roundToInt().toString()
            )
        )
    }

    private fun List<Rally>.averageShots(): Float =
        if (isEmpty()) 0f else sumOf { it.shotCount }.toFloat() / size

    private fun format(value: Float): String = ((value * 10).roundToInt() / 10.0).toString()

    private fun oneDecimal(value: Float): String =
        ((value * 10).roundToInt() / 10f).toString()

    private fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

    private fun seconds(millis: Long): Int = (millis / 1000.0).roundToInt()

    companion object {
        /** Fewer rallies than this is a warm-up, not a session worth characterising. */
        const val MINIMUM_RALLIES = 5
        const val MINIMUM_HEART_RATE_COVERAGE = 0.6f
    }
}

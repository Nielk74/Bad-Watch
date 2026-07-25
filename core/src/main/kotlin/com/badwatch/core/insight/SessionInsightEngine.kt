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
 * structure and heart rate, by contrast, are measured rather than inferred: a rally boundary
 * is a gap in time, and heart rate comes from a sensor. Those are the only inputs here.
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
            heartRateRecoveryInsight(rallyProfile)
        )
            // Caution first: if something suggests backing off, it should not be buried
            // under a personal best.
            .sortedByDescending { it.severity.ordinal }
            .take(maximumInsights)
    }

    /**
     * Work-to-rest ratio against the player's own norm, or the sport's if there is no norm yet.
     *
     * Singles play sits near 1:2 and doubles near 1:1.5. Drifting toward 1:4 usually means
     * long gaps collecting shuttles or chatting between points — worth knowing, because it
     * is invisible from inside the session and it halves the training effect of an hour.
     */
    private fun restRatioInsight(profile: RallyProfile, baseline: InsightBaseline): Insight? {
        val ratio = profile.restRatio
        if (ratio <= 0f) return null

        val evidence = "1:${format(ratio)} work:rest across ${profile.rallyCount} rallies"

        if (baseline.hasEnoughHistory && baseline.medianRestRatio != null) {
            val usual = baseline.medianRestRatio
            val change = (ratio - usual) / usual
            // 25% is comfortably outside session-to-session noise.
            if (change > 0.25f) {
                return Insight(
                    id = "rest-ratio-up",
                    headline = "You rested more than usual",
                    detail = "Rest between rallies was ${percent(change)}% longer than your " +
                        "typical 1:${format(usual)}. Same court time, less training.",
                    severity = InsightSeverity.Notable,
                    evidence = evidence
                )
            }
            if (change < -0.25f) {
                return Insight(
                    id = "rest-ratio-down",
                    headline = "Denser session than usual",
                    detail = "You played with ${percent(abs(change))}% less rest than your " +
                        "typical 1:${format(usual)}.",
                    severity = InsightSeverity.Info,
                    evidence = evidence
                )
            }
            return null
        }

        // No history: fall back to the sport's range.
        if (ratio > 3.5f) {
            return Insight(
                id = "rest-ratio-high",
                headline = "Long gaps between rallies",
                detail = "Competitive singles sits near 1:2. At 1:${format(ratio)} you spent " +
                    "${percent(1f - profile.workDensity)}% of the session not playing.",
                severity = InsightSeverity.Notable,
                evidence = evidence
            )
        }
        return null
    }

    /**
     * Rally length decay across the session — the clearest fatigue signal available without
     * a trustworthy classifier.
     *
     * Compares the first and last thirds. Shorter rallies late usually means points are
     * ending on errors rather than winners.
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
            headline = "Rallies got shorter as you tired",
            detail = "Your last ${third} rallies averaged ${format(closing)} shots against " +
                "${format(opening)} at the start — ${percent(abs(change))}% shorter. Points " +
                "ending early late in a session usually means errors, not winners.",
            severity = InsightSeverity.Caution,
            evidence = "${format(opening)} → ${format(closing)} shots per rally"
        )
    }

    private fun longestRallyInsight(profile: RallyProfile, baseline: InsightBaseline): Insight? {
        val longest = profile.longestRally ?: return null

        if (baseline.hasEnoughHistory && baseline.bestRallyShots != null) {
            if (longest.shotCount > baseline.bestRallyShots) {
                return Insight(
                    id = "longest-rally-best",
                    headline = "Longest rally yet",
                    detail = "A ${longest.shotCount}-shot rally, beating your previous best of " +
                        "${baseline.bestRallyShots}.",
                    severity = InsightSeverity.Notable,
                    evidence = "${longest.shotCount} shots over ${seconds(longest.durationMillis)}s"
                )
            }
            return null
        }

        // No history: only flag rallies long enough to be objectively notable.
        if (longest.shotCount >= 20) {
            return Insight(
                id = "longest-rally",
                headline = "That was a long one",
                detail = "Your longest rally ran ${longest.shotCount} shots over " +
                    "${seconds(longest.durationMillis)} seconds.",
                severity = InsightSeverity.Info,
                evidence = "${longest.shotCount} shots"
            )
        }
        return null
    }

    /**
     * How much of the session was spent actually playing.
     *
     * Reported only when it is low, because a healthy number here is not news.
     */
    private fun intensityInsight(session: TrainingSession, profile: RallyProfile): Insight? {
        if (profile.workDensity >= 0.2f) return null
        val minutes = (profile.totalWorkMillis / 60_000.0).roundToInt()
        val total = (session.summary.durationMillis / 60_000.0).roundToInt()
        if (total < 15) return null

        return Insight(
            id = "low-work-density",
            headline = "Mostly standing around",
            detail = "Only $minutes of $total minutes were spent in rallies. Shorter sessions " +
                "with less standing usually beat long ones with a lot.",
            severity = InsightSeverity.Notable,
            evidence = "${percent(profile.workDensity)}% of the session in play"
        )
    }

    /**
     * Heart-rate drop during rest intervals, early session vs late.
     *
     * A recovery that shrinks over a session is a well-established fatigue marker, and it
     * shows up before the player notices. Requires heart rate on both ends, so it stays
     * silent on watches with no sensor lock.
     */
    private fun heartRateRecoveryInsight(profile: RallyProfile): Insight? {
        val withHeartRate = profile.rallies.filter { it.averageHeartRate != null }
        if (withHeartRate.size < 8) return null

        val half = withHeartRate.size / 2
        val early = withHeartRate.take(half).mapNotNull { it.averageHeartRate }.average()
        val late = withHeartRate.takeLast(half).mapNotNull { it.averageHeartRate }.average()
        val drift = late - early

        // Rising average heart rate at equal or lower rally intensity is cardiac drift.
        if (drift < 8.0) return null

        return Insight(
            id = "cardiac-drift",
            headline = "Working harder for the same rallies",
            detail = "Your average rally heart rate rose ${drift.roundToInt()} bpm from the " +
                "first half to the second. That is normal late in a hard session — worth " +
                "noticing if it happens early.",
            severity = InsightSeverity.Caution,
            evidence = "${early.roundToInt()} → ${late.roundToInt()} bpm average across rallies"
        )
    }

    private fun List<Rally>.averageShots(): Float =
        if (isEmpty()) 0f else sumOf { it.shotCount }.toFloat() / size

    private fun format(value: Float): String = ((value * 10).roundToInt() / 10.0).toString()

    private fun percent(fraction: Float): Int = (fraction * 100).roundToInt()

    private fun seconds(millis: Long): Int = (millis / 1000.0).roundToInt()

    companion object {
        /** Fewer rallies than this is a warm-up, not a session worth characterising. */
        const val MINIMUM_RALLIES = 5
    }
}

package com.badwatch.core.insight

import com.badwatch.core.model.RallyProfile

/**
 * Builds a player's "normal" from their previous sessions.
 *
 * Medians rather than means: one freak session — an hour of casual knock-up, or a single
 * tournament — should not redefine what normal looks like for the next month.
 */
object InsightBaselineBuilder {

    /**
     * @param history Rally profiles of previous sessions, excluding the one being analysed.
     */
    fun build(history: List<RallyProfile>): InsightBaseline {
        val usable = history.filter { it.rallyCount >= SessionInsightEngine.MINIMUM_RALLIES }
        if (usable.isEmpty()) return InsightBaseline.NONE

        return InsightBaseline(
            sessionCount = usable.size,
            medianRestRatio = usable.map { it.restRatio }.filter { it > 0f }.medianOrNull(),
            medianRallyShots = usable.map { it.averageShotsPerRally }
                .filter { it > 0f }
                .medianOrNull(),
            bestRallyShots = usable.mapNotNull { it.longestRally?.shotCount }.maxOrNull()
        )
    }

    private fun List<Float>.medianOrNull(): Float? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}

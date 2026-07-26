package com.badwatch.app.ui

import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.ReportedSoreness

/**
 * The soreness question is an edit of one small part of a potentially richer diary.
 * Keeping the player's decision explicit prevents reopening the watch flow from silently
 * collapsing multiple dashboard-entered body areas into the single area the watch can add.
 */
internal sealed interface SorenessReviewDecision {
    /** The player skipped this question; retain every previously recorded area. */
    data object Preserve : SorenessReviewDecision

    /** The player explicitly chose "Nothing to log"; clear all previous entries. */
    data object Clear : SorenessReviewDecision

    /** Add one entry, replacing only an existing entry for the same area and side. */
    data class AddOrReplace(val soreness: ReportedSoreness) : SorenessReviewDecision
}

internal fun PostSessionReport.applySorenessDecision(
    decision: SorenessReviewDecision
): PostSessionReport = when (decision) {
    SorenessReviewDecision.Preserve -> copy(
        // A non-empty list is itself evidence that soreness was reviewed. Repair legacy
        // inconsistent envelopes while preserving a genuinely unanswered empty report.
        sorenessReviewed = sorenessReviewed || soreness.isNotEmpty()
    )

    SorenessReviewDecision.Clear -> copy(
        soreness = emptyList(),
        sorenessReviewed = true
    )

    is SorenessReviewDecision.AddOrReplace -> copy(
        soreness = soreness.filterNot {
            it.bodyArea == decision.soreness.bodyArea && it.side == decision.soreness.side
        } + decision.soreness,
        sorenessReviewed = true
    )
}

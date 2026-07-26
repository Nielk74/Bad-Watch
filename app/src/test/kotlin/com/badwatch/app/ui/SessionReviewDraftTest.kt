package com.badwatch.app.ui

import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.BodySide
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.ReportedSoreness
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionReviewDraftTest {

    @Test
    fun skippedQuestionPreservesEveryExistingArea() {
        val shoulder = soreness(BodyArea.Shoulder, 3, BodySide.Right)
        val knee = soreness(BodyArea.Knee, 6, BodySide.Left)
        val original = PostSessionReport(
            rpe = 7,
            soreness = listOf(shoulder, knee),
            sorenessReviewed = true
        )

        val revised = original.applySorenessDecision(SorenessReviewDecision.Preserve)

        assertThat(revised.soreness).containsExactly(shoulder, knee).inOrder()
        assertThat(revised.sorenessReviewed).isTrue()
    }

    @Test
    fun explicitNothingToLogClearsAllAreasAndRecordsReview() {
        val original = PostSessionReport(
            soreness = listOf(
                soreness(BodyArea.Shoulder, 3, BodySide.Right),
                soreness(BodyArea.Knee, 6, BodySide.Left)
            ),
            sorenessReviewed = true
        )

        val revised = original.applySorenessDecision(SorenessReviewDecision.Clear)

        assertThat(revised.soreness).isEmpty()
        assertThat(revised.sorenessReviewed).isTrue()
    }

    @Test
    fun watchEntryReplacesOnlyMatchingAreaAndSide() {
        val oldRightShoulder = soreness(BodyArea.Shoulder, 2, BodySide.Right)
        val leftShoulder = soreness(BodyArea.Shoulder, 4, BodySide.Left)
        val knee = soreness(BodyArea.Knee, 6, BodySide.Left)
        val replacement = soreness(BodyArea.Shoulder, 8, BodySide.Right)
        val original = PostSessionReport(
            soreness = listOf(oldRightShoulder, leftShoulder, knee),
            sorenessReviewed = true
        )

        val revised = original.applySorenessDecision(
            SorenessReviewDecision.AddOrReplace(replacement)
        )

        assertThat(revised.soreness).containsExactly(leftShoulder, knee, replacement).inOrder()
        assertThat(revised.sorenessReviewed).isTrue()
    }

    @Test
    fun preservingLegacyNonEmptyReportRepairsReviewedFlag() {
        val original = PostSessionReport(
            soreness = listOf(soreness(BodyArea.Wrist, 5, BodySide.Unspecified)),
            sorenessReviewed = false
        )

        val revised = original.applySorenessDecision(SorenessReviewDecision.Preserve)

        assertThat(revised.soreness).isEqualTo(original.soreness)
        assertThat(revised.sorenessReviewed).isTrue()
    }

    private fun soreness(
        area: BodyArea,
        severity: Int,
        side: BodySide
    ) = ReportedSoreness(bodyArea = area, severity = severity, side = side)
}

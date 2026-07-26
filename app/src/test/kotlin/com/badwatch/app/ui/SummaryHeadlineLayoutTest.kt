package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SummaryHeadlineLayoutTest {

    @Test
    fun shortDurationKeepsGlanceableThreeUpRow() {
        assertThat(summaryHeadlineLayout(durationMillis = 0L, fontScale = 1f))
            .isEqualTo(SummaryHeadlineLayout.ThreeUp)
        assertThat(summaryHeadlineLayout(durationMillis = 3_599_999L, fontScale = 1.19f))
            .isEqualTo(SummaryHeadlineLayout.ThreeUp)
    }

    @Test
    fun hourDurationGetsDedicatedFullWidthRow() {
        assertThat(summaryHeadlineLayout(durationMillis = 3_600_000L, fontScale = 1f))
            .isEqualTo(SummaryHeadlineLayout.WideDuration)
        assertThat(summaryHeadlineLayout(durationMillis = 10L * 60L * 60L * 1_000L, fontScale = 1f))
            .isEqualTo(SummaryHeadlineLayout.WideDuration)
    }

    @Test
    fun enlargedTextUsesAccessibilityStackEvenForLongSessions() {
        assertThat(summaryHeadlineLayout(durationMillis = 3L * 60L * 60L * 1_000L, fontScale = 1.2f))
            .isEqualTo(SummaryHeadlineLayout.AccessibilityStack)
        assertThat(summaryHeadlineLayout(durationMillis = 100L * 60L * 60L * 1_000L, fontScale = 1.3f))
            .isEqualTo(SummaryHeadlineLayout.AccessibilityStack)
    }
}

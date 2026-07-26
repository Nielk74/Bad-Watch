package com.badwatch.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchComponentsLayoutTest {

    @Test
    fun threeUpMetricsStackAtAccessibilityFontScale() {
        assertThat(shouldStackStats(statCount = 3, fontScale = 1.19f)).isFalse()
        assertThat(shouldStackStats(statCount = 3, fontScale = 1.2f)).isTrue()
        assertThat(shouldStackStats(statCount = 4, fontScale = 1.3f)).isTrue()
    }

    @Test
    fun oneAndTwoUpMetricsKeepTheirGlanceableRow() {
        assertThat(shouldStackStats(statCount = 1, fontScale = 2f)).isFalse()
        assertThat(shouldStackStats(statCount = 2, fontScale = 2f)).isFalse()
    }

    @Test
    fun subHourDurationsKeepTheGlanceableThreeUpRow() {
        assertThat(durationStatRowLayout(durationMillis = 0L, fontScale = 1f))
            .isEqualTo(DurationStatRowLayout.ThreeUp)
        assertThat(durationStatRowLayout(durationMillis = 3_599_999L, fontScale = 1.19f))
            .isEqualTo(DurationStatRowLayout.ThreeUp)
    }

    @Test
    fun hourAndMultiHourDurationsGetAFullWidthRow() {
        assertThat(durationStatRowLayout(durationMillis = 3_600_000L, fontScale = 1f))
            .isEqualTo(DurationStatRowLayout.WideDuration)
        assertThat(durationStatRowLayout(durationMillis = 10L * 60L * 60L * 1_000L, fontScale = 1f))
            .isEqualTo(DurationStatRowLayout.WideDuration)
    }

    @Test
    fun enlargedTextUsesAccessibilityStackEvenForVeryLongDurations() {
        assertThat(durationStatRowLayout(durationMillis = 3L * 60L * 60L * 1_000L, fontScale = 1.2f))
            .isEqualTo(DurationStatRowLayout.AccessibilityStack)
        assertThat(durationStatRowLayout(durationMillis = 100L * 60L * 60L * 1_000L, fontScale = 1.3f))
            .isEqualTo(DurationStatRowLayout.AccessibilityStack)
    }
}

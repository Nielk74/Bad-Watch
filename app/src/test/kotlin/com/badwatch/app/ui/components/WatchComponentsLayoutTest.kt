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
}

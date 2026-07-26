package com.badwatch.app.domain

import com.badwatch.core.training.CourtCorner
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShadowCueHapticsTest {

    @Test
    fun allSixCornersHaveDistinctBoundedPatterns() {
        val patterns = CourtCorner.entries.associateWith(ShadowCueHaptics::patternFor)

        assertThat(patterns.values.toSet()).hasSize(CourtCorner.entries.size)
        patterns.values.forEach { pattern ->
            assertThat(pattern.first()).isEqualTo(0L)
            assertThat(pattern.sum()).isAtMost(500L)
            assertThat(pattern.drop(1)).doesNotContain(0L)
        }
    }

    @Test
    fun sideUsesOpeningClusterAndDepthUsesFinalPulseLength() {
        val forehandFront = ShadowCueHaptics.patternFor(CourtCorner.ForehandFront)
        val backhandFront = ShadowCueHaptics.patternFor(CourtCorner.BackhandFront)
        val forehandMid = ShadowCueHaptics.patternFor(CourtCorner.ForehandMid)
        val forehandRear = ShadowCueHaptics.patternFor(CourtCorner.ForehandRear)

        assertThat(forehandFront).hasSize(4)
        assertThat(backhandFront).hasSize(6)
        assertThat(forehandFront.last()).isLessThan(forehandMid.last())
        assertThat(forehandMid.last()).isLessThan(forehandRear.last())
    }
}

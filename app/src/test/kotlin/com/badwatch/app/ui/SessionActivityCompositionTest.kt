package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionActivityCompositionTest {

    @Test
    fun recoveredWallTimeHasExplicitUnobservedShareAndTotalsOneHundredPercent() {
        val composition = sessionActivityComposition(
            durationMillis = 60_000L,
            detectedActiveMillis = 15_000L,
            knownUnobservedMillis = 10_000L
        )

        assertThat(composition.activeMillis).isEqualTo(15_000L)
        assertThat(composition.quietMillis).isEqualTo(35_000L)
        assertThat(composition.unobservedMillis).isEqualTo(10_000L)
        assertThat(
            composition.activePercent + composition.quietPercent + composition.unobservedPercent
        ).isEqualTo(100)
        assertThat(composition.activePercent).isEqualTo(25)
        assertThat(composition.unobservedPercent).isEqualTo(17)
    }

    @Test
    fun cleanSessionUsesWallDenominatorIncludingTimeBeforeFirstDetectedExchange() {
        val composition = sessionActivityComposition(
            durationMillis = 60_000L,
            detectedActiveMillis = 20_000L,
            knownUnobservedMillis = 0L
        )

        assertThat(composition.activePercent).isEqualTo(33)
        assertThat(composition.quietPercent).isEqualTo(67)
        assertThat(composition.unobservedPercent).isEqualTo(0)
    }

    @Test
    fun malformedInputsCannotOverfillTheWallWindow() {
        val composition = sessionActivityComposition(
            durationMillis = 1_000L,
            detectedActiveMillis = 900L,
            knownUnobservedMillis = 400L
        )

        assertThat(composition.activeMillis).isEqualTo(600L)
        assertThat(composition.quietMillis).isEqualTo(0L)
        assertThat(composition.unobservedMillis).isEqualTo(400L)
        assertThat(composition.activePercent).isEqualTo(60)
        assertThat(composition.unobservedPercent).isEqualTo(40)
    }
}

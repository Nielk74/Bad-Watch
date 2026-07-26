package com.badwatch.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionOngoingModelTest {

    @Test
    fun stopwatchStartsAtRecordedDurationBeforeMonotonicNow() {
        assertThat(
            stopwatchStartElapsedRealtime(
                nowElapsedRealtime = 20_000L,
                durationMillis = 7_500L
            )
        ).isEqualTo(12_500L)
    }

    @Test
    fun stopwatchOriginIsSafeForInvalidOrPreBootDurations() {
        assertThat(stopwatchStartElapsedRealtime(5_000L, -1L)).isEqualTo(5_000L)
        assertThat(stopwatchStartElapsedRealtime(5_000L, 8_000L)).isEqualTo(0L)
    }

    @Test
    fun stopwatchOnlyRebasesForMeaningfulRecoveryDrift() {
        assertThat(needsStopwatchRebase(10_000L, 9_200L, 1_500L)).isFalse()
        assertThat(needsStopwatchRebase(10_000L, 7_000L, 1_500L)).isTrue()
    }
}

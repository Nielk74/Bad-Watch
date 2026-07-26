package com.badwatch.core.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShadowTrainerTest {

    @Test
    fun `seeded sequence is deterministic balanced and has no adjacent repeats`() {
        val first = ShadowTrainer.sequence(seed = 42L, count = 60)
        val second = ShadowTrainer.sequence(seed = 42L, count = 60)

        assertThat(first).containsExactlyElementsIn(second).inOrder()
        first.chunked(6).forEach { batch ->
            assertThat(batch).containsExactlyElementsIn(CourtCorner.entries)
        }
        first.zipWithNext().forEach { (left, right) -> assertThat(left).isNotEqualTo(right) }
    }

    @Test
    fun `different seeds produce different routines`() {
        assertThat(ShadowTrainer.sequence(1L, 18))
            .isNotEqualTo(ShadowTrainer.sequence(2L, 18))
    }

    @Test
    fun `confirmation records player response and completes at target`() {
        var state = ShadowTrainer.start(seed = 7L, targetRepetitions = 2, nowMillis = 1_000L)
        val firstCorner = state.currentCorner

        state = ShadowTrainer.confirm(state, nowMillis = 2_250L)
        assertThat(state.repetitions.single().corner).isEqualTo(firstCorner)
        assertThat(state.repetitions.single().responseMillis).isEqualTo(1_250L)
        assertThat(state.status).isEqualTo(ShadowStatus.Active)

        state = ShadowTrainer.confirm(state, nowMillis = 3_000L)
        assertThat(state.status).isEqualTo(ShadowStatus.Complete)
        assertThat(state.currentCorner).isNull()
        assertThat(state.completedRepetitions).isEqualTo(2)
        assertThat(state.progress).isEqualTo(1f)
    }

    @Test
    fun `paused time is excluded from response time`() {
        val started = ShadowTrainer.start(seed = 3L, targetRepetitions = 1, nowMillis = 1_000L)
        val paused = ShadowTrainer.pause(started, nowMillis = 2_000L)

        assertThat(ShadowTrainer.confirm(paused, nowMillis = 20_000L)).isEqualTo(paused)

        val resumed = ShadowTrainer.resume(paused, nowMillis = 12_000L)
        val complete = ShadowTrainer.confirm(resumed, nowMillis = 13_500L)

        assertThat(complete.repetitions.single().responseMillis).isEqualTo(2_500L)
    }

    @Test
    fun `summary is descriptive and supports an early finish`() {
        var state = ShadowTrainer.start(seed = 9L, targetRepetitions = 5, nowMillis = 0L)
        state = ShadowTrainer.confirm(state, 1_000L)
        state = ShadowTrainer.confirm(state, 3_000L)
        state = ShadowTrainer.finish(state)

        val summary = ShadowTrainer.summary(state)

        assertThat(summary.completedRepetitions).isEqualTo(2)
        assertThat(summary.targetRepetitions).isEqualTo(5)
        assertThat(summary.fastestResponseMillis).isEqualTo(1_000L)
        assertThat(summary.medianResponseMillis).isEqualTo(1_500L)
        assertThat(summary.finished).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `routine rejects an unbounded repetition count`() {
        ShadowTrainer.start(seed = 1L, targetRepetitions = 301, nowMillis = 0L)
    }
}

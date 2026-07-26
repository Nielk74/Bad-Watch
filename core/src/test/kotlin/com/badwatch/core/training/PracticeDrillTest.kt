package com.badwatch.core.training

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PracticeDrillTest {

    @Test
    fun `every drill is sourced and states its measurement boundary`() {
        assertThat(BwfPracticeLibrary.drills).isNotEmpty()
        BwfPracticeLibrary.drills.forEach { drill ->
            assertThat(drill.id).isNotEmpty()
            assertThat(drill.steps.size).isAtLeast(3)
            assertThat(drill.sourceTitle).contains("BWF")
            assertThat(drill.sourceUrl).startsWith("https://")
            assertThat(drill.measurementNote.lowercase()).contains("watch")
        }
    }

    @Test
    fun `identifiers are stable and unique`() {
        val ids = BwfPracticeLibrary.drills.map { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
        assertThat(BwfPracticeLibrary.byId("six-corner-shadow")?.durationMinutes).isEqualTo(6)
    }
}

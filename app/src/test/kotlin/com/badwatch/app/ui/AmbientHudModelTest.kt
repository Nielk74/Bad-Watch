package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AmbientHudModelTest {

    @Test
    fun modelUsesProvidedLocalClockFormatterAndHitSnapshot() {
        val model = ambientHudModel(
            ambientTimeMillis = 123_456L,
            detectedHitCount = 42,
            formatLocalTime = { instant -> "local-$instant" }
        )

        assertThat(model.clockText).isEqualTo("local-123456")
        assertThat(model.detectedHitCount).isEqualTo(42)
    }

    @Test
    fun modelNeverShowsAnImpossibleNegativeHitCount() {
        assertThat(
            ambientHudModel(0L, -3) { "12:00" }.detectedHitCount
        ).isEqualTo(0)
    }
}

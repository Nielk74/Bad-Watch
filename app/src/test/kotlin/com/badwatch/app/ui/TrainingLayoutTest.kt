package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrainingLayoutTest {

    @Test
    fun enlargedTextUsesCompactShadowHud() {
        assertThat(shadowUsesLargeTextLayout(1.19f)).isFalse()
        assertThat(shadowUsesLargeTextLayout(1.2f)).isTrue()
        assertThat(shadowUsesLargeTextLayout(1.3f)).isTrue()
    }
}

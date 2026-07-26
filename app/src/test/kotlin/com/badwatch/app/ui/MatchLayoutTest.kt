package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MatchLayoutTest {

    @Test
    fun enlargedTextUsesCompactMatchActions() {
        assertThat(matchUsesLargeTextLayout(1.19f)).isFalse()
        assertThat(matchUsesLargeTextLayout(1.2f)).isTrue()
        assertThat(matchUsesLargeTextLayout(1.3f)).isTrue()
    }
}

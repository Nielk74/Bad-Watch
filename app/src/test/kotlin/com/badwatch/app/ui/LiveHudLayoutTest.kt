package com.badwatch.app.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveHudLayoutTest {

    @Test
    fun enlargedTextReservesTheStopActionLane() {
        assertThat(liveHudNeedsStopClearance(1.19f)).isFalse()
        assertThat(liveHudNeedsStopClearance(1.2f)).isTrue()
        assertThat(liveHudNeedsStopClearance(1.3f)).isTrue()
    }
}

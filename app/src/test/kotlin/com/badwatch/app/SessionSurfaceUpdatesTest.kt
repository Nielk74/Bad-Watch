package com.badwatch.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionSurfaceUpdatesTest {

    @Test
    fun requestersAreIndependentAndCannotEscapeIntoSessionStorage() {
        var complicationRequests = 0
        var tileRequests = 0

        requestSessionSurfaceUpdates(
            complication = {
                complicationRequests++
                error("watch face host unavailable")
            },
            tile = {
                tileRequests++
                error("tile host unavailable")
            }
        )

        assertThat(complicationRequests).isEqualTo(1)
        assertThat(tileRequests).isEqualTo(1)
    }
}

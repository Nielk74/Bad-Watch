package com.badwatch.app.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeartRateZoneUiTest {

    @Test
    fun `zones use percentage of the player's estimated maximum`() {
        val maxHeartRate = 200f

        assertThat(hrZoneOf(119f, maxHeartRate)).isEqualTo(1)
        assertThat(hrZoneOf(120f, maxHeartRate)).isEqualTo(2)
        assertThat(hrZoneOf(140f, maxHeartRate)).isEqualTo(3)
        assertThat(hrZoneOf(160f, maxHeartRate)).isEqualTo(4)
        assertThat(hrZoneOf(180f, maxHeartRate)).isEqualTo(5)
    }

    @Test
    fun `same reading adapts to player profile`() {
        assertThat(hrZoneOf(150f, maxHeartRate = 200f)).isEqualTo(3)
        assertThat(hrZoneOf(150f, maxHeartRate = 170f)).isEqualTo(4)
    }

    @Test
    fun `missing and invalid readings have no displayed zone`() {
        assertThat(hrZoneOf(null, maxHeartRate = 190f)).isEqualTo(0)
        assertThat(hrZoneOf(0f, maxHeartRate = 190f)).isEqualTo(0)
        assertThat(hrZoneOf(140f, maxHeartRate = 0f)).isEqualTo(0)
    }
}

package com.badwatch.core.pipeline

import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SampleWindowTest {

    private fun sample(at: Long) = SensorSample(
        timestampMillis = at,
        gyro = Vector3(0f, 0f, 0f),
        accel = Vector3(0f, 0f, 0f),
        heartRateBpm = null
    )

    @Test
    fun `facade reflects contents in insertion order`() {
        val window = SampleWindow(capacity = 4)
        window.addLast(sample(10))
        window.addLast(sample(20))
        window.addLast(sample(30))

        assertThat(window.asList().map { it.timestampMillis }).containsExactly(10L, 20L, 30L).inOrder()
        assertThat(window.asList()).hasSize(3)
    }

    @Test
    fun `oldest sample is overwritten when capacity is exceeded`() {
        val window = SampleWindow(capacity = 3)
        repeat(5) { window.addLast(sample(it.toLong())) }

        assertThat(window.asList().map { it.timestampMillis }).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(window.size).isEqualTo(3)
    }

    @Test
    fun `trimBefore evicts old samples from the leading edge`() {
        val window = SampleWindow(capacity = 8)
        repeat(6) { window.addLast(sample(it * 100L)) }

        window.trimBefore(250)

        assertThat(window.asList().map { it.timestampMillis })
            .containsExactly(300L, 400L, 500L).inOrder()
    }

    @Test
    fun `clear empties the ring for reuse`() {
        val window = SampleWindow(capacity = 4)
        repeat(3) { window.addLast(sample(it.toLong())) }
        window.clear()

        assertThat(window.size).isEqualTo(0)
        window.addLast(sample(42))
        assertThat(window.asList().map { it.timestampMillis }).containsExactly(42L)
    }

    @Test
    fun `indexed access matches iteration after wrap-around`() {
        val window = SampleWindow(capacity = 4)
        repeat(6) { window.addLast(sample(it.toLong())) }
        val list = window.asList()

        for (i in list.indices) {
            assertThat(list[i]).isSameInstanceAs(list.toList()[i])
        }
        assertThat(list.last().timestampMillis).isEqualTo(5L)
    }
}

package com.badwatch.core.pipeline

import com.badwatch.core.model.SensorSample

/**
 * Fixed-capacity ring buffer for the sliding detection window.
 *
 * Replaces `ArrayDeque` + `toList()`: the old pipeline allocated a fresh list on every
 * sample — at 100 Hz across three sensors that is a steady stream of garbage on the hot
 * path of a battery-constrained watch. Here samples land in a pre-allocated array and the
 * classifier sees a zero-copy [List] facade over the live ring.
 *
 * Capacity is sized so the window can never overflow in practice: 260 ms at 100 Hz is 26
 * samples. If it ever did fill (a far-future sensor at a much higher rate), the oldest
 * sample is overwritten — the same sample [trimBefore] would have evicted moments later.
 */
class SampleWindow(capacity: Int = DEFAULT_CAPACITY) {
    private val samples = arrayOfNulls<SensorSample>(capacity)
    private var start = 0

    var size: Int = 0
        private set

    private val capacity = samples.size

    /** Single persistent facade — reused across calls, so [asList] allocates nothing. */
    private val facade = object : AbstractList<SensorSample>() {
        override val size: Int get() = this@SampleWindow.size

        override fun get(index: Int): SensorSample {
            if (index < 0 || index >= this@SampleWindow.size) {
                throw IndexOutOfBoundsException("index $index, size ${this@SampleWindow.size}")
            }
            @Suppress("UNCHECKED_CAST")
            return samples[(start + index) % capacity] as SensorSample
        }
    }

    fun addLast(sample: SensorSample) {
        samples[(start + size) % capacity] = sample
        if (size < capacity) {
            size++
        } else {
            start = (start + 1) % capacity
        }
    }

    fun first(): SensorSample {
        check(size > 0) { "window is empty" }
        @Suppress("UNCHECKED_CAST")
        return samples[start] as SensorSample
    }

    fun removeFirst() {
        check(size > 0) { "window is empty" }
        samples[start] = null
        start = (start + 1) % capacity
        size--
    }

    /** Evicts every sample older than [timestampMillis] — the leading edge of the window. */
    fun trimBefore(timestampMillis: Long) {
        while (size > 0 && first().timestampMillis < timestampMillis) {
            removeFirst()
        }
    }

    fun clear() {
        samples.fill(null)
        start = 0
        size = 0
    }

    /**
     * A live, zero-copy view of the current window as an immutable list. Iteration and
     * indexed access are O(1); the view changes as the ring changes, which is exactly what
     * the classifier wants — it reads the window once per sample and never retains it.
     */
    fun asList(): List<SensorSample> = facade

    companion object {
        const val DEFAULT_CAPACITY = 128
    }
}

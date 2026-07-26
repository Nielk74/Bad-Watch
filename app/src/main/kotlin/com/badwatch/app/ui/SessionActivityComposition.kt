package com.badwatch.app.ui

import kotlin.math.floor

/**
 * A wall-clock composition that never relabels unobserved process time as play or quiet time.
 * Any observed time outside detected exchange windows is deliberately called quiet/undetected.
 */
internal data class SessionActivityComposition(
    val durationMillis: Long,
    val activeMillis: Long,
    val quietMillis: Long,
    val unobservedMillis: Long,
    val activePercent: Int,
    val quietPercent: Int,
    val unobservedPercent: Int
) {
    val activeFraction: Float get() = fraction(activeMillis)
    val quietFraction: Float get() = fraction(quietMillis)
    val unobservedFraction: Float get() = fraction(unobservedMillis)

    private fun fraction(millis: Long): Float =
        if (durationMillis <= 0L) 0f else millis.toFloat() / durationMillis
}

internal fun sessionActivityComposition(
    durationMillis: Long,
    detectedActiveMillis: Long,
    knownUnobservedMillis: Long
): SessionActivityComposition {
    val duration = durationMillis.coerceAtLeast(0L)
    val unobserved = knownUnobservedMillis.coerceIn(0L, duration)
    val active = detectedActiveMillis.coerceIn(0L, duration - unobserved)
    val quiet = duration - active - unobserved
    val percentages = wholePercentages(duration, listOf(active, quiet, unobserved))
    return SessionActivityComposition(
        durationMillis = duration,
        activeMillis = active,
        quietMillis = quiet,
        unobservedMillis = unobserved,
        activePercent = percentages[0],
        quietPercent = percentages[1],
        unobservedPercent = percentages[2]
    )
}

/** Largest-remainder rounding keeps the three spoken percentages at exactly 100. */
private fun wholePercentages(durationMillis: Long, values: List<Long>): List<Int> {
    if (durationMillis <= 0L) return List(values.size) { 0 }
    val exact = values.map { it * 100.0 / durationMillis }
    val rounded = exact.map { floor(it).toInt() }.toMutableList()
    repeat((100 - rounded.sum()).coerceAtLeast(0)) {
        val index = exact.indices.maxByOrNull { candidate ->
            exact[candidate] - rounded[candidate] - candidate * 1e-12
        } ?: return@repeat
        rounded[index] += 1
    }
    return rounded
}

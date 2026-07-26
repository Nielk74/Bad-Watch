package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * A wall-clock interval during which the recording process was absent and observed no sensors.
 *
 * The interval is provenance, not a pause: session duration and heart-rate coverage continue to
 * use the original wall bounds. Rally analysis uses it only to avoid calling unknown time quiet
 * play or connecting detected hits across a period the watch did not observe.
 */
@Serializable
data class ProcessAbsenceGap(
    val startedAtMillis: Long,
    val endedAtMillis: Long
) {
    init {
        require(startedAtMillis >= 0L) { "Process-absence start must not be negative" }
        require(endedAtMillis > startedAtMillis) {
            "Process-absence end must be after its start"
        }
    }

    val durationMillis: Long get() = endedAtMillis - startedAtMillis
}

/** Duration of the union of all gap overlaps with the half-open [startMillis, endMillis) range. */
internal fun Iterable<ProcessAbsenceGap>.overlapDurationMillis(
    startMillis: Long,
    endMillis: Long
): Long {
    if (endMillis <= startMillis) return 0L
    val overlaps = mapNotNull { gap ->
        val start = maxOf(startMillis, gap.startedAtMillis)
        val end = minOf(endMillis, gap.endedAtMillis)
        if (end > start) start to end else null
    }.sortedBy { it.first }
    if (overlaps.isEmpty()) return 0L

    var total = 0L
    var currentStart = overlaps.first().first
    var currentEnd = overlaps.first().second
    overlaps.drop(1).forEach { (start, end) ->
        if (start <= currentEnd) {
            currentEnd = maxOf(currentEnd, end)
        } else {
            total += currentEnd - currentStart
            currentStart = start
            currentEnd = end
        }
    }
    return total + currentEnd - currentStart
}

internal fun Iterable<ProcessAbsenceGap>.overlapsInterval(
    startMillis: Long,
    endMillis: Long
): Boolean = endMillis > startMillis && any { gap ->
    gap.startedAtMillis < endMillis && gap.endedAtMillis > startMillis
}

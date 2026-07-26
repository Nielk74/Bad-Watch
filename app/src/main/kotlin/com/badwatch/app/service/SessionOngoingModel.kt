package com.badwatch.app.service

import kotlin.math.abs

/**
 * Maps the recorder's elapsed duration onto Android's monotonic clock for a Wear stopwatch.
 *
 * [androidx.wear.ongoing.Status.StopwatchPart] deliberately uses elapsed realtime rather than
 * wall time. Clamping keeps a corrupt or restored duration from producing a timestamp before
 * device boot while still allowing a recovered session to include its process-death gap.
 */
internal fun stopwatchStartElapsedRealtime(
    nowElapsedRealtime: Long,
    durationMillis: Long
): Long = (nowElapsedRealtime - durationMillis.coerceAtLeast(0L)).coerceAtLeast(0L)

/** Avoids reposting OngoingActivity metadata for ordinary sub-second clock skew. */
internal fun needsStopwatchRebase(
    currentStartElapsedRealtime: Long,
    candidateStartElapsedRealtime: Long,
    toleranceMillis: Long
): Boolean = abs(currentStartElapsedRealtime - candidateStartElapsedRealtime) > toleranceMillis

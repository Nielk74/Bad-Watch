package com.badwatch.app.complication

import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.effectiveMetrics
import java.util.concurrent.TimeUnit

/** Pure data rendered by the complication service. */
data class WeeklyHitsSnapshot(
    val detectedHits: Int,
    val sessionCount: Int
) {
    val hasSessions: Boolean get() = sessionCount > 0
}

/**
 * Truthful rolling-seven-day aggregation and copy, kept Android-free for JVM tests.
 *
 * Corrected detected events are used when the player marked false hits or trimmed an edge.
 * Reported missed-hit additions are deliberately excluded: they were not detected by the
 * watch. Sessions explicitly reviewed as unusable are also excluded.
 */
object WeeklyHitsComplicationModel {

    val WINDOW_MILLIS: Long = TimeUnit.DAYS.toMillis(7)

    fun summarize(
        sessions: List<SessionExport>,
        nowMillis: Long
    ): WeeklyHitsSnapshot {
        val windowStart = nowMillis - WINDOW_MILLIS
        val eligible = sessions.filter { export ->
            export.context.recordingQuality != RecordingQuality.Unusable &&
                export.session.startedAtMillis in windowStart..nowMillis
        }
        val hitCount = eligible.fold(0L) { total, export ->
            total + export.effectiveMetrics().correctedDetectedHitCount
        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return WeeklyHitsSnapshot(
            detectedHits = hitCount,
            sessionCount = eligible.size
        )
    }

    /** Value alone is shown under the separate `7D HITS` title on compact watch faces. */
    fun shortValue(snapshot: WeeklyHitsSnapshot): String = when {
        !snapshot.hasSessions -> "NO PLAY"
        snapshot.detectedHits < 1_000 -> snapshot.detectedHits.toString()
        snapshot.detectedHits < 10_000 -> {
            val tenths = (snapshot.detectedHits / 100).coerceAtMost(99)
            "${tenths / 10}.${tenths % 10}k"
        }
        else -> "${snapshot.detectedHits / 1_000}k"
    }

    fun longText(snapshot: WeeklyHitsSnapshot): String = if (!snapshot.hasSessions) {
        "No sessions in the last 7 days"
    } else {
        "${plural(snapshot.detectedHits, "detected hit", "detected hits")} · " +
            "${plural(snapshot.sessionCount, "session", "sessions")} · 7 days"
    }

    fun contentDescription(snapshot: WeeklyHitsSnapshot): String = if (!snapshot.hasSessions) {
        "No recorded badminton sessions in the last 7 days"
    } else {
        "${plural(snapshot.detectedHits, "corrected detected hit", "corrected detected hits")} " +
            "across ${plural(snapshot.sessionCount, "session", "sessions")} in the last 7 days"
    }

    private fun plural(count: Int, singular: String, plural: String): String =
        "$count ${if (count == 1) singular else plural}"
}

package com.badwatch.app.tile

import com.badwatch.app.data.StoredSession
import com.badwatch.app.ui.latestUsableSession
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.effectiveMetrics
import com.badwatch.core.sync.knownProcessAbsenceMillisInEffectiveWindow
import com.badwatch.core.sync.reviewedAnalysis
import java.util.concurrent.TimeUnit

/** Trusted subset promoted onto the watch face; unusable evidence remains History-only. */
internal data class TileSessionSelection(
    val latest: StoredSession?,
    val rollingWeek: List<StoredSession>
)

/** Pure presentation input shared by Tile rendering and unit tests. */
internal data class TileSessionSummary(
    val detectedHitCount: Int,
    val exchangeCount: Int,
    val reviewedDurationMillis: Long,
    val knownUnobservedMillis: Long?,
    val rollingWeekSessionCount: Int,
    val rollingWeekDetectedHitCount: Int
)

internal fun TileSessionSelection.summary(): TileSessionSummary? {
    val latestSession = latest ?: return null
    val reviewed = latestSession.export.reviewedAnalysis()
    return TileSessionSummary(
        detectedHitCount = reviewed.metrics.correctedDetectedHitCount,
        exchangeCount = reviewed.rallyProfile.rallyCount,
        reviewedDurationMillis = reviewed.window.durationMillis,
        knownUnobservedMillis = latestSession.export
            .knownProcessAbsenceMillisInEffectiveWindow
            .takeIf { it > 0L },
        rollingWeekSessionCount = rollingWeek.size,
        rollingWeekDetectedHitCount = rollingWeek.sumOf {
            it.export.effectiveMetrics().correctedDetectedHitCount
        }
    )
}

internal fun selectTileSessions(
    sessions: List<StoredSession>,
    nowMillis: Long
): TileSessionSelection {
    val weekStart = nowMillis - TimeUnit.DAYS.toMillis(7)
    return TileSessionSelection(
        latest = latestUsableSession(sessions, nowMillis),
        rollingWeek = sessions.filter { stored ->
            stored.export.context.recordingQuality != RecordingQuality.Unusable &&
                stored.export.session.startedAtMillis in weekStart..nowMillis
        }
    )
}

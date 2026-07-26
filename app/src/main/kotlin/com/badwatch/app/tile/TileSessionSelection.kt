package com.badwatch.app.tile

import com.badwatch.app.data.StoredSession
import com.badwatch.app.ui.latestUsableSession
import com.badwatch.core.sync.RecordingQuality
import java.util.concurrent.TimeUnit

/** Trusted subset promoted onto the watch face; unusable evidence remains History-only. */
internal data class TileSessionSelection(
    val latest: StoredSession?,
    val rollingWeek: List<StoredSession>
)

internal fun selectTileSessions(
    sessions: List<StoredSession>,
    nowMillis: Long
): TileSessionSelection {
    val weekStart = nowMillis - TimeUnit.DAYS.toMillis(7)
    return TileSessionSelection(
        latest = latestUsableSession(sessions),
        rollingWeek = sessions.filter { stored ->
            stored.export.context.recordingQuality != RecordingQuality.Unusable &&
                stored.export.session.startedAtMillis >= weekStart
        }
    )
}

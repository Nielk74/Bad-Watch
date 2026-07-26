package com.badwatch.app.tile

import com.badwatch.app.data.StoredSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Test

class TileSessionSelectionTest {

    @Test
    fun headlineAndRollingWeekSkipUnusableRecording() {
        val now = 2_000_000_000_000L
        val newestUnusable = stored(now - 1_000L, RecordingQuality.Unusable)
        val newestUsable = stored(now - 2_000L, RecordingQuality.Partial)
        val olderUsable = stored(now - 3_000L, RecordingQuality.Unreviewed)

        val selection = selectTileSessions(
            listOf(newestUnusable, newestUsable, olderUsable),
            nowMillis = now
        )

        assertThat(selection.latest).isSameInstanceAs(newestUsable)
        assertThat(selection.rollingWeek).containsExactly(newestUsable, olderUsable).inOrder()
    }

    @Test
    fun onlyUnusableHistoryProducesNoLatestHeadline() {
        val now = 2_000_000_000_000L
        val unusable = stored(now - 1_000L, RecordingQuality.Unusable)

        val selection = selectTileSessions(listOf(unusable), now)

        assertThat(selection.latest).isNull()
        assertThat(selection.rollingWeek).isEmpty()
    }

    @Test
    fun futureUsableSessionCannotReplaceValidPastHeadline() {
        val now = 2_000_000_000_000L
        val future = stored(now + 1L, RecordingQuality.Unreviewed)
        val validPast = stored(now - 1L, RecordingQuality.Partial)

        val selection = selectTileSessions(listOf(future, validPast), now)

        assertThat(selection.latest).isSameInstanceAs(validPast)
    }

    @Test
    fun futureOnlyHistoryProducesNoLatestHeadline() {
        val now = 2_000_000_000_000L
        val future = stored(now + 1L, RecordingQuality.Unreviewed)

        val selection = selectTileSessions(listOf(future), now)

        assertThat(selection.latest).isNull()
    }

    @Test
    fun rollingWeekIncludesBoundariesButExcludesFutureAndExpiredSessions() {
        val now = 2_000_000_000_000L
        val start = now - TimeUnit.DAYS.toMillis(7)
        val atStart = stored(start, RecordingQuality.Unreviewed)
        val expired = stored(start - 1L, RecordingQuality.Unreviewed)
        val atNow = stored(now, RecordingQuality.Partial)
        val future = stored(now + 1L, RecordingQuality.Unreviewed)

        val selection = selectTileSessions(
            listOf(atStart, expired, atNow, future),
            nowMillis = now
        )

        assertThat(selection.rollingWeek).containsExactly(atStart, atNow).inOrder()
    }

    private fun stored(startedAtMillis: Long, quality: RecordingQuality): StoredSession {
        val id = "session-$startedAtMillis-${quality.name}"
        return StoredSession(
            file = File("$id.json"),
            export = SessionExport(
                deviceId = "watch",
                appVersion = "test",
                profile = PlayerProfile(),
                session = TrainingSession(
                    id = id,
                    startedAtMillis = startedAtMillis,
                    endedAtMillis = startedAtMillis + 60_000L,
                    summary = TrainingSummary(
                        totalShots = 0,
                        shotCounts = emptyMap(),
                        durationMillis = 60_000L,
                        averageHeartRate = null,
                        maxHeartRate = null,
                        recoveryScore = 0f,
                        fatigueScore = 0f,
                        effortScore = 0f,
                        heartRateZoneHistogram = emptyMap()
                    ),
                    shots = emptyList()
                ),
                rallyProfile = RallyProfile.EMPTY,
                context = SessionContext(recordingQuality = quality)
            ),
            synced = false
        )
    }
}

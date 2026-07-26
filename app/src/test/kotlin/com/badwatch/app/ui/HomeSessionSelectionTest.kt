package com.badwatch.app.ui

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

class HomeSessionSelectionTest {

    @Test
    fun latestUsableSessionSkipsNewerUnusableRecordings() {
        val newestUnusable = stored(startedAtMillis = 3_000L, RecordingQuality.Unusable)
        val newestUsable = stored(startedAtMillis = 2_000L, RecordingQuality.Partial)
        val olderUsable = stored(startedAtMillis = 1_000L, RecordingQuality.Unreviewed)

        assertThat(
            latestUsableSession(
                listOf(newestUnusable, newestUsable, olderUsable),
                nowMillis = 3_000L
            )
        ).isSameInstanceAs(newestUsable)
    }

    @Test
    fun latestUsableSessionIsEmptyWhenOnlyUnusableRecordingsExist() {
        assertThat(
            latestUsableSession(
                listOf(stored(1_000L, RecordingQuality.Unusable)),
                nowMillis = 1_000L
            )
        ).isNull()
    }

    @Test
    fun latestUsableSessionSkipsFutureDatedRecordings() {
        val future = stored(startedAtMillis = 3_001L, RecordingQuality.Unreviewed)
        val validPast = stored(startedAtMillis = 2_000L, RecordingQuality.Partial)

        assertThat(
            latestUsableSession(listOf(future, validPast), nowMillis = 3_000L)
        ).isSameInstanceAs(validPast)
    }

    @Test
    fun rollingWeekIncludesBoundariesButExcludesFuturePastAndUnusableSessions() {
        val now = 2_000_000_000_000L
        val start = now - TimeUnit.DAYS.toMillis(7)
        val atStart = stored(start, RecordingQuality.Unreviewed)
        val beforeStart = stored(start - 1L, RecordingQuality.Unreviewed)
        val atNow = stored(now, RecordingQuality.Partial)
        val future = stored(now + 1L, RecordingQuality.Unreviewed)
        val unusable = stored(now - 1_000L, RecordingQuality.Unusable)

        val selected = selectHomeRollingWeek(
            listOf(atStart, beforeStart, atNow, future, unusable),
            nowMillis = now
        )

        assertThat(selected).containsExactly(atStart, atNow).inOrder()
    }

    private fun stored(startedAtMillis: Long, quality: RecordingQuality): StoredSession {
        val sessionId = "session-$startedAtMillis"
        val export = SessionExport(
            deviceId = "watch",
            appVersion = "test",
            profile = PlayerProfile(),
            session = TrainingSession(
                id = sessionId,
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
        )
        return StoredSession(
            file = File("$sessionId.json"),
            export = export,
            synced = false
        )
    }
}

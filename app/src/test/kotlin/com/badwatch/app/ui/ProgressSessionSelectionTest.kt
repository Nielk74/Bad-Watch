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

class ProgressSessionSelectionTest {

    @Test
    fun usableHistoryExcludesFutureAndUnusableRecordsButKeepsValidPastData() {
        val now = 2_000_000_000_000L
        val validPast = stored(now - 1L, RecordingQuality.Partial)
        val future = stored(now + 1L, RecordingQuality.Unreviewed)
        val unusable = stored(now - 2L, RecordingQuality.Unusable)

        val selected = selectProgressUsableHistory(
            listOf(future, validPast, unusable),
            nowMillis = now
        )

        assertThat(selected).containsExactly(validPast)
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

        val selected = selectProgressRollingWeek(
            listOf(atStart, beforeStart, atNow, future, unusable),
            nowMillis = now
        )

        assertThat(selected).containsExactly(atStart, atNow).inOrder()
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

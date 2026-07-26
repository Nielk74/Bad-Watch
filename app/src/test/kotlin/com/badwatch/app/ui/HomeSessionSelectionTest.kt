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
import org.junit.Test

class HomeSessionSelectionTest {

    @Test
    fun latestUsableSessionSkipsNewerUnusableRecordings() {
        val newestUnusable = stored(startedAtMillis = 3_000L, RecordingQuality.Unusable)
        val newestUsable = stored(startedAtMillis = 2_000L, RecordingQuality.Partial)
        val olderUsable = stored(startedAtMillis = 1_000L, RecordingQuality.Unreviewed)

        assertThat(
            latestUsableSession(listOf(newestUnusable, newestUsable, olderUsable))
        ).isSameInstanceAs(newestUsable)
    }

    @Test
    fun latestUsableSessionIsEmptyWhenOnlyUnusableRecordingsExist() {
        assertThat(
            latestUsableSession(listOf(stored(1_000L, RecordingQuality.Unusable)))
        ).isNull()
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

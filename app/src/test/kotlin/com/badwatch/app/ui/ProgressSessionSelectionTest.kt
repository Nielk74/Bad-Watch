package com.badwatch.app.ui

import com.badwatch.app.data.StoredSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
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

    @Test
    fun completeRecoveredSessionCountsAsASessionButOnlyObservedTimeSetsGoalsAndRecords() {
        val now = 2_000_000_000_000L
        val recoveredStart = now - 120_000L
        val recovered = stored(
            startedAtMillis = recoveredStart,
            quality = RecordingQuality.Complete,
            durationMillis = 120_000L,
            gaps = listOf(
                ProcessAbsenceGap(recoveredStart + 30_000L, recoveredStart + 60_000L),
                ProcessAbsenceGap(recoveredStart + 50_000L, recoveredStart + 80_000L)
            )
        )
        val clean = stored(
            startedAtMillis = now - 220_000L,
            quality = RecordingQuality.Complete,
            durationMillis = 100_000L
        )

        val recent = selectProgressRollingWeek(listOf(recovered, clean), now)

        assertThat(recent).hasSize(2)
        // Overlapping recovery gaps are a 50-second union, not a 60-second sum.
        assertThat(recovered.export.observedEffectiveDurationMillis).isEqualTo(70_000L)
        assertThat(progressObservedMillis(recent)).isEqualTo(170_000L)
        assertThat(progressObservedMillis(recent) / 60_000L).isEqualTo(2L)
        assertThat(progressLongestObservedMillis(recent)).isEqualTo(100_000L)
    }

    @Test
    fun gapFreeProgressDurationRemainsTheReviewedDuration() {
        val clean = stored(
            startedAtMillis = 1_000L,
            quality = RecordingQuality.Unreviewed,
            durationMillis = 90_000L
        )

        assertThat(clean.export.observedEffectiveDurationMillis).isEqualTo(90_000L)
        assertThat(progressObservedMillis(listOf(clean))).isEqualTo(90_000L)
        assertThat(progressLongestObservedMillis(listOf(clean))).isEqualTo(90_000L)
    }

    private fun stored(
        startedAtMillis: Long,
        quality: RecordingQuality,
        durationMillis: Long = 60_000L,
        gaps: List<ProcessAbsenceGap> = emptyList()
    ): StoredSession {
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
                    endedAtMillis = startedAtMillis + durationMillis,
                    summary = TrainingSummary(
                        totalShots = 0,
                        shotCounts = emptyMap(),
                        durationMillis = durationMillis,
                        averageHeartRate = null,
                        maxHeartRate = null,
                        recoveryScore = 0f,
                        fatigueScore = 0f,
                        effortScore = 0f,
                        heartRateZoneHistogram = emptyMap()
                    ),
                    shots = emptyList(),
                    processAbsenceGaps = gaps
                ),
                rallyProfile = RallyProfile.EMPTY,
                context = SessionContext(recordingQuality = quality)
            ),
            synced = false
        )
    }
}

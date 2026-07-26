package com.badwatch.app.tile

import com.badwatch.app.data.StoredSession
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
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

    @Test
    fun completeRecoveredHeadlineCarriesExactGapAndKeepsWeeklyDetectedHits() {
        val now = 2_000_000_000_000L
        val latestStart = now - 60_000L
        val latest = stored(
            startedAtMillis = latestStart,
            quality = RecordingQuality.Complete,
            hitCount = 2,
            gaps = listOf(ProcessAbsenceGap(latestStart + 10_000L, latestStart + 25_000L))
        )
        val older = stored(
            startedAtMillis = now - 120_000L,
            quality = RecordingQuality.Unreviewed,
            hitCount = 3
        )

        val summary = selectTileSessions(listOf(latest, older), now).summary()

        assertThat(summary).isNotNull()
        assertThat(summary!!.detectedHitCount).isEqualTo(2)
        assertThat(summary.knownUnobservedMillis).isEqualTo(15_000L)
        assertThat(summary.rollingWeekSessionCount).isEqualTo(2)
        assertThat(summary.rollingWeekDetectedHitCount).isEqualTo(5)
    }

    @Test
    fun gapFreeTileHeadlineHasNoUnobservedMarkerInput() {
        val now = 2_000_000_000_000L
        val summary = selectTileSessions(
            listOf(stored(now - 60_000L, RecordingQuality.Complete, hitCount = 1)),
            now
        ).summary()

        assertThat(summary!!.knownUnobservedMillis).isNull()
    }

    private fun stored(
        startedAtMillis: Long,
        quality: RecordingQuality,
        hitCount: Int = 0,
        gaps: List<ProcessAbsenceGap> = emptyList()
    ): StoredSession {
        val id = "session-$startedAtMillis-${quality.name}"
        val shots = List(hitCount) { index ->
            ShotEvent(
                id = "$id-shot-$index",
                type = ShotType.Unknown,
                timestampMillis = startedAtMillis + 1_000L + index * 1_000L,
                confidence = 0.9f,
                peakAngularVelocity = 10f,
                heartRateBpm = null,
                swingDurationMillis = 100L
            )
        }
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
                        totalShots = hitCount,
                        shotCounts = if (hitCount == 0) emptyMap() else mapOf(ShotType.Unknown to hitCount),
                        durationMillis = 60_000L,
                        averageHeartRate = null,
                        maxHeartRate = null,
                        recoveryScore = 0f,
                        fatigueScore = 0f,
                        effortScore = 0f,
                        heartRateZoneHistogram = emptyMap()
                    ),
                    shots = shots,
                    processAbsenceGaps = gaps
                ),
                rallyProfile = RallyProfile.EMPTY,
                context = SessionContext(recordingQuality = quality)
            ),
            synced = false
        )
    }
}

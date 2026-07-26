package com.badwatch.app.complication

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WeeklyHitsComplicationModelTest {

    @Test
    fun rollingWindowIncludesBothBoundariesButNotFutureOrUnusableSessions() {
        val now = 2_000_000_000_000L
        val start = now - WeeklyHitsComplicationModel.WINDOW_MILLIS
        val sessions = listOf(
            export(startedAt = start, hits = 2),
            export(startedAt = start - 1L, hits = 20),
            export(startedAt = now, hits = 3),
            export(startedAt = now + 1L, hits = 30),
            export(startedAt = now - 1_000L, hits = 40, quality = RecordingQuality.Unusable)
        )

        val summary = WeeklyHitsComplicationModel.summarize(sessions, now)

        assertThat(summary.sessionCount).isEqualTo(2)
        assertThat(summary.detectedHits).isEqualTo(5)
    }

    @Test
    fun falseHitsAreRemovedButReportedMissesAreNotCalledDetected() {
        val now = 2_000_000_000_000L
        val raw = export(startedAt = now - 1_000L, hits = 3)
        val reviewed = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = listOf(raw.session.shots.first().id),
                        missedHitCount = 9,
                        provenance = CorrectionProvenance(
                            revisionId = "review-1",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = now
                        )
                    )
                )
            )
        )

        val summary = WeeklyHitsComplicationModel.summarize(listOf(reviewed), now)

        assertThat(summary.detectedHits).isEqualTo(2)
        assertThat(summary.sessionCount).isEqualTo(1)
    }

    @Test
    fun compactAndAccessibleCopyKeepEmptyAndZeroDistinct() {
        val empty = WeeklyHitsSnapshot(detectedHits = 0, sessionCount = 0)
        val zero = WeeklyHitsSnapshot(detectedHits = 0, sessionCount = 1)

        assertThat(WeeklyHitsComplicationModel.shortValue(empty)).isEqualTo("NO PLAY")
        assertThat(WeeklyHitsComplicationModel.shortValue(zero)).isEqualTo("0")
        assertThat(WeeklyHitsComplicationModel.longText(empty))
            .isEqualTo("No sessions in the last 7 days")
        assertThat(WeeklyHitsComplicationModel.contentDescription(empty))
            .contains("No recorded badminton sessions")
    }

    @Test
    fun compactCountsStayReadableInShortTextSlots() {
        fun compact(hits: Int) = WeeklyHitsComplicationModel.shortValue(
            WeeklyHitsSnapshot(detectedHits = hits, sessionCount = 1)
        )

        assertThat(compact(999)).isEqualTo("999")
        assertThat(compact(1_200)).isEqualTo("1.2k")
        assertThat(compact(9_999)).isEqualTo("9.9k")
        assertThat(compact(10_000)).isEqualTo("10k")
    }

    private fun export(
        startedAt: Long,
        hits: Int,
        quality: RecordingQuality = RecordingQuality.Unreviewed
    ): SessionExport {
        val events = (0 until hits).map { index ->
            ShotEvent(
                id = "$startedAt-hit-$index",
                type = ShotType.Unknown,
                timestampMillis = startedAt + index,
                confidence = 0.5f,
                peakAngularVelocity = 4f,
                heartRateBpm = null,
                swingDurationMillis = 100L
            )
        }
        return SessionExport(
            deviceId = "watch",
            appVersion = "test",
            profile = PlayerProfile(),
            session = TrainingSession(
                id = "session-$startedAt-$hits-${quality.name}",
                startedAtMillis = startedAt,
                endedAtMillis = startedAt + 60_000L,
                summary = TrainingSummary(
                    totalShots = hits,
                    shotCounts = mapOf(ShotType.Unknown to hits),
                    durationMillis = 60_000L,
                    averageHeartRate = null,
                    maxHeartRate = null,
                    recoveryScore = 0f,
                    fatigueScore = 0f,
                    effortScore = 0f,
                    heartRateZoneHistogram = emptyMap()
                ),
                shots = events
            ),
            rallyProfile = RallyProfile.EMPTY,
            context = SessionContext(recordingQuality = quality)
        )
    }
}

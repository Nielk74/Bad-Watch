package com.badwatch.core.sync

import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.session.RallySegmenter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewedSessionAnalysisTest {

    @Test
    fun trimsThenRemovesResolvedFalseHitsBeforeRebuildingDetectedExchanges() {
        val raw = export(
            start = 0L,
            end = 30_000L,
            shotTimes = listOf(1_000L, 2_000L, 8_000L, 9_000L, 10_000L, 20_000L, 21_000L)
        )
        val reviewed = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = listOf("hit-3", "missing", "hit-0"),
                        missedHitCount = 5,
                        provenance = provenance("hits")
                    )
                ),
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 5_000L,
                        trimFromEndMillis = 8_000L,
                        provenance = provenance("trim")
                    )
                )
            )
        )

        val analysis = reviewed.reviewedAnalysis()

        assertThat(analysis.window.startedAtMillis).isEqualTo(5_000L)
        assertThat(analysis.window.endedAtMillis).isEqualTo(22_000L)
        assertThat(analysis.window.durationMillis).isEqualTo(17_000L)
        assertThat(analysis.detectedHits.map { it.id })
            .containsExactly("hit-2", "hit-4", "hit-5", "hit-6").inOrder()
        assertThat(analysis.metrics.falseHitCount).isEqualTo(1)
        assertThat(analysis.metrics.unknownFalseHitIds).containsExactly("missing")
        assertThat(analysis.metrics.correctedDetectedHitCount).isEqualTo(4)
        assertThat(analysis.metrics.reportedMissedHitCount).isEqualTo(5)
        assertThat(analysis.metrics.effectiveHitCount).isEqualTo(9)

        assertThat(analysis.session.summary.totalShots).isEqualTo(4)
        assertThat(analysis.session.summary.durationMillis).isEqualTo(17_000L)
        assertThat(analysis.rallyProfile.rallyCount).isEqualTo(2)
        assertThat(analysis.rallyProfile.rallies.map { it.shotCount })
            .containsExactly(2, 2).inOrder()
        assertThat(analysis.rallyProfile.totalWorkMillis).isEqualTo(3_000L)
        assertThat(analysis.rallyProfile.totalRestMillis).isEqualTo(11_000L)

        // The derived view never replaces raw detector events or the original model output.
        assertThat(reviewed.session).isEqualTo(raw.session)
        assertThat(reviewed.rallyProfile).isEqualTo(raw.rallyProfile)
        assertThat(reviewed.session.summary.totalShots).isEqualTo(7)
        assertThat(reviewed.rallyProfile.rallyCount).isEqualTo(3)
    }

    @Test
    fun reportedMissesChangeOnlyTheEffectiveTotalBecauseTheyHaveNoTimestamps() {
        val raw = export(
            start = 0L,
            end = 20_000L,
            shotTimes = listOf(1_000L, 2_000L, 10_000L, 11_000L)
        )
        val reviewed = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        missedHitCount = 12,
                        provenance = provenance("missed")
                    )
                )
            )
        ).reviewedAnalysis()

        assertThat(reviewed.metrics.correctedDetectedHitCount).isEqualTo(4)
        assertThat(reviewed.metrics.effectiveHitCount).isEqualTo(16)
        assertThat(reviewed.detectedHits).hasSize(4)
        assertThat(reviewed.session.summary.totalShots).isEqualTo(4)
        assertThat(reviewed.rallyProfile).isEqualTo(raw.rallyProfile)
    }

    @Test
    fun reviewedRalliesKeepProcessAbsenceAsABoundaryWithoutCallingItQuietTime() {
        val gap = ProcessAbsenceGap(startedAtMillis = 2_500L, endedAtMillis = 8_500L)
        val raw = export(
            start = 0L,
            end = 12_000L,
            shotTimes = listOf(1_000L, 2_000L, 9_000L, 10_000L),
            processAbsenceGaps = listOf(gap)
        )

        val analysis = raw.reviewedAnalysis()

        assertThat(analysis.session.processAbsenceGaps).containsExactly(gap)
        assertThat(analysis.session.summary.durationMillis).isEqualTo(12_000L)
        assertThat(analysis.rallyProfile.rallyCount).isEqualTo(2)
        // 7 s between exchanges includes 6 s of unknown process absence, leaving 1 s quiet.
        // The final 2 s to the session end is observed quiet time.
        assertThat(analysis.rallyProfile.totalRestMillis).isEqualTo(3_000L)
        assertThat(analysis.rallyProfile).isEqualTo(raw.rallyProfile)
    }

    @Test
    fun baselineUsesOnlyPriorCompleteOrUnreviewedSessionsWithTheSameEligibleContext() {
        val current = exchangeExport(1_000_000L).withContext(ActivityMode.SinglesMatch)
        val eligible = listOf(
            exchangeExport(100_000L).withContext(ActivityMode.SinglesMatch),
            exchangeExport(200_000L).withContext(
                ActivityMode.SinglesMatch,
                quality = RecordingQuality.Partial
            ),
            exchangeExport(300_000L).withContext(
                ActivityMode.SinglesMatch,
                quality = RecordingQuality.Unreviewed
            )
        )
        val excluded = listOf(
            exchangeExport(400_000L).withContext(ActivityMode.DoublesMatch),
            exchangeExport(500_000L).withContext(
                ActivityMode.SinglesMatch,
                quality = RecordingQuality.Unusable
            ),
            exchangeExport(1_000_000L).withContext(ActivityMode.SinglesMatch),
            exchangeExport(1_100_000L).withContext(ActivityMode.SinglesMatch),
            exchangeExport(600_000L).withContext(ActivityMode.Drill, tag = "rear court")
        )

        val baseline = current.reviewedInsightBaseline(eligible + excluded + current)

        assertThat(baseline.sessionCount).isEqualTo(2)
        assertThat(baseline.hasEnoughHistory).isFalse()
    }

    @Test
    fun removingTimestampedFalseHitsCanSilenceAnInsightButReportedMissesCannotRestoreIt() {
        val raw = exchangeExport(start = 0L)
        val rawAnalysis = raw.reviewedAnalysis()
        assertThat(
            SessionInsightEngine().generate(rawAnalysis.session, rawAnalysis.rallyProfile)
                .map { it.id }
        ).contains("rest-ratio-high")

        val oneHitFromEveryExchange = raw.rallyProfile.rallies.map { rally ->
            raw.session.shots.first { it.timestampMillis == rally.endMillis }.id
        }
        val corrected = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = oneHitFromEveryExchange,
                        missedHitCount = 100,
                        provenance = provenance("remove-one-per-exchange")
                    )
                )
            )
        ).reviewedAnalysis()

        assertThat(corrected.metrics.effectiveHitCount).isEqualTo(105)
        assertThat(corrected.rallyProfile.rallies).isEmpty()
        assertThat(
            SessionInsightEngine().generate(corrected.session, corrected.rallyProfile)
        ).isEmpty()
    }

    @Test
    @Suppress("DEPRECATION")
    fun reviewedViewNeutralizesStoredLegacyScoresWithoutMutatingRawEvidence() {
        val base = export(
            start = 0L,
            end = 20_000L,
            shotTimes = listOf(1_000L, 2_000L, 10_000L, 11_000L)
        )
        val storedLegacy = base.copy(
            session = base.session.copy(
                summary = base.session.summary.copy(
                    recoveryScore = 0.6f,
                    fatigueScore = 0.7f,
                    effortScore = 0.8f
                )
            )
        )

        val reviewed = storedLegacy.reviewedAnalysis()

        assertThat(reviewed.session.summary.recoveryScore).isEqualTo(0f)
        assertThat(reviewed.session.summary.fatigueScore).isEqualTo(0f)
        assertThat(reviewed.session.summary.effortScore).isEqualTo(0f)
        assertThat(storedLegacy.session.summary.recoveryScore).isEqualTo(0.6f)
        assertThat(storedLegacy.session.summary.fatigueScore).isEqualTo(0.7f)
        assertThat(storedLegacy.session.summary.effortScore).isEqualTo(0.8f)
    }

    private fun exchangeExport(start: Long): SessionExport {
        val shotTimes = buildList {
            repeat(5) { exchange ->
                val exchangeStart = start + 1_000L + exchange * 11_000L
                add(exchangeStart)
                add(exchangeStart + 1_000L)
            }
        }
        return export(
            start = start,
            end = start + 60_000L,
            shotTimes = shotTimes
        )
    }

    private fun SessionExport.withContext(
        mode: ActivityMode,
        tag: String? = null,
        quality: RecordingQuality = RecordingQuality.Complete
    ): SessionExport = copy(
        context = SessionContext(
            activityMode = mode,
            comparisonTag = tag,
            recordingQuality = quality
        )
    )

    private fun export(
        start: Long,
        end: Long,
        shotTimes: List<Long>,
        processAbsenceGaps: List<ProcessAbsenceGap> = emptyList()
    ): SessionExport {
        val shots = shotTimes.mapIndexed { index, timestamp ->
            ShotEvent(
                id = "hit-$index",
                type = if (index % 2 == 0) ShotType.Clear else ShotType.Drive,
                timestampMillis = timestamp,
                confidence = 0.5f,
                peakAngularVelocity = 5f,
                heartRateBpm = null,
                swingDurationMillis = 180L
            )
        }
        val session = TrainingSession(
            id = "session-$start",
            startedAtMillis = start,
            endedAtMillis = end,
            summary = TrainingSummary(
                totalShots = shots.size,
                shotCounts = shots.groupingBy { it.type }.eachCount(),
                durationMillis = end - start,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap<HeartRateZone, Int>()
            ),
            shots = shots,
            processAbsenceGaps = processAbsenceGaps
        )
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = session,
            rallyProfile = RallySegmenter().segment(
                shots = shots,
                sessionEndMillis = end,
                processAbsenceGaps = processAbsenceGaps
            )
        )
    }

    private fun provenance(id: String) = CorrectionProvenance(
        revisionId = id,
        actor = CorrectionActor.Player,
        recordedAtMillis = 100L
    )
}

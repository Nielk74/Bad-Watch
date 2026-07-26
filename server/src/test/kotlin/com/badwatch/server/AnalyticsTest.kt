package com.badwatch.server

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.session.RallySegmenter
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.ReportedSoreness
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.TrimCorrectionRevision
import com.badwatch.core.sync.reviewedAnalysis
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnalyticsTest {

    @Test
    fun volumeTrendKeepsElapsedActiveAndHitUnitsSeparate() {
        val day = 24L * 60 * 60 * 1_000
        val sessions = listOf(
            session(
                startedAtMillis = day / 2,
                elapsedMillis = 60 * 60 * 1_000L,
                estimatedActiveMillis = 40 * 60 * 1_000L,
                detectedHits = 100,
                cardiovascularLoad = 24f,
                heartRateSampleCount = 1_800,
                heartRateCoverage = 0.8f
            ),
            session(
                startedAtMillis = 34 * day + day / 2,
                elapsedMillis = 90 * 60 * 1_000L,
                estimatedActiveMillis = 70 * 60 * 1_000L,
                detectedHits = 70,
                // A value without proof of optical readings must not reach the dashboard.
                cardiovascularLoad = 99f,
                heartRateSampleCount = 0,
                heartRateCoverage = 0f
            )
        )

        val dashboard = Analytics.build(sessions)
        val earlyAnalysis = sessions.first().reviewedAnalysis()
        val latestAnalysis = sessions.last().reviewedAnalysis()

        assertThat(dashboard.volumeTrend).hasSize(35)
        val latest = dashboard.volumeTrend.last()
        assertThat(latest.dailyElapsedMillis).isEqualTo(90 * 60 * 1_000L)
        assertThat(latest.dailyEstimatedActiveMillis)
            .isEqualTo(latestAnalysis.rallyProfile.totalWorkMillis)
        assertThat(latest.dailyDetectedHits)
            .isEqualTo(latestAnalysis.metrics.correctedDetectedHitCount)
        assertThat(latest.rolling7DayEstimatedActiveMillis)
            .isEqualTo(latestAnalysis.rallyProfile.totalWorkMillis)
        assertThat(latest.rolling7DayDetectedHits)
            .isEqualTo(latestAnalysis.metrics.correctedDetectedHitCount)
        // The preceding four complete weeks contain the first session's reviewed activity.
        assertThat(latest.previous28DayWeeklyAverageEstimatedActiveMillis)
            .isEqualTo(earlyAnalysis.rallyProfile.totalWorkMillis / 4L)

        assertThat(dashboard.sessions.first().cardiovascularLoad).isNull()
        assertThat(dashboard.sessions.last().cardiovascularLoad).isEqualTo(24f)
        assertThat(dashboard.sessions.last().heartRateCoverage).isEqualTo(0.8f)
    }

    @Test
    fun cardsPreserveMeasuredBpmButWithholdLegacyUnconfiguredHeartRateLoad() {
        val legacy = session(
            startedAtMillis = 1_000L,
            elapsedMillis = 10 * 60_000L,
            estimatedActiveMillis = 4 * 60_000L,
            detectedHits = 30,
            cardiovascularLoad = 8f,
            heartRateSampleCount = 500,
            heartRateCoverage = 0.8f
        ).copy(profile = PlayerProfile())

        val card = Analytics.build(listOf(legacy)).sessions.single()

        assertThat(card.averageHeartRate).isEqualTo(legacy.session.summary.averageHeartRate)
        assertThat(card.maxHeartRate).isEqualTo(legacy.session.summary.maxHeartRate)
        assertThat(card.cardiovascularLoad).isNull()
    }

    @Test
    @Suppress("DEPRECATION")
    fun syntheticSessionsKeepUndefinedLegacyScoresNeutral() {
        val summary = SyntheticSessions.session(
            startedAtMillis = 1_000L,
            rallies = 4,
            shotsPerRally = 5
        ).session.summary

        assertThat(summary.recoveryScore).isEqualTo(0f)
        assertThat(summary.fatigueScore).isEqualTo(0f)
        assertThat(summary.effortScore).isEqualTo(0f)
    }

    @Test
    fun priorWeeklyComparisonStaysAbsentUntilFiveWeeksExist() {
        val oneDay = 24L * 60 * 60 * 1_000
        val dashboard = Analytics.build(
            listOf(
                session(0L, oneDay, 20 * 60_000L, 50),
                session(33 * oneDay, oneDay, 30 * 60_000L, 60)
            )
        )

        assertThat(dashboard.volumeTrend.last().previous28DayWeeklyAverageEstimatedActiveMillis)
            .isNull()
    }

    @Test
    fun cardsExposeReportedDiaryAndEffectiveCorrectionMetrics() {
        val base = SyntheticSessions.session(
            startedAtMillis = 1_000L,
            rallies = 3,
            shotsPerRally = 4
        )
        val falseHitId = base.session.shots.first().id
        val corrected = base.copy(
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                opponent = "Alex",
                hall = "Local club",
                goal = "Patient rear court",
                completion = SessionCompletion.Completed,
                recordingQuality = RecordingQuality.Complete
            ),
            report = PostSessionReport(
                rpe = 8,
                soreness = listOf(ReportedSoreness(BodyArea.Forearm, severity = 2)),
                notes = "Good length in game two"
            ),
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = listOf(falseHitId),
                        missedHitCount = 3,
                        provenance = CorrectionProvenance(
                            revisionId = "review-1",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = base.session.endedAtMillis + 1_000L
                        )
                    )
                )
            )
        )

        val card = Analytics.build(listOf(corrected)).sessions.single()

        assertThat(card.context).isEqualTo(corrected.context)
        assertThat(card.report).isEqualTo(corrected.report)
        assertThat(card.comparisonKey.activityMode).isEqualTo(ActivityMode.SinglesMatch)
        assertThat(card.correctionRevisionCount).isEqualTo(1)
        assertThat(card.effectiveMetrics?.rawDetectedHitCount)
            .isEqualTo(base.session.shots.size)
        assertThat(card.effectiveMetrics?.falseHitCount).isEqualTo(1)
        assertThat(card.effectiveMetrics?.reportedMissedHitCount).isEqualTo(3)
        assertThat(card.effectiveMetrics?.effectiveHitCount)
            .isEqualTo(base.session.shots.size + 2)
        // Dashboard hit fields remain detected-only; reported misses live only in the explicit
        // effective total because they have no timestamp or provisional type.
        assertThat(card.totalShots).isEqualTo(base.session.shots.size - 1)
    }

    @Test
    fun reviewedCorrectionsDriveCardsAggregatesAndInsightsWithoutReplacingRawEvidence() {
        val raw = reviewableSession()
        val rawDashboard = Analytics.build(listOf(raw))
        assertThat(rawDashboard.sessions.single().insights.map { it.id })
            .contains("rest-ratio-high")

        val oneHitFromEveryExchange = raw.rallyProfile.rallies.map { rally ->
            raw.session.shots.first { it.timestampMillis == rally.endMillis }.id
        }
        val reviewed = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = oneHitFromEveryExchange + "unknown-id",
                        missedHitCount = 7,
                        provenance = CorrectionProvenance(
                            revisionId = "hits-reviewed",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = raw.session.endedAtMillis + 1L
                        )
                    )
                ),
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 500L,
                        trimFromEndMillis = 5_000L,
                        provenance = CorrectionProvenance(
                            revisionId = "edges-reviewed",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = raw.session.endedAtMillis + 2L
                        )
                    )
                )
            )
        )

        val dashboard = Analytics.build(listOf(reviewed))
        val card = dashboard.sessions.single()

        assertThat(card.durationMillis).isEqualTo(54_500L)
        assertThat(card.totalShots).isEqualTo(5)
        assertThat(card.rallyCount).isEqualTo(0)
        assertThat(card.estimatedActiveMillis).isEqualTo(0L)
        assertThat(card.insights).isEmpty()
        assertThat(card.effectiveMetrics?.effectiveHitCount).isEqualTo(12)
        assertThat(card.effectiveMetrics?.unknownFalseHitIds).containsExactly("unknown-id")
        assertThat(dashboard.totalShots).isEqualTo(5)
        assertThat(dashboard.totalElapsedMillis).isEqualTo(54_500L)
        assertThat(dashboard.totalPlayingMillis).isEqualTo(0L)
        assertThat(dashboard.shotDistribution.sumOf { it.count }).isEqualTo(5)

        val detail = Analytics.detail(reviewed, listOf(reviewed))
        assertThat(detail.raw).isEqualTo(reviewed)
        assertThat(detail.reviewed.session.summary.totalShots).isEqualTo(5)
        assertThat(detail.reviewed.session.summary.durationMillis).isEqualTo(54_500L)
        assertThat(detail.reviewed.rallyProfile.rallyCount).isEqualTo(0)
        assertThat(detail.reviewed.effectiveMetrics.effectiveHitCount).isEqualTo(12)
        assertThat(detail.reviewed.insights).isEmpty()

        // The detail/export contract still exposes the immutable raw evidence.
        assertThat(reviewed.session).isEqualTo(raw.session)
        assertThat(reviewed.rallyProfile).isEqualTo(raw.rallyProfile)
        assertThat(reviewed.session.summary.totalShots).isEqualTo(10)
        assertThat(reviewed.rallyProfile.rallyCount).isEqualTo(5)
    }

    @Test
    fun partialAndUnusableRecordingsNeverGenerateGapSensitiveInsights() {
        val seed = reviewableSession()
        val complete = seed.copy(
            context = seed.context.copy(
                recordingQuality = RecordingQuality.Complete
            )
        )
        assertThat(Analytics.detail(complete, listOf(complete)).reviewed.insights).isNotEmpty()

        listOf(RecordingQuality.Partial, RecordingQuality.Unusable).forEach { quality ->
            val recording = complete.copy(
                context = complete.context.copy(recordingQuality = quality)
            )
            val filter = if (quality == RecordingQuality.Unusable) {
                SessionAnalyticsFilter(recordingQualities = setOf(RecordingQuality.Unusable))
            } else {
                SessionAnalyticsFilter()
            }

            val dashboard = Analytics.build(listOf(recording), filter)

            assertThat(dashboard.sessions.single().insights).isEmpty()
            assertThat(Analytics.detail(recording, listOf(recording)).reviewed.insights).isEmpty()
        }
    }

    @Test
    fun completeGapRecordingStaysInAggregatesButCannotTeachServerInferences() {
        val complete = reviewableSession().copy(
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                recordingQuality = RecordingQuality.Complete
            )
        )
        val gap = ProcessAbsenceGap(59_000L, 60_000L)
        val gapBearing = complete.copy(
            session = complete.session.copy(processAbsenceGaps = listOf(gap))
        )

        val dashboard = Analytics.build(listOf(gapBearing))
        val card = dashboard.sessions.single()
        val detail = Analytics.detail(gapBearing, listOf(gapBearing)).reviewed

        assertThat(dashboard.sessionCount).isEqualTo(1)
        assertThat(dashboard.totalElapsedMillis).isEqualTo(60_000L)
        assertThat(dashboard.totalShots).isEqualTo(complete.session.shots.size)
        assertThat(card.insights).isEmpty()
        assertThat(card.processAbsenceCount).isEqualTo(1)
        assertThat(card.unobservedMillis).isEqualTo(1_000L)
        assertThat(card.observedMillis).isEqualTo(59_000L)
        assertThat(card.workDensity).isWithin(0.0001f)
            .of(card.estimatedActiveMillis.toFloat() / card.durationMillis)
        assertThat(detail.processAbsenceCount).isEqualTo(1)
        assertThat(detail.unobservedMillis).isEqualTo(1_000L)
        assertThat(detail.observedMillis).isEqualTo(59_000L)
        assertThat(dashboard.comparisonGroups).isEmpty()
        assertThat(detail.insights).isEmpty()

        val trimmed = gapBearing.copy(
            corrections = SessionCorrections(
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromEndMillis = 1_000L,
                        provenance = CorrectionProvenance(
                            revisionId = "exclude-gap",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = complete.session.endedAtMillis + 1L
                        )
                    )
                )
            )
        )
        val reviewedDashboard = Analytics.build(listOf(trimmed))
        val trimmedCard = reviewedDashboard.sessions.single()
        val trimmedDetail = Analytics.detail(trimmed, listOf(trimmed)).reviewed

        assertThat(reviewedDashboard.comparisonGroups).hasSize(1)
        assertThat(trimmedCard.processAbsenceCount).isEqualTo(1)
        assertThat(trimmedCard.unobservedMillis).isEqualTo(0L)
        assertThat(trimmedCard.observedMillis).isEqualTo(59_000L)
        assertThat(trimmedDetail.processAbsenceCount).isEqualTo(1)
        assertThat(trimmedDetail.unobservedMillis).isEqualTo(0L)
        assertThat(trimmedDetail.observedMillis).isEqualTo(59_000L)
        assertThat(trimmedDetail.insights).isNotEmpty()
        assertThat(trimmed.session.processAbsenceGaps).containsExactly(gap)
    }

    @Test
    fun analyticsFiltersAndGroupsOnlyComparableSessionContexts() {
        fun contextual(
            start: Long,
            mode: ActivityMode,
            tag: String? = null,
            completion: SessionCompletion = SessionCompletion.Completed,
            quality: RecordingQuality = RecordingQuality.Complete
        ) = SyntheticSessions.session(start, rallies = 2, shotsPerRally = 3).copy(
            context = SessionContext(
                activityMode = mode,
                comparisonTag = tag,
                completion = completion,
                recordingQuality = quality
            )
        )

        val corpus = listOf(
            contextual(1_000L, ActivityMode.SinglesMatch),
            contextual(2_000L, ActivityMode.DoublesMatch),
            contextual(3_000L, ActivityMode.Drill, "Rear court"),
            contextual(4_000L, ActivityMode.Drill, " rear COURT "),
            contextual(
                5_000L,
                ActivityMode.Drill,
                "Rear court",
                completion = SessionCompletion.StoppedEarly,
                quality = RecordingQuality.Partial
            ),
            contextual(6_000L, ActivityMode.Drill)
        )

        val dashboard = Analytics.build(
            corpus,
            SessionAnalyticsFilter(
                activityModes = setOf(ActivityMode.Drill),
                comparisonTag = " REAR COURT ",
                completions = setOf(SessionCompletion.Completed),
                recordingQualities = setOf(RecordingQuality.Complete)
            )
        )

        assertThat(dashboard.sessionCount).isEqualTo(2)
        assertThat(dashboard.sessions.map { it.startedAtMillis })
            .containsExactly(3_000L, 4_000L)
        assertThat(dashboard.appliedFilter.comparisonTag).isEqualTo("rear court")

        val rearCourt = dashboard.comparisonGroups.single {
            it.key.activityMode == ActivityMode.Drill &&
                it.key.comparisonTag == "rear court"
        }
        // The filtered dashboard may explicitly show a partial record, but process gaps make it
        // unsafe to advertise as part of the like-for-like personal-baseline corpus.
        assertThat(rearCourt.sessionCount).isEqualTo(2)
        assertThat(rearCourt.baselineEligible).isTrue()

        val untaggedDrill = dashboard.comparisonGroups.single {
            it.key.activityMode == ActivityMode.Drill && it.key.comparisonTag == null
        }
        assertThat(untaggedDrill.sessionCount).isEqualTo(1)
        assertThat(untaggedDrill.baselineEligible).isFalse()
        assertThat(dashboard.comparisonGroups).hasSize(4)
    }

    @Test
    fun defaultDashboardExcludesUnusableRecordingsFromAggregatesAndGroups() {
        val usable = SyntheticSessions.session(
            startedAtMillis = 1_000L,
            rallies = 2,
            shotsPerRally = 3
        ).copy(
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                recordingQuality = RecordingQuality.Complete
            )
        )
        val unusable = SyntheticSessions.session(
            startedAtMillis = 2_000L,
            rallies = 5,
            shotsPerRally = 7
        ).copy(
            context = SessionContext(
                activityMode = ActivityMode.DoublesMatch,
                recordingQuality = RecordingQuality.Unusable
            )
        )

        val dashboard = Analytics.build(listOf(usable, unusable))

        assertThat(dashboard.sessionCount).isEqualTo(1)
        assertThat(dashboard.sessions.map { it.id }).containsExactly(usable.session.id)
        assertThat(dashboard.totalShots)
            .isEqualTo(usable.reviewedAnalysis().metrics.correctedDetectedHitCount)
        assertThat(dashboard.volumeTrend.last().dailyDetectedHits)
            .isEqualTo(usable.reviewedAnalysis().metrics.correctedDetectedHitCount)
        assertThat(dashboard.comparisonGroups.map { it.key.activityMode })
            .containsExactly(ActivityMode.SinglesMatch)
        assertThat(dashboard.appliedFilter.recordingQualities)
            .containsExactly(
                RecordingQuality.Unreviewed,
                RecordingQuality.Complete,
                RecordingQuality.Partial
            )
    }

    @Test
    fun explicitUnusableFilterKeepsBrokenRecordingAvailableForAudit() {
        val usable = SyntheticSessions.session(
            startedAtMillis = 1_000L,
            rallies = 2,
            shotsPerRally = 3
        ).copy(
            context = SessionContext(recordingQuality = RecordingQuality.Complete)
        )
        val unusable = SyntheticSessions.session(
            startedAtMillis = 2_000L,
            rallies = 5,
            shotsPerRally = 7
        ).copy(
            context = SessionContext(recordingQuality = RecordingQuality.Unusable)
        )

        val dashboard = Analytics.build(
            sessions = listOf(usable, unusable),
            filter = SessionAnalyticsFilter(
                recordingQualities = setOf(RecordingQuality.Unusable)
            )
        )

        assertThat(dashboard.sessionCount).isEqualTo(1)
        assertThat(dashboard.sessions.map { it.id }).containsExactly(unusable.session.id)
        assertThat(dashboard.appliedFilter.recordingQualities)
            .containsExactly(RecordingQuality.Unusable)
    }

    private fun session(
        startedAtMillis: Long,
        elapsedMillis: Long,
        estimatedActiveMillis: Long,
        detectedHits: Int,
        cardiovascularLoad: Float? = null,
        heartRateSampleCount: Int = 0,
        heartRateCoverage: Float = 0f
    ): SessionExport {
        val base = SyntheticSessions.session(
            startedAtMillis = startedAtMillis,
            rallies = 2,
            shotsPerRally = 2
        )
        return base.copy(
            session = base.session.copy(
                endedAtMillis = startedAtMillis + elapsedMillis,
                summary = base.session.summary.copy(
                    totalShots = detectedHits,
                    shotCounts = mapOf(ShotType.Unknown to detectedHits),
                    durationMillis = elapsedMillis,
                    cardiovascularLoad = cardiovascularLoad,
                    heartRateSampleCount = heartRateSampleCount,
                    heartRateCoverage = heartRateCoverage
                )
            ),
            rallyProfile = base.rallyProfile.copy(
                totalWorkMillis = estimatedActiveMillis,
                totalRestMillis = (elapsedMillis - estimatedActiveMillis).coerceAtLeast(0L)
            )
        )
    }

    private fun reviewableSession(): SessionExport {
        val start = 0L
        val end = 60_000L
        val shots = buildList {
            repeat(5) { exchange ->
                val exchangeStart = 1_000L + exchange * 11_000L
                repeat(2) { shot ->
                    val index = exchange * 2 + shot
                    add(
                        ShotEvent(
                            id = "review-hit-$index",
                            type = ShotType.Unknown,
                            timestampMillis = exchangeStart + shot * 1_000L,
                            confidence = 0.5f,
                            peakAngularVelocity = 5f,
                            heartRateBpm = null,
                            swingDurationMillis = 180L
                        )
                    )
                }
            }
        }
        val session = TrainingSession(
            id = "reviewed-session",
            startedAtMillis = start,
            endedAtMillis = end,
            summary = TrainingSummary(
                totalShots = shots.size,
                shotCounts = mapOf(ShotType.Unknown to shots.size),
                durationMillis = end - start,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap<HeartRateZone, Int>()
            ),
            shots = shots
        )
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = session,
            rallyProfile = RallySegmenter().segment(shots, sessionEndMillis = end),
            context = SessionContext(activityMode = ActivityMode.SinglesMatch)
        )
    }
}

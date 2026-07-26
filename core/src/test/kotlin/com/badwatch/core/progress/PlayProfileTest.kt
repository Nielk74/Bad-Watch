package com.badwatch.core.progress

import com.badwatch.core.model.HeartRateValueSource
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.session.RallySegmenter
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.TrimCorrectionRevision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayProfileTest {

    @Test
    fun waitsForFiveComparableSessionsAcrossThreeDays() {
        val exports = listOf(
            export("one", day = 0),
            export("two", day = 0),
            export("three", day = 1),
            export("four", day = 1),
            export("other-mode", day = 2, mode = ActivityMode.SinglesMatch)
        )

        val profile = PlayProfileBuilder.build(exports)

        assertThat(profile).isInstanceOf(PlayProfile.Building::class.java)
        profile as PlayProfile.Building
        assertThat(profile.comparableSessionCount).isEqualTo(4)
        assertThat(profile.distinctDayCount).isEqualTo(2)
        assertThat(profile.sessionsRemaining).isEqualTo(1)
        assertThat(profile.daysRemaining).isEqualTo(1)
    }

    @Test
    fun describesOneComparableContextWithoutCallingItSkill() {
        val exports = (0 until 6).map { index ->
            export(
                id = "free-$index",
                day = index / 2,
                shots = 30 + index * 10,
                averageHeartRate = 140f
            )
        } + (0 until 8).map { index ->
            export(
                id = "drill-$index",
                day = index,
                mode = ActivityMode.Drill,
                comparisonTag = null
            )
        }

        val profile = PlayProfileBuilder.build(exports)

        assertThat(profile).isInstanceOf(PlayProfile.Ready::class.java)
        profile as PlayProfile.Ready
        assertThat(profile.comparisonKey.activityMode).isEqualTo(ActivityMode.FreePlay)
        assertThat(profile.sessionCount).isEqualTo(6)
        assertThat(profile.distinctDayCount).isEqualTo(3)
        assertThat(profile.medianDetectedHitsPerMinute).isWithin(0.01f).of(5.5f)
        assertThat(profile.medianEstimatedActiveShare).isWithin(0.001f).of(0.0733f)
        assertThat(profile.medianHitsPerBurst).isWithin(0.01f).of(5f)
        assertThat(profile.medianHeartRateReservePercent).isEqualTo(63)
        assertThat(profile.recentDetectedRateChangePercent).isEqualTo(75)
    }

    @Test
    fun excludesPartialAndUnusableRecordingsFromProfileEvidence() {
        val exports = (0 until 5).map { index -> export("complete-$index", day = index) } +
            export("partial", day = 6, quality = RecordingQuality.Partial, shots = 500) +
            export("unusable", day = 7, quality = RecordingQuality.Unusable, shots = 500)

        val profile = PlayProfileBuilder.build(exports) as PlayProfile.Ready

        assertThat(profile.sessionCount).isEqualTo(5)
        assertThat(profile.medianDetectedHitsPerMinute).isWithin(0.01f).of(3f)
    }

    @Test
    fun preservesNonCardiacProfileDimensionsWhenHeartRateProfileIsUnconfigured() {
        val exports = (0 until 6).map { index ->
            export(
                id = "unconfigured-$index",
                day = index / 2,
                shots = 30 + index * 10,
                averageHeartRate = 140f,
                heartRateConfigured = false
            )
        }

        val profile = PlayProfileBuilder.build(exports) as PlayProfile.Ready

        assertThat(profile.medianDetectedHitsPerMinute).isWithin(0.01f).of(5.5f)
        assertThat(profile.medianHeartRateReservePercent).isNull()
    }

    @Test
    fun derivesProfileDimensionsFromTrimmedAndFalseHitReviewedEvidence() {
        val raw = (0 until 5).map { index -> export("reviewed-$index", day = index) }
        val corrected = raw.mapIndexed { index, export ->
            export.copy(
                corrections = SessionCorrections(
                    hitRevisions = listOf(
                        HitCorrectionRevision(
                            falseHitIds = listOf(5, 10, 15, 20, 25).map { hitIndex ->
                                "reviewed-$index-hit-$hitIndex"
                            },
                            provenance = provenance("hits-$index")
                        )
                    ),
                    trimRevisions = listOf(
                        TrimCorrectionRevision(
                            trimFromStartMillis = 10_000L,
                            provenance = provenance("trim-$index")
                        )
                    )
                )
            )
        }

        val rawProfile = PlayProfileBuilder.build(raw) as PlayProfile.Ready
        val reviewedProfile = PlayProfileBuilder.build(corrected) as PlayProfile.Ready

        assertThat(rawProfile.medianDetectedHitsPerMinute).isWithin(0.001f).of(3f)
        assertThat(rawProfile.medianHitsPerBurst).isWithin(0.001f).of(5f)
        assertThat(reviewedProfile.medianDetectedHitsPerMinute)
            .isWithin(0.001f).of(20 * 60_000f / 590_000f)
        assertThat(reviewedProfile.medianEstimatedActiveShare)
            .isWithin(0.001f).of(15_000f / 590_000f)
        assertThat(reviewedProfile.medianHitsPerBurst).isWithin(0.001f).of(4f)
    }

    private fun export(
        id: String,
        day: Int,
        shots: Int = 30,
        mode: ActivityMode = ActivityMode.FreePlay,
        comparisonTag: String? = null,
        quality: RecordingQuality = RecordingQuality.Complete,
        averageHeartRate: Float? = null,
        heartRateConfigured: Boolean = true
    ): SessionExport {
        val start = day * DAY_MILLIS
        val detectedHits = (0 until shots).map { index ->
            ShotEvent(
                id = "$id-hit-$index",
                type = ShotType.Unknown,
                timestampMillis = start + 1_000L + (index / 5) * 10_000L +
                    (index % 5) * 1_000L,
                confidence = 0.8f,
                peakAngularVelocity = 5f,
                heartRateBpm = null,
                swingDurationMillis = 180L
            )
        }
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(
                restingHeartRate = 60f,
                maxHeartRate = 187f,
                restingHeartRateSource = if (heartRateConfigured) {
                    HeartRateValueSource.UserEntered
                } else {
                    HeartRateValueSource.Unconfigured
                },
                maxHeartRateSource = if (heartRateConfigured) {
                    HeartRateValueSource.UserEntered
                } else {
                    HeartRateValueSource.Unconfigured
                }
            ),
            session = TrainingSession(
                id = id,
                startedAtMillis = start,
                endedAtMillis = start + 600_000L,
                summary = TrainingSummary(
                    totalShots = shots,
                    shotCounts = mapOf(ShotType.Unknown to shots),
                    durationMillis = 600_000L,
                    averageHeartRate = averageHeartRate,
                    maxHeartRate = averageHeartRate,
                    recoveryScore = 0f,
                    fatigueScore = 0f,
                    effortScore = 0f,
                    heartRateZoneHistogram = emptyMap(),
                    heartRateSampleCount = if (averageHeartRate == null) 0 else 400,
                    heartRateCoverage = if (averageHeartRate == null) 0f else 0.8f
                ),
                shots = detectedHits
            ),
            rallyProfile = RallySegmenter().segment(
                shots = detectedHits,
                sessionEndMillis = start + 600_000L
            ),
            context = SessionContext(
                activityMode = mode,
                comparisonTag = comparisonTag,
                recordingQuality = quality
            )
        )
    }

    private fun provenance(id: String) = CorrectionProvenance(
        revisionId = id,
        actor = CorrectionActor.Player,
        recordedAtMillis = 1L
    )

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

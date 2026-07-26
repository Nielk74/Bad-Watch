package com.badwatch.core.physiology

import com.badwatch.core.model.HeartRatePoint
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.Rally
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.CorrectionActor
import com.badwatch.core.sync.CorrectionProvenance
import com.badwatch.core.sync.HitCorrectionRevision
import com.badwatch.core.sync.SessionCorrections
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PostBurstHeartRateTest {

    @Test
    fun reportsDescriptiveChangeWhenBothWindowsHaveDistinctOpticalSamples() {
        val reference = listOf(92_000L to 164f, 100_000L to 170f, 112_000L to 168f)
        val followUp = listOf(
            151_000L to 145f,
            155_000L to 143f,
            160_000L to 141f,
            165_000L to 140f,
            169_000L to 139f
        )

        val result = PostBurstHeartRateBuilder.build(export(reference + followUp))

        assertThat(result).isNotNull()
        assertThat(result!!.nearBurstPeakBpm).isEqualTo(170)
        assertThat(result.followUpBpm).isEqualTo(141)
        assertThat(result.decreaseBpm).isEqualTo(29)
        assertThat(result.referenceSampleCount).isEqualTo(3)
        assertThat(result.followUpSampleCount).isEqualTo(5)
    }

    @Test
    fun staysSilentWhenFollowUpCoverageIsThin() {
        val points = listOf(
            92_000L to 164f,
            100_000L to 170f,
            112_000L to 168f,
            155_000L to 143f,
            165_000L to 140f
        )

        assertThat(PostBurstHeartRateBuilder.build(export(points))).isNull()
    }

    @Test
    fun staysSilentWithoutAnInferredBurst() {
        val original = export(emptyList())
        val export = original.copy(
            session = original.session.copy(shots = emptyList()),
            rallyProfile = RallyProfile.EMPTY
        )

        assertThat(PostBurstHeartRateBuilder.build(export)).isNull()
    }

    @Test
    fun playerReviewCanRemoveTheFinalBurstFromTheHeartRateObservation() {
        val points = listOf(
            92_000L to 164f,
            100_000L to 170f,
            112_000L to 168f,
            151_000L to 145f,
            155_000L to 143f,
            160_000L to 141f,
            165_000L to 140f,
            169_000L to 139f
        )
        val original = export(points)
        val reviewed = original.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = original.session.shots.map { it.id },
                        provenance = CorrectionProvenance(
                            revisionId = "remove-burst",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = 200_000L,
                            reason = "Practice swings"
                        )
                    )
                )
            )
        )

        assertThat(PostBurstHeartRateBuilder.build(original)).isNotNull()
        assertThat(PostBurstHeartRateBuilder.build(reviewed)).isNull()
    }

    private fun export(points: List<Pair<Long, Float>>): SessionExport {
        val rally = Rally(
            index = 0,
            startMillis = 95_000L,
            endMillis = 100_000L,
            shotCount = 4,
            shotCounts = mapOf(ShotType.Unknown to 4),
            peakAngularVelocity = 5f,
            averageHeartRate = 165f,
            restBeforeMillis = 0L
        )
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = TrainingSession(
                id = "session",
                startedAtMillis = 0L,
                endedAtMillis = 180_000L,
                summary = TrainingSummary(
                    totalShots = 4,
                    shotCounts = mapOf(ShotType.Unknown to 4),
                    durationMillis = 180_000L,
                    averageHeartRate = 150f,
                    maxHeartRate = 170f,
                    recoveryScore = 0f,
                    fatigueScore = 0f,
                    effortScore = 0f,
                    heartRateZoneHistogram = emptyMap(),
                    heartRateSampleCount = points.size,
                    heartRateCoverage = 0.8f
                ),
                shots = listOf(
                    shot("hit-1", 95_000L),
                    shot("hit-2", 97_000L),
                    shot("hit-3", 99_000L),
                    shot("hit-4", 100_000L)
                ),
                heartRateTrace = points.map { (time, bpm) -> HeartRatePoint(time, bpm) }
            ),
            rallyProfile = RallyProfile(
                rallies = listOf(rally),
                totalWorkMillis = 5_000L,
                totalRestMillis = 175_000L
            )
        )
    }

    private fun shot(id: String, timestampMillis: Long) = ShotEvent(
        id = id,
        type = ShotType.Unknown,
        timestampMillis = timestampMillis,
        confidence = 0.7f,
        peakAngularVelocity = 5f,
        heartRateBpm = 165f,
        swingDurationMillis = 180L
    )
}

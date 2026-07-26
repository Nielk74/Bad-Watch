package com.badwatch.app.viewmodel

import com.badwatch.core.model.HeartRateZone
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
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StoredSessionInsightsTest {

    @Test
    fun shuffledHistoryUsesOnlyEarlierLikeForLikeSessionsForTheBaseline() {
        val current = export("current", start = 1_000_000L, quietMillis = 10_000L)
        val earlier = listOf(
            export("earlier-1", start = 100_000L, quietMillis = 5_000L),
            export("earlier-2", start = 200_000L, quietMillis = 5_000L),
            export("earlier-3", start = 300_000L, quietMillis = 5_000L)
        )
        val future = export("future", start = 2_000_000L, quietMillis = 30_000L)

        val insights = buildStoredSessionInsights(
            selected = current,
            history = listOf(future, earlier[2], current, earlier[0], earlier[1])
        )

        val comparison = insights.single { it.id == "rest-ratio-up" }
        assertThat(comparison.localizationArgs["usual"]).isEqualTo("5.0")
    }

    @Test
    fun detectorCorrectionRecomputesAndSilencesTheHistoricalInsight() {
        val raw = export("corrected", start = 10_000L, quietMillis = 10_000L)
        assertThat(buildStoredSessionInsights(raw, emptyList()).map { it.id })
            .contains("rest-ratio-high")
        val oneHitFromEveryExchange = raw.rallyProfile.rallies.map { rally ->
            raw.session.shots.first { it.timestampMillis == rally.endMillis }.id
        }
        val corrected = raw.copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        falseHitIds = oneHitFromEveryExchange,
                        provenance = CorrectionProvenance(
                            revisionId = "player-review",
                            actor = CorrectionActor.Player,
                            recordedAtMillis = raw.session.endedAtMillis + 1L
                        )
                    )
                )
            )
        )

        assertThat(buildStoredSessionInsights(corrected, listOf(raw))).isEmpty()
        assertThat(corrected.session).isEqualTo(raw.session)
        assertThat(corrected.rallyProfile).isEqualTo(raw.rallyProfile)
    }

    @Test
    fun partialAndUnusableHistoricalRecordingsRemainSilent() {
        val complete = export("quality", start = 10_000L, quietMillis = 10_000L)
        assertThat(buildStoredSessionInsights(complete, emptyList())).isNotEmpty()

        listOf(RecordingQuality.Partial, RecordingQuality.Unusable).forEach { quality ->
            val selected = complete.copy(
                context = complete.context.copy(recordingQuality = quality)
            )

            assertThat(buildStoredSessionInsights(selected, emptyList())).isEmpty()
        }
    }

    private fun export(
        id: String,
        start: Long,
        quietMillis: Long,
        quality: RecordingQuality = RecordingQuality.Complete
    ): SessionExport {
        val shots = buildList {
            repeat(5) { exchange ->
                val exchangeStart = start + 1_000L + exchange * (1_000L + quietMillis)
                repeat(2) { hit ->
                    add(
                        ShotEvent(
                            id = "$id-hit-$exchange-$hit",
                            type = ShotType.Unknown,
                            timestampMillis = exchangeStart + hit * 1_000L,
                            confidence = 0.5f,
                            peakAngularVelocity = 5f,
                            heartRateBpm = null,
                            swingDurationMillis = 180L
                        )
                    )
                }
            }
        }
        val end = shots.last().timestampMillis + quietMillis
        val session = TrainingSession(
            id = id,
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
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                recordingQuality = quality
            )
        )
    }
}

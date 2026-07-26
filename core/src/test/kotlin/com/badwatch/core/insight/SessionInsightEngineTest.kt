package com.badwatch.core.insight

import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.Rally
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Note the shape of this suite: roughly half the tests assert that **nothing** is produced.
 *
 * That is the point. The failure mode this engine exists to avoid is confident narration of
 * thin data, which is what the app used to do. A rule that never stays quiet is a bug, so
 * silence is tested at least as hard as speech.
 */
class SessionInsightEngineTest {

    private val engine = SessionInsightEngine()

    // --- Staying silent -----------------------------------------------------------------

    @Test
    fun saysNothingAboutAWarmUp() {
        val profile = profile(rallies = rallies(count = 3, shotsEach = 6))

        assertThat(engine.generate(session(), profile)).isEmpty()
    }

    @Test
    fun saysNothingWhenThereAreNoRallies() {
        assertThat(engine.generate(session(), RallyProfile.EMPTY)).isEmpty()
    }

    @Test
    fun neverMentionsStrokeTypes() {
        // Stroke labels come from an uncalibrated classifier, so no insight may depend on
        // them. This guards against a well-meaning future rule quietly reintroducing that.
        val profile = profile(rallies = rallies(count = 30, shotsEach = 9), restMillis = 30_000L)

        val text = engine.generate(session(), profile)
            .joinToString(" ") { "${it.headline} ${it.detail} ${it.evidence}" }
            .lowercase()

        listOf("smash", "clear", "drop", "drive", "backhand").forEach { stroke ->
            assertThat(text).doesNotContain(stroke)
        }
    }

    @Test
    fun doesNotClaimAPersonalBestWithoutEnoughHistory() {
        val profile = profile(rallies = rallies(count = 10, shotsEach = 25))
        val thinHistory = InsightBaseline(sessionCount = 2, null, null, bestRallyShots = 5)

        val ids = engine.generate(session(), profile, thinHistory).map { it.id }

        assertThat(ids).doesNotContain("longest-rally-best")
    }

    @Test
    fun doesNotReportFatigueOnATooShortSession() {
        // Decay needs enough rallies to split into meaningful thirds.
        val decaying = rallies(count = 6, shotsEach = 12) + rallies(count = 3, shotsEach = 2)
        val profile = profile(rallies = decaying)

        assertThat(engine.generate(session(), profile).map { it.id })
            .doesNotContain("endurance-decay")
    }

    @Test
    fun staysSilentOnCardiacDriftWithoutHeartRate() {
        val profile = profile(rallies = rallies(count = 20, shotsEach = 8, heartRate = null))

        assertThat(engine.generate(session(), profile).map { it.id })
            .doesNotContain("cardiac-drift")
    }

    @Test
    fun staysSilentOnCardiacDriftWithSparseHeartRateCoverage() {
        val early = rallies(count = 8, shotsEach = 8, heartRate = 130f)
        val late = rallies(count = 8, shotsEach = 8, heartRate = 170f)

        val ids = engine.generate(
            session(heartRateCoverage = 0.2f),
            profile(rallies = early + late)
        ).map { it.id }

        assertThat(ids).doesNotContain("cardiac-drift")
    }

    // --- Firing correctly ---------------------------------------------------------------

    @Test
    fun flagsExcessiveRestAgainstSportNormsWhenThereIsNoHistory() {
        // 20 rallies of 4 s play, 20 s rest each → 1:5.
        val profile = profile(rallies = rallies(count = 20, shotsEach = 5), restMillis = 20_000L)

        val insight = engine.generate(session(), profile)
            .single { it.id == "rest-ratio-high" }

        assertThat(insight.severity).isEqualTo(InsightSeverity.Notable)
        assertThat(insight.evidence).contains("active:quiet")
    }

    @Test
    fun comparesRestAgainstThePlayersOwnNormWhenHistoryExists() {
        val profile = profile(rallies = rallies(count = 20, shotsEach = 5), restMillis = 20_000L)
        val baseline = InsightBaseline(
            sessionCount = 6,
            medianRestRatio = 2.0f,
            medianRallyShots = 8f,
            bestRallyShots = 30
        )

        val insight = engine.generate(session(), profile, baseline)
            .single { it.id == "rest-ratio-up" }

        assertThat(insight.detail).contains("than your typical")
    }

    @Test
    fun detectsRallyLengthDecayAcrossTheSession() {
        val profile = profile(
            rallies = rallies(count = 6, shotsEach = 14) +
                rallies(count = 6, shotsEach = 10) +
                rallies(count = 6, shotsEach = 4)
        )

        val insight = engine.generate(session(), profile)
            .single { it.id == "endurance-decay" }

        assertThat(insight.severity).isEqualTo(InsightSeverity.Notable)
    }

    @Test
    fun reportsCardiacDriftWhenHeartRateClimbsAcrossTheSession() {
        val early = rallies(count = 8, shotsEach = 8, heartRate = 140f)
        val late = rallies(count = 8, shotsEach = 8, heartRate = 158f)

        val insight = engine.generate(session(), profile(rallies = early + late))
            .single { it.id == "cardiac-drift" }

        assertThat(insight.severity).isEqualTo(InsightSeverity.Notable)
        assertThat(insight.evidence).contains("bpm")
    }

    @Test
    fun everyInsightCarriesEvidence() {
        val profile = profile(
            rallies = rallies(count = 8, shotsEach = 14, heartRate = 140f) +
                rallies(count = 8, shotsEach = 4, heartRate = 160f),
            restMillis = 25_000L
        )

        val insights = engine.generate(session(), profile)

        assertThat(insights).isNotEmpty()
        insights.forEach { insight ->
            assertThat(insight.evidence).isNotEmpty()
            assertThat(insight.headline).isNotEmpty()
            assertThat(insight.detail).isNotEmpty()
        }
    }

    @Test
    fun strongestObservationsAreRankedAboveInfo() {
        val profile = profile(
            rallies = rallies(count = 6, shotsEach = 25, heartRate = 140f) +
                rallies(count = 6, shotsEach = 20, heartRate = 150f) +
                rallies(count = 6, shotsEach = 6, heartRate = 165f)
        )

        val insights = engine.generate(session(), profile)

        assertThat(insights.first().severity).isEqualTo(InsightSeverity.Notable)
    }

    @Test
    fun returnsAtMostThreeInsights() {
        val profile = profile(
            rallies = rallies(count = 10, shotsEach = 25, heartRate = 140f) +
                rallies(count = 10, shotsEach = 5, heartRate = 165f),
            restMillis = 40_000L
        )

        assertThat(engine.generate(session(), profile).size).isAtMost(3)
    }

    // --- Baseline -----------------------------------------------------------------------

    @Test
    fun baselineIgnoresSessionsTooShortToCharacterise() {
        val history = listOf(
            profile(rallies = rallies(count = 2, shotsEach = 5)),
            profile(rallies = rallies(count = 20, shotsEach = 8))
        )

        assertThat(InsightBaselineBuilder.build(history).sessionCount).isEqualTo(1)
    }

    @Test
    fun baselineUsesMediansSoOneOddSessionDoesNotSkewIt() {
        val history = listOf(
            profile(rallies = rallies(count = 20, shotsEach = 8), restMillis = 10_000L),
            profile(rallies = rallies(count = 20, shotsEach = 8), restMillis = 10_000L),
            // One outlier session with enormous rest.
            profile(rallies = rallies(count = 20, shotsEach = 8), restMillis = 120_000L)
        )

        val baseline = InsightBaselineBuilder.build(history)

        // The median tracks the two typical sessions, not the outlier.
        assertThat(baseline.medianRestRatio!!).isLessThan(5f)
        assertThat(baseline.hasEnoughHistory).isTrue()
    }

    // --- Fixtures -----------------------------------------------------------------------

    private fun rallies(
        count: Int,
        shotsEach: Int,
        heartRate: Float? = 150f,
        shotIntervalMillis: Long = 800L
    ): List<Rally> = (0 until count).map { index ->
        Rally(
            index = index,
            startMillis = 0L,
            endMillis = (shotsEach - 1) * shotIntervalMillis,
            shotCount = shotsEach,
            shotCounts = mapOf(ShotType.Clear to shotsEach),
            peakAngularVelocity = 5f,
            averageHeartRate = heartRate,
            restBeforeMillis = 0L
        )
    }

    private fun profile(rallies: List<Rally>, restMillis: Long = 12_000L): RallyProfile {
        val indexed = rallies.mapIndexed { index, rally -> rally.copy(index = index) }
        return RallyProfile(
            rallies = indexed,
            totalWorkMillis = indexed.sumOf { it.durationMillis },
            totalRestMillis = restMillis * indexed.size
        )
    }

    private fun session(
        durationMillis: Long = 60L * 60 * 1000,
        heartRateCoverage: Float = 1f
    ) = TrainingSession(
        id = "session",
        startedAtMillis = 0L,
        endedAtMillis = durationMillis,
        summary = TrainingSummary(
            totalShots = 200,
            shotCounts = mapOf(ShotType.Clear to 200),
            durationMillis = durationMillis,
            averageHeartRate = 150f,
            maxHeartRate = 178f,
            recoveryScore = 0f,
            fatigueScore = 0f,
            effortScore = 0f,
            heartRateZoneHistogram = mapOf(HeartRateZone.Tempo to 100),
            heartRateSampleCount = (durationMillis / 1_000L).toInt(),
            heartRateCoverage = heartRateCoverage
        ),
        shots = emptyList()
    )
}

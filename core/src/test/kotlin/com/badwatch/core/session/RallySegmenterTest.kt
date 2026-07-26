package com.badwatch.core.session

import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RallySegmenterTest {

    private val segmenter = RallySegmenter(restThresholdMillis = 4_000L, minimumShots = 2)

    @Test
    fun splitsShotsIntoRalliesOnRestGaps() {
        // Two rallies of four shots, separated by a 10 s gap between points.
        val shots = rally(startMillis = 0L, count = 4, spacingMillis = 900L) +
            rally(startMillis = 13_000L, count = 4, spacingMillis = 900L)

        val profile = segmenter.segment(shots, sessionEndMillis = 20_000L)

        assertThat(profile.rallyCount).isEqualTo(2)
        assertThat(profile.rallies[0].shotCount).isEqualTo(4)
        assertThat(profile.rallies[1].shotCount).isEqualTo(4)
        assertThat(profile.rallies[0].restBeforeMillis).isEqualTo(0L)
        // Gap from the end of rally 1 (t=2700) to the start of rally 2 (t=13000).
        assertThat(profile.rallies[1].restBeforeMillis).isEqualTo(10_300L)
    }

    @Test
    fun keepsFastExchangesInOneRally() {
        val shots = rally(startMillis = 0L, count = 12, spacingMillis = 700L)

        val profile = segmenter.segment(shots)

        assertThat(profile.rallyCount).isEqualTo(1)
        assertThat(profile.rallies.single().shotCount).isEqualTo(12)
    }

    @Test
    fun discardsIsolatedShotsAsDetectorNoise() {
        val shots = rally(startMillis = 0L, count = 3, spacingMillis = 800L) +
            listOf(shot(60_000L)) // lone spurious detection

        val profile = segmenter.segment(shots)

        assertThat(profile.rallyCount).isEqualTo(1)
    }

    @Test
    fun computesWorkRestRatioAcrossSession() {
        // Rally 1: 0..3000 (3 s work). Rally 2: 13000..16000 (3 s work).
        // Rest between = 10 s, trailing rest to session end (20 s) = 4 s.
        val shots = rally(startMillis = 0L, count = 4, spacingMillis = 1_000L) +
            rally(startMillis = 13_000L, count = 4, spacingMillis = 1_000L)

        val profile = segmenter.segment(shots, sessionEndMillis = 20_000L)

        assertThat(profile.totalWorkMillis).isEqualTo(6_000L)
        assertThat(profile.totalRestMillis).isEqualTo(14_000L)
        assertThat(profile.restRatio).isWithin(0.01f).of(2.33f)
        assertThat(profile.workDensity).isWithin(0.01f).of(0.3f)
    }

    @Test
    fun processAbsenceIsAHardBoundaryAndNeverCountsAsDetectedRest() {
        // The 1.5 s hit gap would normally remain one exchange. The watch process was absent
        // for 0.5 s inside it, so continuity is unknown and must split at that boundary.
        val shots = listOf(shot(0L), shot(1_000L), shot(2_500L), shot(3_500L))
        val gaps = listOf(
            ProcessAbsenceGap(1_500L, 2_000L),
            ProcessAbsenceGap(4_000L, 4_800L)
        )

        val profile = segmenter.segment(
            shots = shots,
            sessionEndMillis = 5_000L,
            processAbsenceGaps = gaps
        )

        assertThat(profile.rallyCount).isEqualTo(2)
        assertThat(profile.rallies.map { it.shotCount }).containsExactly(2, 2).inOrder()
        // 1.5 s wall gap minus 0.5 s unobserved process time.
        assertThat(profile.rallies[1].restBeforeMillis).isEqualTo(1_000L)
        // 1.5 s trailing wall time minus the later 0.8 s process absence.
        assertThat(profile.totalRestMillis).isEqualTo(1_700L)
        assertThat(profile.totalWorkMillis).isEqualTo(2_000L)
    }

    @Test
    fun emptyShotListYieldsEmptyProfile() {
        assertThat(segmenter.segment(emptyList())).isEqualTo(com.badwatch.core.model.RallyProfile.EMPTY)
    }

    @Test
    fun reportsLongestRallyAndAverages() {
        val shots = rally(startMillis = 0L, count = 3, spacingMillis = 1_000L) +
            rally(startMillis = 20_000L, count = 9, spacingMillis = 1_000L)

        val profile = segmenter.segment(shots, sessionEndMillis = 40_000L)

        assertThat(profile.longestRally!!.shotCount).isEqualTo(9)
        assertThat(profile.averageShotsPerRally).isWithin(0.01f).of(6f)
    }

    private fun rally(startMillis: Long, count: Int, spacingMillis: Long): List<ShotEvent> =
        (0 until count).map { index -> shot(startMillis + index * spacingMillis) }

    private fun shot(timestampMillis: Long) = ShotEvent(
        id = "shot-$timestampMillis",
        type = ShotType.Smash,
        timestampMillis = timestampMillis,
        confidence = 0.8f,
        peakAngularVelocity = 6.5f,
        heartRateBpm = 150f,
        swingDurationMillis = 260L
    )
}

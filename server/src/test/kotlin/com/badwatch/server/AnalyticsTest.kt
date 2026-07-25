package com.badwatch.server

import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.SessionExport
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

        assertThat(dashboard.volumeTrend).hasSize(35)
        val latest = dashboard.volumeTrend.last()
        assertThat(latest.dailyElapsedMillis).isEqualTo(90 * 60 * 1_000L)
        assertThat(latest.dailyEstimatedActiveMillis).isEqualTo(70 * 60 * 1_000L)
        assertThat(latest.dailyDetectedHits).isEqualTo(70)
        assertThat(latest.rolling7DayEstimatedActiveMillis).isEqualTo(70 * 60 * 1_000L)
        assertThat(latest.rolling7DayDetectedHits).isEqualTo(70)
        // The preceding 28 days contain 40 active minutes: a 10-minute weekly average.
        assertThat(latest.previous28DayWeeklyAverageEstimatedActiveMillis)
            .isEqualTo(10 * 60 * 1_000L)

        assertThat(dashboard.sessions.first().cardiovascularLoad).isNull()
        assertThat(dashboard.sessions.last().cardiovascularLoad).isEqualTo(24f)
        assertThat(dashboard.sessions.last().heartRateCoverage).isEqualTo(0.8f)
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
}

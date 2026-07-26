package com.badwatch.app.ui

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionRecoveryUiTest {

    @Test
    fun completeOrUnreviewedDiaryLabelsCannotHideExactEffectiveUnobservedTime() {
        val complete = export(
            quality = RecordingQuality.Complete,
            gaps = listOf(
                ProcessAbsenceGap(10_000L, 20_000L),
                ProcessAbsenceGap(15_000L, 30_000L),
                ProcessAbsenceGap(50_000L, 70_000L)
            )
        )
        val unreviewed = complete.copy(
            context = complete.context.copy(recordingQuality = RecordingQuality.Unreviewed)
        )

        assertThat(complete.knownUnobservedMillisForDisplay).isEqualTo(30_000L)
        assertThat(unreviewed.knownUnobservedMillisForDisplay).isEqualTo(30_000L)
        assertThat(complete.observedEffectiveDurationMillis).isEqualTo(30_000L)
    }

    @Test
    fun gapFreeCardsHaveNoRecoveryMarker() {
        val clean = export(quality = RecordingQuality.Complete)

        assertThat(clean.knownUnobservedMillisForDisplay).isNull()
        assertThat(clean.observedEffectiveDurationMillis).isEqualTo(60_000L)
    }

    private fun export(
        quality: RecordingQuality,
        gaps: List<ProcessAbsenceGap> = emptyList()
    ): SessionExport = SessionExport(
        deviceId = "watch",
        appVersion = "test",
        profile = PlayerProfile(),
        session = TrainingSession(
            id = "session",
            startedAtMillis = 0L,
            endedAtMillis = 60_000L,
            summary = TrainingSummary(
                totalShots = 0,
                shotCounts = emptyMap(),
                durationMillis = 60_000L,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap()
            ),
            shots = emptyList(),
            processAbsenceGaps = gaps
        ),
        rallyProfile = RallyProfile.EMPTY,
        context = SessionContext(recordingQuality = quality)
    )
}

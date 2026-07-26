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

class SummaryProcessAbsenceTest {

    @Test
    fun editableQualityCannotHideImmutableProcessAbsenceFromRecap() {
        val gapBearing = export(
            gaps = listOf(ProcessAbsenceGap(200L, 400L)),
            quality = RecordingQuality.Complete
        )

        assertThat(shouldShowProcessAbsenceNotice(gapBearing)).isTrue()
        assertThat(processAbsenceNoticeMillis(gapBearing)).isEqualTo(200L)
        assertThat(
            shouldShowProcessAbsenceNotice(
                gapBearing.copy(
                    context = gapBearing.context.copy(
                        recordingQuality = RecordingQuality.Unreviewed
                    )
                )
            )
        ).isTrue()
        assertThat(shouldShowProcessAbsenceNotice(export(emptyList()))).isFalse()
    }

    private fun export(
        gaps: List<ProcessAbsenceGap>,
        quality: RecordingQuality = RecordingQuality.Complete
    ): SessionExport = SessionExport(
        deviceId = "device",
        appVersion = "test",
        profile = PlayerProfile(),
        session = TrainingSession(
            id = "session",
            startedAtMillis = 0L,
            endedAtMillis = 1_000L,
            summary = TrainingSummary(
                totalShots = 0,
                shotCounts = emptyMap(),
                durationMillis = 1_000L,
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

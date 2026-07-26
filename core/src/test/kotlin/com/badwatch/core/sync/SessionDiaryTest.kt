package com.badwatch.core.sync

import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ProcessAbsenceGap
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionDiaryTest {

    @Test
    fun structuredDiaryAndCorrectionHistoryRoundTripWithoutChangingRawOutput() {
        val original = sessionExport().copy(
            context = SessionContext(
                activityMode = ActivityMode.DoublesMatch,
                comparisonTag = "Tuesday League",
                opponent = "Northside",
                partner = "Sam",
                hall = "Jean Bouin",
                goal = "Keep serves short",
                completion = SessionCompletion.Completed,
                recordingQuality = RecordingQuality.Partial,
                diaryReviewStatus = DiaryReviewStatus.Reviewed,
                equipment = SessionEquipmentSnapshot(
                    racket = "Astrox 88D Pro",
                    string = "BG80",
                    stringTensionLbs = 27.5f,
                    shoes = "Power Cushion 65Z"
                ),
                conditions = SessionConditionsSnapshot(
                    shuttleBrand = "AS-30",
                    shuttleSpeed = "77",
                    temperatureCelsius = 19.5f,
                    draft = DraftLevel.Light
                )
            ),
            report = PostSessionReport(
                rpe = 7,
                soreness = listOf(
                    ReportedSoreness(BodyArea.Shoulder, severity = 3, side = BodySide.Right),
                    ReportedSoreness(BodyArea.Knee, severity = 1, side = BodySide.Left)
                ),
                notes = "Watch restarted after game one",
                sorenessReviewed = true
            ),
            corrections = corrections()
        )

        val encoded = BadWatchJson.encodeToString(SessionExport.serializer(), original)
        val decoded = BadWatchJson.decodeFromString(SessionExport.serializer(), encoded)

        assertThat(decoded).isEqualTo(original)
        assertThat(decoded.session).isEqualTo(sessionExport().session)
        assertThat(decoded.rallyProfile).isEqualTo(sessionExport().rallyProfile)
        assertThat(decoded.context.comparisonKey())
            .isEqualTo(SessionComparisonKey(ActivityMode.DoublesMatch, "tuesday league"))
        assertThat(decoded.corrections.hitRevisions).hasSize(2)
        assertThat(decoded.corrections.trimRevisions).hasSize(1)
    }

    @Test
    fun effectiveMetricsApplyLatestCorrectionsWithoutMutatingRawEvents() {
        val export = sessionExport().copy(corrections = corrections())

        val metrics = export.effectiveMetrics()

        assertThat(metrics.window.startedAtMillis).isEqualTo(2_500L)
        assertThat(metrics.window.endedAtMillis).isEqualTo(5_000L)
        assertThat(metrics.window.durationMillis).isEqualTo(2_500L)
        assertThat(metrics.rawDetectedHitCount).isEqualTo(6)
        assertThat(metrics.trimExcludedDetectedHitCount).isEqualTo(3)
        assertThat(metrics.falseHitCount).isEqualTo(1)
        assertThat(metrics.correctedDetectedHitCount).isEqualTo(2)
        assertThat(metrics.reportedMissedHitCount).isEqualTo(2)
        assertThat(metrics.effectiveHitCount).isEqualTo(4)
        assertThat(metrics.unknownFalseHitIds).containsExactly("missing")
        assertThat(metrics.hasCorrections).isTrue()
        assertThat(export.effectiveDetectedHits().map { it.id })
            .containsExactly("shot-2", "shot-4").inOrder()

        // Raw model output is still the complete six-event recording.
        assertThat(export.session.shots.map { it.id })
            .containsExactly("shot-0", "shot-1", "shot-2", "shot-3", "shot-4", "shot-5")
            .inOrder()
        assertThat(export.session.summary.totalShots).isEqualTo(6)
    }

    @Test
    fun overlongTrimIsBoundedToTheRawRecording() {
        val provenance = provenance("trim-overrun", 9_000L)
        val export = sessionExport().copy(
            corrections = SessionCorrections(
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 20_000L,
                        trimFromEndMillis = 20_000L,
                        provenance = provenance
                    )
                )
            )
        )

        val metrics = export.effectiveMetrics()

        assertThat(metrics.window.durationMillis).isEqualTo(0L)
        assertThat(metrics.window.startedAtMillis).isEqualTo(6_000L)
        assertThat(metrics.window.endedAtMillis).isEqualTo(6_000L)
        assertThat(metrics.correctedDetectedHitCount).isEqualTo(0)
    }

    @Test
    fun schemaOnePayloadWithoutDiaryFieldsMigratesToTruthfulEmptyDefaults() {
        val current = sessionExport()
        val encoded = BadWatchJson.encodeToString(SessionExport.serializer(), current)
        val fields = BadWatchJson.parseToJsonElement(encoded).jsonObject.toMutableMap().apply {
            remove("context")
            remove("report")
            remove("corrections")
        }
        val legacySchemaOneJson = JsonObject(fields).toString()

        val decoded = BadWatchJson.decodeFromString(
            SessionExport.serializer(),
            legacySchemaOneJson
        )

        assertThat(decoded.schemaVersion).isEqualTo(1)
        assertThat(decoded.context).isEqualTo(SessionContext())
        assertThat(decoded.report).isEqualTo(PostSessionReport())
        assertThat(decoded.corrections).isEqualTo(SessionCorrections())
        assertThat(decoded.context.activityMode).isEqualTo(ActivityMode.Unspecified)
        assertThat(decoded.context.activityMode).isNotEqualTo(ActivityMode.FreePlay)
        assertThat(decoded.context.diaryReviewStatus).isEqualTo(DiaryReviewStatus.Unreviewed)
        assertThat(decoded.context.equipment).isEqualTo(SessionEquipmentSnapshot())
        assertThat(decoded.context.conditions).isEqualTo(SessionConditionsSnapshot())
        assertThat(decoded.context.conditions.draft).isEqualTo(DraftLevel.Unreported)
        assertThat(decoded.report.sorenessReviewed).isFalse()
        assertThat(decoded.effectiveMetrics().effectiveHitCount).isEqualTo(6)
    }

    @Test
    fun diaryTextAndNumericSnapshotsRejectValuesOutsideDocumentedBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionContext(comparisonTag = "x".repeat(SessionDiaryLimits.COMPARISON_TAG_MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionContext(opponent = "x".repeat(SessionDiaryLimits.PERSON_MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionContext(hall = "x".repeat(SessionDiaryLimits.HALL_MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionContext(goal = "x".repeat(SessionDiaryLimits.GOAL_MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PostSessionReport(notes = "x".repeat(SessionDiaryLimits.NOTES_MAX_LENGTH + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionEquipmentSnapshot(
                racket = "x".repeat(SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH + 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionEquipmentSnapshot(
                stringTensionLbs = SessionDiaryLimits.STRING_TENSION_MAX_LBS + 0.1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionConditionsSnapshot(
                shuttleSpeed = "x".repeat(SessionDiaryLimits.SHUTTLE_SPEED_MAX_LENGTH + 1)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionConditionsSnapshot(
                temperatureCelsius = SessionDiaryLimits.TEMPERATURE_MIN_CELSIUS - 0.1f
            )
        }
    }

    @Test
    fun comparisonRequiresKnownLikeForLikeContext() {
        val singles = sessionExport().copy(
            context = SessionContext(activityMode = ActivityMode.SinglesMatch)
        )
        val anotherSingles = sessionExport().copy(
            context = SessionContext(activityMode = ActivityMode.SinglesMatch)
        )
        val untyped = sessionExport()
        val untaggedDrill = sessionExport().copy(
            context = SessionContext(activityMode = ActivityMode.Drill)
        )
        val taggedDrill = untaggedDrill.copy(
            context = untaggedDrill.context.copy(comparisonTag = "Net routine")
        )
        val sameTaggedDrill = untaggedDrill.copy(
            context = untaggedDrill.context.copy(comparisonTag = " net ROUTINE ")
        )

        assertThat(singles.isComparableWith(anotherSingles)).isTrue()
        assertThat(singles.isComparableWith(untaggedDrill)).isFalse()
        assertThat(untyped.isComparableWith(sessionExport())).isFalse()
        assertThat(untaggedDrill.isComparableWith(untaggedDrill)).isFalse()
        assertThat(taggedDrill.isComparableWith(sameTaggedDrill)).isTrue()
    }

    @Test
    fun immutableGapOverlapOverridesEditableCompleteQualityForInferenceAndComparison() {
        val clean = sessionExport().copy(
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                recordingQuality = RecordingQuality.Complete
            )
        )
        val gap = clean.copy(
            session = clean.session.copy(
                processAbsenceGaps = listOf(ProcessAbsenceGap(2_000L, 3_000L))
            )
        )

        assertThat(clean.isPlayerInferenceEligible).isTrue()
        assertThat(gap.hasKnownProcessAbsence).isTrue()
        assertThat(gap.knownProcessAbsenceMillisInEffectiveWindow).isEqualTo(1_000L)
        assertThat(gap.isPlayerInferenceEligible).isFalse()
        assertThat(gap.isComparableWith(clean)).isFalse()
        assertThat(clean.isComparableWith(gap)).isFalse()
    }

    @Test
    fun edgeTrimOutsideTheGapRestoresInferenceEligibilityWithoutDeletingProvenance() {
        val clean = sessionExport().copy(
            context = SessionContext(
                activityMode = ActivityMode.SinglesMatch,
                recordingQuality = RecordingQuality.Complete
            )
        )
        val trimmed = clean.copy(
            session = clean.session.copy(
                processAbsenceGaps = listOf(ProcessAbsenceGap(1_000L, 2_000L))
            ),
            corrections = SessionCorrections(
                trimRevisions = listOf(
                    TrimCorrectionRevision(
                        trimFromStartMillis = 1_000L,
                        provenance = provenance("trim-gap", 7_000L)
                    )
                )
            )
        )

        assertThat(trimmed.hasKnownProcessAbsence).isTrue()
        assertThat(trimmed.knownProcessAbsenceMillisInEffectiveWindow).isEqualTo(0L)
        assertThat(trimmed.isPlayerInferenceEligible).isTrue()
        assertThat(trimmed.isComparableWith(clean)).isTrue()
    }

    @Test
    fun effectiveHitCountSaturatesInsteadOfOverflowing() {
        val export = sessionExport().copy(
            corrections = SessionCorrections(
                hitRevisions = listOf(
                    HitCorrectionRevision(
                        missedHitCount = Int.MAX_VALUE,
                        provenance = provenance("large-count", 7_000L)
                    )
                )
            )
        )

        assertThat(export.effectiveMetrics().effectiveHitCount).isEqualTo(Int.MAX_VALUE)
    }

    private fun corrections(): SessionCorrections = SessionCorrections(
        hitRevisions = listOf(
            HitCorrectionRevision(
                falseHitIds = listOf("shot-1"),
                missedHitCount = 1,
                provenance = provenance("hit-1", 7_000L)
            ),
            HitCorrectionRevision(
                falseHitIds = listOf("shot-3", "missing", "missing"),
                missedHitCount = 2,
                provenance = provenance("hit-2", 8_000L)
            )
        ),
        trimRevisions = listOf(
            TrimCorrectionRevision(
                trimFromStartMillis = 1_500L,
                trimFromEndMillis = 1_000L,
                provenance = provenance("trim-1", 8_500L)
            )
        )
    )

    private fun provenance(id: String, timestamp: Long) = CorrectionProvenance(
        revisionId = id,
        actor = CorrectionActor.Player,
        recordedAtMillis = timestamp,
        reason = "Reviewed after the session"
    )

    private fun sessionExport(): SessionExport {
        val shots = (0 until 6).map { index ->
            ShotEvent(
                id = "shot-$index",
                type = ShotType.Unknown,
                timestampMillis = 1_000L + index * 1_000L,
                confidence = 0.6f,
                peakAngularVelocity = 5f,
                heartRateBpm = null,
                swingDurationMillis = 180L
            )
        }
        val session = TrainingSession(
            id = "session",
            startedAtMillis = 1_000L,
            endedAtMillis = 6_000L,
            summary = TrainingSummary(
                totalShots = shots.size,
                shotCounts = mapOf(ShotType.Unknown to shots.size),
                durationMillis = 5_000L,
                averageHeartRate = null,
                maxHeartRate = null,
                recoveryScore = 0f,
                fatigueScore = 0f,
                effortScore = 0f,
                heartRateZoneHistogram = emptyMap()
            ),
            shots = shots
        )
        return SessionExport(
            deviceId = "device",
            appVersion = "test",
            profile = PlayerProfile(),
            session = session,
            rallyProfile = RallyProfile.EMPTY,
            notes = mapOf("source" to "legacy-extension")
        )
    }
}

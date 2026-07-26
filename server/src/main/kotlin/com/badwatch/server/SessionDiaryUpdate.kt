package com.badwatch.server

import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.DiaryReviewStatus
import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.sync.SessionConditionsSnapshot
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionDiaryLimits
import com.badwatch.core.sync.SessionEquipmentSnapshot
import com.badwatch.core.sync.SessionExport
import kotlinx.serialization.Serializable

/**
 * Complete editable diary document accepted by the narrow dashboard endpoint.
 *
 * It deliberately contains no raw session, rally, insight, or correction fields. Applying it
 * can therefore replace only player-reported context and subjective report values.
 */
@Serializable
data class SessionDiaryUpdateRequest(
    /** Revision read with the form. Null is accepted only for legacy revision-zero records. */
    val baseDiaryRevision: Long? = null,
    val activityMode: ActivityMode = ActivityMode.Unspecified,
    val comparisonTag: String? = null,
    val opponent: String? = null,
    val partner: String? = null,
    val hall: String? = null,
    val goal: String? = null,
    val completion: SessionCompletion = SessionCompletion.Unreported,
    val recordingQuality: RecordingQuality = RecordingQuality.Unreviewed,
    val rpe: Int? = null,
    val sorenessReviewed: Boolean = false,
    val notes: String? = null,
    val equipment: SessionEquipmentSnapshot = SessionEquipmentSnapshot(),
    val conditions: SessionConditionsSnapshot = SessionConditionsSnapshot()
) {

    init {
        require(baseDiaryRevision == null || baseDiaryRevision >= 0L) {
            "Base diary revision must not be negative"
        }
    }

    /** Returns a new envelope while retaining every non-diary byte of model output/history. */
    fun applyTo(existing: SessionExport): SessionExport {
        require(sorenessReviewed || existing.report.soreness.isEmpty()) {
            "Soreness review cannot be cleared while soreness entries are present"
        }
        val context = SessionContext(
            activityMode = activityMode,
            comparisonTag = comparisonTag.cleaned(
                "Comparison tag",
                SessionDiaryLimits.COMPARISON_TAG_MAX_LENGTH
            ),
            opponent = opponent.cleaned("Opponent", SessionDiaryLimits.PERSON_MAX_LENGTH),
            partner = partner.cleaned("Partner", SessionDiaryLimits.PERSON_MAX_LENGTH),
            hall = hall.cleaned("Hall", SessionDiaryLimits.HALL_MAX_LENGTH),
            goal = goal.cleaned("Goal", SessionDiaryLimits.GOAL_MAX_LENGTH),
            completion = completion,
            recordingQuality = recordingQuality,
            diaryReviewStatus = DiaryReviewStatus.Reviewed,
            equipment = equipment.normalized(),
            conditions = conditions.normalized()
        )
        val report = PostSessionReport(
            rpe = rpe,
            soreness = existing.report.soreness,
            notes = notes.cleaned("Diary notes", SessionDiaryLimits.NOTES_MAX_LENGTH),
            sorenessReviewed = sorenessReviewed
        )
        return existing.copy(context = context, report = report)
    }
}

private fun SessionEquipmentSnapshot.normalized(): SessionEquipmentSnapshot =
    SessionEquipmentSnapshot(
        racket = racket.cleaned("Racket", SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH),
        string = string.cleaned("String", SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH),
        stringTensionLbs = stringTensionLbs,
        shoes = shoes.cleaned("Shoes", SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH)
    )

private fun SessionConditionsSnapshot.normalized(): SessionConditionsSnapshot =
    SessionConditionsSnapshot(
        shuttleBrand = shuttleBrand.cleaned(
            "Shuttle brand",
            SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH
        ),
        shuttleSpeed = shuttleSpeed.cleaned(
            "Shuttle speed",
            SessionDiaryLimits.SHUTTLE_SPEED_MAX_LENGTH
        ),
        temperatureCelsius = temperatureCelsius,
        draft = draft
    )

private fun String?.cleaned(field: String, maxLength: Int): String? {
    require(this == null || length <= maxLength) {
        "$field must be at most $maxLength characters"
    }
    return this?.trim()?.takeIf(String::isNotEmpty)
}

package com.badwatch.core.sync

import com.badwatch.core.model.ShotEvent
import kotlinx.serialization.Serializable

/**
 * Player-reported session type. [Unspecified] is deliberately different from [FreePlay]:
 * legacy sessions and skipped questions must not silently become a type of training.
 */
@Serializable
enum class ActivityMode {
    Unspecified,
    SinglesMatch,
    DoublesMatch,
    ConditionedGame,
    Drill,
    Shadow,
    FreePlay,
    Conditioning
}

/** Whether the player completed what they intended to do, not whether sensors kept running. */
@Serializable
enum class SessionCompletion {
    Unreported,
    Completed,
    StoppedEarly
}

/** Player review of the recording's coverage, kept separate from session completion. */
@Serializable
enum class RecordingQuality {
    Unreviewed,
    Complete,
    Partial,
    Unusable
}

/** Whether the optional post-session diary prompt was completed or deliberately skipped. */
@Serializable
enum class DiaryReviewStatus {
    Unreviewed,
    Reviewed,
    Skipped
}

/** Shared wire-contract bounds for player-entered diary text and numeric snapshots. */
object SessionDiaryLimits {
    const val COMPARISON_TAG_MAX_LENGTH = 64
    const val PERSON_MAX_LENGTH = 120
    const val HALL_MAX_LENGTH = 160
    const val GOAL_MAX_LENGTH = 280
    const val NOTES_MAX_LENGTH = 2_000
    const val EQUIPMENT_LABEL_MAX_LENGTH = 120
    const val SHUTTLE_SPEED_MAX_LENGTH = 40
    const val STRING_TENSION_MIN_LBS = 10f
    const val STRING_TENSION_MAX_LBS = 50f
    const val TEMPERATURE_MIN_CELSIUS = -30f
    const val TEMPERATURE_MAX_CELSIUS = 60f
}

/** Equipment actually used for this session; null fields mean the player did not report it. */
@Serializable
data class SessionEquipmentSnapshot(
    val racket: String? = null,
    val string: String? = null,
    val stringTensionLbs: Float? = null,
    val shoes: String? = null
) {
    init {
        requireOptionalText(
            field = "Racket",
            value = racket,
            maxLength = SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH
        )
        requireOptionalText(
            field = "String",
            value = string,
            maxLength = SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH
        )
        requireOptionalText(
            field = "Shoes",
            value = shoes,
            maxLength = SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH
        )
        require(
            stringTensionLbs == null ||
                stringTensionLbs.isFinite() &&
                stringTensionLbs in SessionDiaryLimits.STRING_TENSION_MIN_LBS..
                    SessionDiaryLimits.STRING_TENSION_MAX_LBS
        ) {
            "String tension must be between ${SessionDiaryLimits.STRING_TENSION_MIN_LBS} " +
                "and ${SessionDiaryLimits.STRING_TENSION_MAX_LBS} lb"
        }
    }
}

/** Player-observed air movement in the hall. [Unreported] never implies a still court. */
@Serializable
enum class DraftLevel {
    Unreported,
    None,
    Light,
    Noticeable,
    Strong
}

/** Conditions actually observed for this session; missing values remain explicitly unknown. */
@Serializable
data class SessionConditionsSnapshot(
    val shuttleBrand: String? = null,
    /** Manufacturer speed number or local grade, retained as reported rather than inferred. */
    val shuttleSpeed: String? = null,
    val temperatureCelsius: Float? = null,
    val draft: DraftLevel = DraftLevel.Unreported
) {
    init {
        requireOptionalText(
            field = "Shuttle brand",
            value = shuttleBrand,
            maxLength = SessionDiaryLimits.EQUIPMENT_LABEL_MAX_LENGTH
        )
        requireOptionalText(
            field = "Shuttle speed",
            value = shuttleSpeed,
            maxLength = SessionDiaryLimits.SHUTTLE_SPEED_MAX_LENGTH
        )
        require(
            temperatureCelsius == null ||
                temperatureCelsius.isFinite() &&
                temperatureCelsius in SessionDiaryLimits.TEMPERATURE_MIN_CELSIUS..
                    SessionDiaryLimits.TEMPERATURE_MAX_CELSIUS
        ) {
            "Temperature must be between ${SessionDiaryLimits.TEMPERATURE_MIN_CELSIUS} " +
                "and ${SessionDiaryLimits.TEMPERATURE_MAX_CELSIUS} C"
        }
    }
}

/**
 * Reported context that makes like-for-like history possible.
 *
 * [comparisonTag] is a short, stable label for variants within an activity mode, such as
 * `rear-court-multishuttle` or `six-corner-easy`. It is not free-form coaching analysis.
 * Matches and free play can be compared by mode alone; drills and conditioned work require
 * a tag before the analytics layer treats them as comparable.
 */
@Serializable
data class SessionContext(
    val activityMode: ActivityMode = ActivityMode.Unspecified,
    val comparisonTag: String? = null,
    val opponent: String? = null,
    val partner: String? = null,
    val hall: String? = null,
    val goal: String? = null,
    val completion: SessionCompletion = SessionCompletion.Unreported,
    val recordingQuality: RecordingQuality = RecordingQuality.Unreviewed,
    val diaryReviewStatus: DiaryReviewStatus = DiaryReviewStatus.Unreviewed,
    val equipment: SessionEquipmentSnapshot = SessionEquipmentSnapshot(),
    val conditions: SessionConditionsSnapshot = SessionConditionsSnapshot()
) {
    init {
        requireOptionalText(
            field = "Comparison tag",
            value = comparisonTag,
            maxLength = SessionDiaryLimits.COMPARISON_TAG_MAX_LENGTH
        )
        requireOptionalText(
            field = "Opponent",
            value = opponent,
            maxLength = SessionDiaryLimits.PERSON_MAX_LENGTH
        )
        requireOptionalText(
            field = "Partner",
            value = partner,
            maxLength = SessionDiaryLimits.PERSON_MAX_LENGTH
        )
        requireOptionalText(
            field = "Hall",
            value = hall,
            maxLength = SessionDiaryLimits.HALL_MAX_LENGTH
        )
        requireOptionalText(
            field = "Goal",
            value = goal,
            maxLength = SessionDiaryLimits.GOAL_MAX_LENGTH
        )
    }
}

@Serializable
enum class BodyArea {
    General,
    Shoulder,
    UpperArm,
    Elbow,
    Forearm,
    Wrist,
    Hand,
    UpperBack,
    LowerBack,
    Hip,
    Thigh,
    Knee,
    LowerLeg,
    Ankle,
    Foot,
    Other
}

@Serializable
enum class BodySide {
    Unspecified,
    Left,
    Right,
    Both
}

/** A player-reported 0..10 soreness rating for one body area. */
@Serializable
data class ReportedSoreness(
    val bodyArea: BodyArea,
    val severity: Int,
    val side: BodySide = BodySide.Unspecified
) {
    init {
        require(severity in 0..10) { "Soreness severity must be between 0 and 10" }
    }
}

/**
 * Subjective post-session report. RPE uses the common category-ratio 0..10 scale.
 * Empty/default values mean "not reported", never zero effort or no soreness.
 */
@Serializable
data class PostSessionReport(
    val rpe: Int? = null,
    val soreness: List<ReportedSoreness> = emptyList(),
    val notes: String? = null,
    /** Distinguishes an explicit "nothing to log" from a skipped soreness question. */
    val sorenessReviewed: Boolean = false
) {
    init {
        require(rpe == null || rpe in 0..10) { "RPE must be between 0 and 10" }
        requireOptionalText(
            field = "Diary notes",
            value = notes,
            maxLength = SessionDiaryLimits.NOTES_MAX_LENGTH
        )
    }
}

private fun requireOptionalText(field: String, value: String?, maxLength: Int) {
    require(value == null || value.length <= maxLength) {
        "$field must be at most $maxLength characters"
    }
}

/** Who supplied a correction. This is provenance, not an authentication claim. */
@Serializable
enum class CorrectionActor {
    Player,
    Coach,
    Reviewer,
    Import
}

/** Metadata common to every immutable correction revision. */
@Serializable
data class CorrectionProvenance(
    /** Stable identifier generated by the editing client. */
    val revisionId: String,
    val actor: CorrectionActor,
    val recordedAtMillis: Long,
    val reason: String? = null
) {
    init {
        require(revisionId.isNotBlank()) { "Correction revisionId must not be blank" }
        require(recordedAtMillis >= 0L) { "Correction timestamp must not be negative" }
    }
}

/**
 * One complete revision of hit corrections.
 *
 * False hits reference immutable raw [ShotEvent.id] values. Missed hits are a reported
 * count only: without an event timestamp they cannot alter rally timing or stroke mix.
 */
@Serializable
data class HitCorrectionRevision(
    val falseHitIds: List<String> = emptyList(),
    val missedHitCount: Int = 0,
    val provenance: CorrectionProvenance
) {
    init {
        require(missedHitCount >= 0) { "Missed-hit count must not be negative" }
        require(falseHitIds.none { it.isBlank() }) { "False-hit ids must not be blank" }
    }
}

/**
 * One complete revision of non-destructive edge trimming.
 *
 * Both values are offsets from the immutable raw session bounds. They are clamped to the
 * raw duration by [SessionExport.effectiveMetrics], so even a stale edit cannot manufacture
 * time outside the recording.
 */
@Serializable
data class TrimCorrectionRevision(
    val trimFromStartMillis: Long = 0L,
    val trimFromEndMillis: Long = 0L,
    val provenance: CorrectionProvenance
) {
    init {
        require(trimFromStartMillis >= 0L) { "Start trim must not be negative" }
        require(trimFromEndMillis >= 0L) { "End trim must not be negative" }
    }
}

/**
 * Append-only edit history. Array order is authoritative; the last revision of each kind
 * is the current one. Appending a zero/empty revision explicitly clears an earlier edit.
 */
@Serializable
data class SessionCorrections(
    val hitRevisions: List<HitCorrectionRevision> = emptyList(),
    val trimRevisions: List<TrimCorrectionRevision> = emptyList()
) {
    val currentHitRevision: HitCorrectionRevision? get() = hitRevisions.lastOrNull()
    val currentTrimRevision: TrimCorrectionRevision? get() = trimRevisions.lastOrNull()
}

/** Effective session interval after applying the latest non-destructive trim revision. */
@Serializable
data class EffectiveSessionWindow(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long,
    val trimFromStartMillis: Long,
    val trimFromEndMillis: Long
)

/**
 * Transparent corrected totals. Raw events remain available in [SessionExport.session].
 * [effectiveHitCount] combines corrected detections with a reported missed-hit count and
 * therefore must not be presented as a sensor measurement.
 */
@Serializable
data class EffectiveSessionMetrics(
    val window: EffectiveSessionWindow,
    val rawDetectedHitCount: Int,
    val trimExcludedDetectedHitCount: Int,
    val falseHitCount: Int,
    val correctedDetectedHitCount: Int,
    val reportedMissedHitCount: Int,
    val effectiveHitCount: Int,
    /** Referenced ids absent from the immutable raw event list; retained for audit. */
    val unknownFalseHitIds: List<String>,
    val hasCorrections: Boolean
)

/** Canonical, bounded interval produced by the current trim revision. */
fun SessionExport.effectiveWindow(): EffectiveSessionWindow {
    val rawStart = session.startedAtMillis
    val rawEnd = maxOf(rawStart, session.endedAtMillis)
    val rawDuration = rawEnd - rawStart
    val requested = corrections.currentTrimRevision
    val startTrim = requested?.trimFromStartMillis?.coerceAtMost(rawDuration) ?: 0L
    val remainingAfterStart = rawDuration - startTrim
    val endTrim = requested?.trimFromEndMillis?.coerceAtMost(remainingAfterStart) ?: 0L
    val effectiveStart = rawStart + startTrim
    val effectiveEnd = rawEnd - endTrim
    return EffectiveSessionWindow(
        startedAtMillis = effectiveStart,
        endedAtMillis = effectiveEnd,
        durationMillis = effectiveEnd - effectiveStart,
        trimFromStartMillis = startTrim,
        trimFromEndMillis = endTrim
    )
}

/** Raw detected events inside the corrected interval, before false-hit removal. */
fun SessionExport.detectedHitsInsideEffectiveWindow(): List<ShotEvent> {
    val window = effectiveWindow()
    if (window.durationMillis == 0L) return emptyList()
    return session.shots.filter { event ->
        event.timestampMillis >= window.startedAtMillis &&
            event.timestampMillis <= window.endedAtMillis
    }
}

/** Detected events remaining after both edge trimming and explicit false-hit correction. */
fun SessionExport.effectiveDetectedHits(): List<ShotEvent> {
    val falseIds = corrections.currentHitRevision?.falseHitIds.orEmpty().toSet()
    return detectedHitsInsideEffectiveWindow().filterNot { it.id in falseIds }
}

/** Deterministically applies only the latest revision of each correction kind. */
fun SessionExport.effectiveMetrics(): EffectiveSessionMetrics {
    val window = effectiveWindow()
    val rawIds = session.shots.mapTo(mutableSetOf()) { it.id }
    val hitsInsideWindow = detectedHitsInsideEffectiveWindow()
    val requestedFalseIds = corrections.currentHitRevision?.falseHitIds.orEmpty().distinct()
    val falseIds = requestedFalseIds.toSet()
    val falseHitCount = hitsInsideWindow.count { it.id in falseIds }
    val correctedDetectedCount = (hitsInsideWindow.size - falseHitCount).coerceAtLeast(0)
    val missedHitCount = corrections.currentHitRevision?.missedHitCount ?: 0

    return EffectiveSessionMetrics(
        window = window,
        rawDetectedHitCount = session.shots.size,
        trimExcludedDetectedHitCount = session.shots.size - hitsInsideWindow.size,
        falseHitCount = falseHitCount,
        correctedDetectedHitCount = correctedDetectedCount,
        reportedMissedHitCount = missedHitCount,
        effectiveHitCount = (correctedDetectedCount.toLong() + missedHitCount)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt(),
        unknownFalseHitIds = requestedFalseIds.filterNot { it in rawIds },
        hasCorrections = corrections.hitRevisions.isNotEmpty() ||
            corrections.trimRevisions.isNotEmpty()
    )
}

/** Stable comparison identity used by analytics and future UI filters. */
@Serializable
data class SessionComparisonKey(
    val activityMode: ActivityMode,
    /** Lowercase, trimmed [SessionContext.comparisonTag]. */
    val comparisonTag: String? = null
) {
    /**
     * Modes with structurally different variants require a tag. Unspecified legacy sessions
     * are grouped for display but never used as a personal performance baseline.
     */
    val baselineEligible: Boolean
        get() = when (activityMode) {
            ActivityMode.Unspecified -> false
            ActivityMode.SinglesMatch,
            ActivityMode.DoublesMatch,
            ActivityMode.FreePlay -> true
            ActivityMode.ConditionedGame,
            ActivityMode.Drill,
            ActivityMode.Shadow,
            ActivityMode.Conditioning -> comparisonTag != null
        }
}

fun SessionContext.comparisonKey(): SessionComparisonKey = SessionComparisonKey(
    activityMode = activityMode,
    comparisonTag = comparisonTag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
)

fun SessionExport.isComparableWith(other: SessionExport): Boolean {
    val key = context.comparisonKey()
    return key.baselineEligible && key == other.context.comparisonKey()
}

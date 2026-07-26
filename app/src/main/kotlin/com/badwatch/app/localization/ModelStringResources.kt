package com.badwatch.app.localization

import androidx.annotation.StringRes
import com.badwatch.app.R
import com.badwatch.core.match.MatchFormat
import com.badwatch.core.match.MatchSide
import com.badwatch.core.match.ServiceCourt
import com.badwatch.core.model.SelfReportedExperience
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.ActivityMode
import com.badwatch.core.sync.BodyArea
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionCompletion
import com.badwatch.core.training.CourtCorner

/** Resource-backed names for domain values that appear on more than one Wear surface. */
@get:StringRes
val ShotType.displayNameResource: Int
    get() = when (this) {
        ShotType.Smash -> R.string.shot_smash
        ShotType.Clear -> R.string.shot_clear
        ShotType.Drop -> R.string.shot_drop
        ShotType.Drive -> R.string.shot_drive
        ShotType.BackhandDrive -> R.string.shot_backhand_drive
        ShotType.Unknown -> R.string.shot_unclassified
    }

/**
 * Names for classifier output. These stay separate from [displayNameResource] because capture
 * drills show a stroke the player deliberately selected, while session labels are automatic
 * and must never read as established fact.
 */
@get:StringRes
val ShotType.provisionalDisplayNameResource: Int
    get() = when (this) {
        ShotType.Smash -> R.string.shot_smash_provisional
        ShotType.Clear -> R.string.shot_clear_provisional
        ShotType.Drop -> R.string.shot_drop_provisional
        ShotType.Drive -> R.string.shot_drive_provisional
        ShotType.BackhandDrive -> R.string.shot_backhand_drive_provisional
        ShotType.Unknown -> R.string.shot_unclassified_provisional
    }

@get:StringRes
val SelfReportedExperience.displayNameResource: Int
    get() = when (this) {
        SelfReportedExperience.Unspecified -> R.string.experience_not_set
        SelfReportedExperience.NewPlayer -> R.string.experience_new_player
        SelfReportedExperience.Recreational -> R.string.experience_recreational
        SelfReportedExperience.Club -> R.string.experience_club
        SelfReportedExperience.Competitive -> R.string.experience_competitive
    }

@get:StringRes
val ActivityMode.displayNameResource: Int
    get() = when (this) {
        ActivityMode.Unspecified -> R.string.activity_not_specified
        ActivityMode.SinglesMatch -> R.string.activity_singles_match
        ActivityMode.DoublesMatch -> R.string.activity_doubles_match
        ActivityMode.ConditionedGame -> R.string.activity_conditioned_game
        ActivityMode.Drill -> R.string.activity_drill
        ActivityMode.Shadow -> R.string.activity_shadow
        ActivityMode.FreePlay -> R.string.activity_free_play
        ActivityMode.Conditioning -> R.string.activity_conditioning
    }

@get:StringRes
val RecordingQuality.displayNameResource: Int
    get() = when (this) {
        RecordingQuality.Unreviewed -> R.string.recording_not_reviewed
        RecordingQuality.Complete -> R.string.recording_complete
        RecordingQuality.Partial -> R.string.recording_partial
        RecordingQuality.Unusable -> R.string.recording_unusable
    }

@get:StringRes
val SessionCompletion.displayNameResource: Int
    get() = when (this) {
        SessionCompletion.Unreported -> R.string.completion_unreported
        SessionCompletion.Completed -> R.string.completion_completed
        SessionCompletion.StoppedEarly -> R.string.completion_stopped_early
    }

@get:StringRes
val BodyArea.displayNameResource: Int
    get() = when (this) {
        BodyArea.General -> R.string.body_area_general
        BodyArea.Shoulder -> R.string.body_area_shoulder
        BodyArea.UpperArm -> R.string.body_area_upper_arm
        BodyArea.Elbow -> R.string.body_area_elbow
        BodyArea.Forearm -> R.string.body_area_forearm
        BodyArea.Wrist -> R.string.body_area_wrist
        BodyArea.Hand -> R.string.body_area_hand
        BodyArea.UpperBack -> R.string.body_area_upper_back
        BodyArea.LowerBack -> R.string.body_area_lower_back
        BodyArea.Hip -> R.string.body_area_hip
        BodyArea.Thigh -> R.string.body_area_thigh
        BodyArea.Knee -> R.string.body_area_knee
        BodyArea.LowerLeg -> R.string.body_area_lower_leg
        BodyArea.Ankle -> R.string.body_area_ankle
        BodyArea.Foot -> R.string.body_area_foot
        BodyArea.Other -> R.string.body_area_other
    }

@get:StringRes
val MatchFormat.displayNameResource: Int
    get() = when (this) {
        MatchFormat.Singles -> R.string.match_format_singles
        MatchFormat.Doubles -> R.string.match_format_doubles
    }

@get:StringRes
val MatchSide.shortNameResource: Int
    get() = when (this) {
        MatchSide.Player -> R.string.match_side_you
        MatchSide.Opponent -> R.string.match_side_them
    }

@get:StringRes
val ServiceCourt.displayNameResource: Int
    get() = when (this) {
        ServiceCourt.Right -> R.string.match_court_right
        ServiceCourt.Left -> R.string.match_court_left
    }

@get:StringRes
val CourtCorner.displayNameResource: Int
    get() = when (this) {
        CourtCorner.ForehandFront -> R.string.training_corner_forehand_front
        CourtCorner.BackhandFront -> R.string.training_corner_backhand_front
        CourtCorner.ForehandMid -> R.string.training_corner_forehand_mid
        CourtCorner.BackhandMid -> R.string.training_corner_backhand_mid
        CourtCorner.ForehandRear -> R.string.training_corner_forehand_rear
        CourtCorner.BackhandRear -> R.string.training_corner_backhand_rear
    }

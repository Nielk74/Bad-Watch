package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Which hand holds the racket.
 *
 * This matters for classification: the pronation/supination signature of a backhand
 * inverts between left and right handed players, so the sign conventions in
 * [com.badwatch.core.classifier.MotionFeatureExtractor] have to be mirrored.
 */
@Serializable
enum class Handedness {
    Right,
    Left
}

/**
 * Where the watch is worn relative to the racket hand.
 *
 * Bad Watch requires the watch on the **racket (dominant) wrist**. Worn on the other
 * wrist the swing signal simply is not present, and stroke classification is not merely
 * less accurate — it is meaningless. The app states this during onboarding rather than
 * silently producing confident nonsense.
 *
 * The enum exists so the requirement is explicit in the data model and in every exported
 * session, and so a future non-dominant (footwork-only) mode has somewhere to live.
 */
@Serializable
enum class WristPlacement {
    /** Watch on the racket hand. The only supported mode. */
    Dominant
}

/**
 * Per-player calibration inputs. Defaults are reasonable for a club-level adult player.
 *
 * @property handedness Racket hand, used to mirror motion features.
 * @property restingHeartRate Baseline used for load and effort normalisation.
 * @property maxHeartRate Estimated max HR. Defaults to the 208 - 0.7*age formula at age 30.
 * @property wristPlacement Always [WristPlacement.Dominant]; see the enum docs.
 */
@Serializable
data class PlayerProfile(
    val handedness: Handedness = Handedness.Right,
    val restingHeartRate: Float = 60f,
    val maxHeartRate: Float = 187f,
    val wristPlacement: WristPlacement = WristPlacement.Dominant
) {
    init {
        require(maxHeartRate > restingHeartRate) {
            "maxHeartRate ($maxHeartRate) must exceed restingHeartRate ($restingHeartRate)"
        }
    }

    companion object {
        /** Age-based max HR estimate (Tanaka et al.), the standard used by most wearables. */
        fun maxHeartRateForAge(ageYears: Int): Float = 208f - 0.7f * ageYears
    }
}

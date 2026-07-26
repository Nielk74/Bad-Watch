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
 * The player's own description of their badminton background.
 *
 * A single wrist cannot infer global playing level: coaching, movement, tactics and match
 * context are largely outside its view. Keeping this self-reported prevents sensor-derived
 * activity from being presented as a skill ranking.
 */
@Serializable
enum class SelfReportedExperience {
    Unspecified,
    NewPlayer,
    Recreational,
    Club,
    Competitive
}

/**
 * Provenance for one physiological profile value.
 *
 * Numeric compatibility defaults remain present in [PlayerProfile], but [Unconfigured] makes
 * it explicit that they are placeholders and cannot authorize personalized zones or reserve
 * calculations. Sources other than [Unconfigured] require an affirmative configuration or
 * import action by the player/application.
 */
@Serializable
enum class HeartRateValueSource {
    Unconfigured,
    UserEntered,
    AgeEstimated,
    HealthPlatform,
    /** Explicit developer/demo data; never inferred for a real player. */
    Synthetic;

    val isConfigured: Boolean get() = this != Unconfigured
}

/**
 * Per-player calibration inputs. The numeric physiological defaults retain wire/source
 * compatibility, but their provenance defaults to [HeartRateValueSource.Unconfigured]. They
 * must not be used for personalized physiology until the corresponding source is configured.
 * Experience likewise remains explicitly unspecified until the player reports it.
 *
 * @property handedness Racket hand, used to mirror motion features.
 * @property restingHeartRate Compatibility placeholder or configured baseline BPM.
 * @property maxHeartRate Compatibility placeholder or configured/estimated maximum BPM.
 * @property wristPlacement Always [WristPlacement.Dominant]; see the enum docs.
 * @property experience Player-reported background, never inferred from watch motion.
 */
@Serializable
data class PlayerProfile(
    val handedness: Handedness = Handedness.Right,
    val restingHeartRate: Float = 60f,
    val maxHeartRate: Float = 187f,
    val wristPlacement: WristPlacement = WristPlacement.Dominant,
    val experience: SelfReportedExperience = SelfReportedExperience.Unspecified,
    val restingHeartRateSource: HeartRateValueSource = HeartRateValueSource.Unconfigured,
    val maxHeartRateSource: HeartRateValueSource = HeartRateValueSource.Unconfigured
) {
    init {
        require(maxHeartRate > restingHeartRate) {
            "maxHeartRate ($maxHeartRate) must exceed restingHeartRate ($restingHeartRate)"
        }
        require(restingHeartRateSource != HeartRateValueSource.AgeEstimated) {
            "Resting heart rate cannot be age-estimated"
        }
    }

    val hasConfiguredRestingHeartRate: Boolean
        get() = restingHeartRateSource.isConfigured

    val hasConfiguredMaxHeartRate: Boolean
        get() = maxHeartRateSource.isConfigured

    /** Both endpoints are configured, so heart-rate reserve calculations are meaningful. */
    val hasConfiguredHeartRateReserve: Boolean
        get() = hasConfiguredRestingHeartRate && hasConfiguredMaxHeartRate

    companion object {
        /** Tanaka et al. age-based maximum-HR estimate for healthy adults. */
        fun maxHeartRateForAge(ageYears: Int): Float = 208f - 0.7f * ageYears
    }
}

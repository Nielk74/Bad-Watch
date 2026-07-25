package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * One segmented swing with a ground-truth label, for training the classifier.
 *
 * The label comes from the player choosing a stroke before a drill — it is never inferred
 * by the existing rule-based classifier. Letting the current heuristics label the training
 * data would just teach a model to reproduce their mistakes.
 *
 * @property label The stroke the player said they were hitting.
 * @property peakTimestampMillis Time of the angular-velocity peak the window is centred on.
 * @property samples The raw window, unfiltered, so feature engineering stays a decision for
 *   the training pipeline rather than something baked in on the watch.
 */
@Serializable
data class LabeledSwing(
    val id: String,
    val label: ShotType,
    val peakTimestampMillis: Long,
    val peakAngularVelocity: Float,
    val samples: List<SensorSample>,
    /** Set when the player marks a swing as mishit or otherwise unrepresentative. */
    val discarded: Boolean = false
)

/**
 * A labelled data-collection run: one drill, one stroke type, many repetitions.
 */
@Serializable
data class CaptureSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val label: ShotType,
    val swings: List<LabeledSwing>
) {
    val swingCount: Int get() = swings.count { !it.discarded }
}

package com.badwatch.core.physiology

import com.badwatch.core.model.HeartRatePoint
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.isPlayerInferenceEligible
import com.badwatch.core.sync.reviewedAnalysis
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Descriptive optical-HR change after the final inferred hit burst.
 *
 * This is not a recovery score or fitness diagnosis. It simply compares a local peak near
 * the final wearer-hit with the median optical reading 50–70 seconds later, when both
 * windows contain enough distinct sensor observations.
 */
@Serializable
data class PostBurstHeartRateChange(
    val burstEndedAtMillis: Long,
    val nearBurstPeakBpm: Int,
    val followUpBpm: Int,
    /** Positive means HR was lower at follow-up; negative means it was higher. */
    val decreaseBpm: Int,
    val followUpMidpointSeconds: Int = 60,
    val referenceSampleCount: Int,
    val followUpSampleCount: Int
)

object PostBurstHeartRateBuilder {
    private const val REFERENCE_BEFORE_MILLIS = 10_000L
    private const val REFERENCE_AFTER_MILLIS = 15_000L
    private const val FOLLOW_UP_START_MILLIS = 50_000L
    private const val FOLLOW_UP_END_MILLIS = 70_000L
    private const val MIN_REFERENCE_SAMPLES = 3
    private const val MIN_FOLLOW_UP_SAMPLES = 5

    fun build(export: SessionExport): PostBurstHeartRateChange? {
        if (!export.isPlayerInferenceEligible) return null
        val reviewed = export.reviewedAnalysis()
        val burstEnd = reviewed.rallyProfile.rallies.maxOfOrNull { it.endMillis } ?: return null
        val trace = reviewed.session.heartRateTrace
            .filter { it.beatsPerMinute.isFinite() && it.beatsPerMinute > 0f }
        val reference = trace.filter {
            it.timestampMillis in (burstEnd - REFERENCE_BEFORE_MILLIS)..
                (burstEnd + REFERENCE_AFTER_MILLIS)
        }
        val followUp = trace.filter {
            it.timestampMillis in (burstEnd + FOLLOW_UP_START_MILLIS)..
                (burstEnd + FOLLOW_UP_END_MILLIS)
        }
        if (reference.size < MIN_REFERENCE_SAMPLES || followUp.size < MIN_FOLLOW_UP_SAMPLES) {
            return null
        }

        val peak = reference.maxOf { it.beatsPerMinute }.roundToInt()
        val later = followUp.map(HeartRatePoint::beatsPerMinute).median().roundToInt()
        return PostBurstHeartRateChange(
            burstEndedAtMillis = burstEnd,
            nearBurstPeakBpm = peak,
            followUpBpm = later,
            decreaseBpm = peak - later,
            referenceSampleCount = reference.size,
            followUpSampleCount = followUp.size
        )
    }

    private fun List<Float>.median(): Float {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }
}

package com.badwatch.core.session

import com.badwatch.core.model.HeartRatePoint
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import kotlinx.serialization.Serializable

/** Compact accumulator state required to continue summary construction after process death. */
@Serializable
data class TrainingSessionAggregatorCheckpoint(
    val startedAtMillis: Long,
    val heartRateTrace: List<HeartRatePoint>,
    val shots: List<ShotEvent>,
    val lastSample: SensorSample?
) {
    init {
        require(startedAtMillis >= 0L) { "Session start must not be negative" }
    }
}

/** The classifier only needs its short rolling window and duplicate-emission boundary. */
@Serializable
data class ShotDetectionPipelineCheckpoint(
    val recentSamples: List<SensorSample>,
    val lastEmittedAtMillis: Long
) {
    init {
        require(lastEmittedAtMillis >= 0L) { "Last emitted timestamp must not be negative" }
    }
}

/**
 * Platform-free active recording checkpoint.
 *
 * This is a local durability format, not the dashboard wire contract. It intentionally stores
 * accumulated results plus the classifier's 260 ms edge, rather than retaining an unbounded
 * 100 Hz raw trace for the whole session.
 */
@Serializable
data class SessionRecorderCheckpoint(
    val schemaVersion: Int = SCHEMA_VERSION,
    val sessionId: String,
    val profile: PlayerProfile,
    val samplesProcessed: Long,
    val aggregator: TrainingSessionAggregatorCheckpoint,
    val pipeline: ShotDetectionPipelineCheckpoint
) {
    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(samplesProcessed >= 0L) { "Processed sample count must not be negative" }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

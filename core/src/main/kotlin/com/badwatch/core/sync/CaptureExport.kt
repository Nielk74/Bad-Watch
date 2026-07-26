package com.badwatch.core.sync

import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.PlayerProfile
import kotlinx.serialization.Serializable

/**
 * A labelled training-data upload.
 *
 * Kept separate from [SessionExport] because it serves a different purpose and has a very
 * different size profile: a capture run carries raw sample windows, so it is orders of
 * magnitude larger than a session summary and should never be mixed into the same batch.
 */
@Serializable
data class CaptureExport(
    val schemaVersion: Int = SessionExport.SCHEMA_VERSION,
    /** Stable installation identifier used for delivery deduplication, never as a player id. */
    val deviceId: String,
    /**
     * Pseudonymous contributor id. Separate from [deviceId] so evaluation can group by a
     * person even when hardware changes. Null identifies a legacy capture whose contributor
     * grouping is unknown; it must not silently fall back to the device id.
     */
    val participantId: String? = null,
    val appVersion: String,
    val profile: PlayerProfile,
    val capture: CaptureSession,
    /** Sampling rate the windows were recorded at, so the trainer need not infer it. */
    val samplingRateHz: Int,
    /** Consent captured at recording time. Changing a setting never uploads old raw data. */
    val dataUse: CaptureDataUse = CaptureDataUse.LocalOnly,
    /** Null means the file predates the versioned collection protocol. */
    val protocol: CaptureProtocol? = null,
    /** Hardware context is descriptive metadata, not a proxy for contributor identity. */
    val watch: CaptureWatch? = null
)

/** Raw motion windows are local by default and only leave the watch after explicit opt-in. */
@Serializable
enum class CaptureDataUse {
    LocalOnly,
    SelfHostedModelTraining
}

/** Reproducible context for the current one-stroke repetition collection flow. */
@Serializable
data class CaptureProtocol(
    val name: String = NAME,
    val version: Int = VERSION,
    val context: CaptureContext = CaptureContext.SingleStrokeDrill,
    val labelSource: CaptureLabelSource = CaptureLabelSource.PlayerSelectedBeforeDrill,
    val wrist: CaptureWrist = CaptureWrist.RacketHand,
    val targetRepetitions: Int = 20
) {
    companion object {
        const val NAME = "single-stroke-repetitions"
        const val VERSION = 1
    }
}

@Serializable
enum class CaptureContext { SingleStrokeDrill }

@Serializable
enum class CaptureLabelSource { PlayerSelectedBeforeDrill }

@Serializable
enum class CaptureWrist { RacketHand }

@Serializable
data class CaptureWatch(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int
)

@Serializable
data class CaptureEnvelope(
    val schemaVersion: Int = SessionExport.SCHEMA_VERSION,
    val captures: List<CaptureExport>
)

/** Conservative upload gate: legacy or incomplete raw captures always remain local. */
val CaptureExport.isEligibleForModelTrainingUpload: Boolean
    get() = dataUse == CaptureDataUse.SelfHostedModelTraining &&
        !participantId.isNullOrBlank() &&
        protocol != null

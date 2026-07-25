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
    val deviceId: String,
    val appVersion: String,
    val profile: PlayerProfile,
    val capture: CaptureSession,
    /** Sampling rate the windows were recorded at, so the trainer need not infer it. */
    val samplingRateHz: Int
)

@Serializable
data class CaptureEnvelope(
    val schemaVersion: Int = SessionExport.SCHEMA_VERSION,
    val captures: List<CaptureExport>
)

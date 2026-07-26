package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Durable server rejection surfaced by stored records without changing their `synced` API. */
data class SyncRejection(
    val reason: String,
    val recordedAtMillis: Long
)

internal data class StoredSyncState(
    val synced: Boolean,
    val rejection: SyncRejection?
)

@Serializable
private data class DurableSyncMarker(
    val payloadFingerprint: String,
    val recordedAtMillis: Long,
    val rejectionReason: String? = null
)

/**
 * Reads sibling sync markers for one exact payload.
 *
 * New markers carry a payload fingerprint, so an acknowledgement racing with a local edit
 * cannot mark the replacement payload as accepted or rejected. Historical `.synced` files
 * contained only a timestamp; they remain accepted for UI and storage compatibility.
 */
internal fun readStoredSyncState(payloadFile: File, payloadText: String): StoredSyncState {
    val fingerprint = payloadFingerprint(payloadText)
    val acceptedFile = acceptedMarkerFor(payloadFile)
    if (acceptedFile.exists()) {
        val marker = acceptedFile.decodeMarkerOrNull()
        if (marker == null || marker.payloadFingerprint == fingerprint) {
            return StoredSyncState(synced = true, rejection = null)
        }
    }

    val rejectedFile = rejectedMarkerFor(payloadFile)
    if (!rejectedFile.exists()) return StoredSyncState(synced = false, rejection = null)
    val marker = rejectedFile.decodeMarkerOrNull()
    if (marker != null && marker.payloadFingerprint != fingerprint) {
        return StoredSyncState(synced = false, rejection = null)
    }
    return StoredSyncState(
        synced = false,
        rejection = SyncRejection(
            reason = marker?.rejectionReason
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_REJECTION_REASON,
            recordedAtMillis = marker?.recordedAtMillis ?: rejectedFile.lastModified()
        )
    )
}

internal fun writeAcceptedSyncMarker(
    payloadFile: File,
    payloadFingerprint: String,
    writer: (File, String) -> Unit = ::writeDurableAtomically
) {
    val marker = DurableSyncMarker(
        payloadFingerprint = payloadFingerprint,
        recordedAtMillis = System.currentTimeMillis()
    )
    writer(
        acceptedMarkerFor(payloadFile),
        BadWatchJson.encodeToString(DurableSyncMarker.serializer(), marker)
    )
    // Acceptance wins if a malformed server ever lists an ID in both result sets. The synced
    // marker is written first so a failed cleanup cannot turn accepted data back into pending.
    rejectedMarkerFor(payloadFile).delete()
}

internal fun writeRejectedSyncMarker(
    payloadFile: File,
    payloadFingerprint: String,
    reason: String,
    writer: (File, String) -> Unit = ::writeDurableAtomically
) {
    val marker = DurableSyncMarker(
        payloadFingerprint = payloadFingerprint,
        recordedAtMillis = System.currentTimeMillis(),
        rejectionReason = reason.trim().ifEmpty { DEFAULT_REJECTION_REASON }.take(MAX_REASON_LENGTH)
    )
    writer(
        rejectedMarkerFor(payloadFile),
        BadWatchJson.encodeToString(DurableSyncMarker.serializer(), marker)
    )
    acceptedMarkerFor(payloadFile).delete()
}

/** Clears state before a changed payload is installed, preventing a stale marker from winning. */
internal fun clearStoredSyncState(payloadFile: File, requireSuccess: Boolean) {
    listOf(acceptedMarkerFor(payloadFile), rejectedMarkerFor(payloadFile)).forEach { marker ->
        if (marker.exists() && !marker.delete() && requireSuccess) {
            throw IOException("Could not clear sync state for ${payloadFile.name}")
        }
        File(marker.parentFile, "${marker.name}.tmp").delete()
    }
}

internal fun acceptedMarkerFor(payloadFile: File): File =
    File(payloadFile.parentFile, "${payloadFile.name}.synced")

internal fun rejectedMarkerFor(payloadFile: File): File =
    File(payloadFile.parentFile, "${payloadFile.name}.rejected")

private fun File.decodeMarkerOrNull(): DurableSyncMarker? = runCatching {
    BadWatchJson.decodeFromString(DurableSyncMarker.serializer(), readText())
}.getOrNull()

internal fun payloadFingerprint(payloadText: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(payloadText.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private const val DEFAULT_REJECTION_REASON = "Server rejected this record"
private const val MAX_REASON_LENGTH = 2_000

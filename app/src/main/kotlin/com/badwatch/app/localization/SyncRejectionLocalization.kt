package com.badwatch.app.localization

import androidx.annotation.StringRes
import com.badwatch.app.R

/** Stable player-facing categories for server diagnostics, which may contain English IDs/text. */
internal enum class SyncRejectionKind(@param:StringRes val messageRes: Int) {
    IncompatibleSchema(R.string.history_rejected_schema),
    EditConflict(R.string.history_rejected_edit_conflict),
    IdentityConflict(R.string.history_rejected_identity_conflict),
    InvalidRecord(R.string.history_rejected_invalid),
    Generic(R.string.history_rejected_default)
}

internal fun classifySyncRejection(reason: String): SyncRejectionKind {
    val normalized = reason.lowercase()
    return when {
        "unsupported schema" in normalized || "incompatible" in normalized ->
            SyncRejectionKind.IncompatibleSchema
        "divergent" in normalized || "revision" in normalized ->
            SyncRejectionKind.EditConflict
        "immutable recorded evidence" in normalized || "already uses" in normalized ->
            SyncRejectionKind.IdentityConflict
        normalized == "server rejected this record" -> SyncRejectionKind.Generic
        else -> SyncRejectionKind.InvalidRecord
    }
}

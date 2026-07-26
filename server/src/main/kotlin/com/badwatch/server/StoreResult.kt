package com.badwatch.server

/** Outcome of one atomic per-record repository write. */
enum class StoreResult {
    Created,
    Replaced,
    Unchanged
}

/**
 * The payload is internally valid JSON, but it cannot describe the record already stored under
 * the same stable id. Upload routes may reject this record permanently; retrying identical bytes
 * cannot make an immutable identity collision valid.
 */
class StoredRecordValidationException(message: String) : IllegalArgumentException(message)

/**
 * Two valid mutable histories diverged from the same record. Neither branch is discarded.
 * Callers receive HTTP 409 and must retain their local payload until the owner resolves it.
 */
class StoredRecordConflictException(message: String) : IllegalStateException(message)

/**
 * IDs become filenames in the current storage engine. Restrict them to the UUID-like form
 * emitted by the watch so imports and uploads cannot escape the configured data directory.
 */
internal fun isSafeStorageId(id: String): Boolean =
    id.length in 1..128 && id.first().isLetterOrDigit() &&
        id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

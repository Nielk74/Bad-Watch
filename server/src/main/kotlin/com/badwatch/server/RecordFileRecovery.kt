package com.badwatch.server

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Reads the repository's one-record-per-JSON layout and repairs interrupted atomic writes.
 *
 * [writeDurableAtomically] writes `<record>.json.tmp` before moving it over the destination.
 * A process or host failure can therefore leave either an orphan temporary file or an invalid
 * destination beside a valid temporary file. Recovery is deliberately conservative:
 *
 * - a valid destination is authoritative;
 * - a valid orphan replaces a missing or invalid destination;
 * - conflicting valid bytes and invalid payloads are moved aside, never overwritten; and
 * - an I/O failure while reading or moving a file is propagated instead of being described as
 *   corrupt data.
 *
 * Callers must hold their repository lock for the complete scan.
 */
internal fun <T> recoverStoredJsonRecords(
    directory: File,
    decode: (String) -> T,
    belongsToDestination: (destination: File, payload: T) -> Boolean
): List<Pair<File, T>> {
    ensureStorageDirectory(directory)
    val children = directory.listFiles()
        ?: throw IOException("Cannot list storage directory '${directory.absolutePath}'")
    val recordNames = children.asSequence()
        .mapNotNull { file ->
            when {
                file.name.endsWith(JSON_SUFFIX) -> file.name
                file.name.endsWith(TEMP_SUFFIX) -> file.name.removeSuffix(TEMP_MARKER)
                else -> null
            }
        }
        .distinct()
        .sorted()
        .toList()

    return recordNames.mapNotNull { recordName ->
        val destination = File(directory, recordName)
        val temporary = File(directory, "$recordName$TEMP_MARKER")
        recoverRecordPair(destination, temporary, decode, belongsToDestination)
    }
}

private fun ensureStorageDirectory(directory: File) {
    if (directory.isDirectory) return
    if (directory.exists() || !directory.mkdirs() || !directory.isDirectory) {
        throw IOException("Cannot create storage directory '${directory.absolutePath}'")
    }
}

private fun <T> recoverRecordPair(
    destination: File,
    temporary: File,
    decode: (String) -> T,
    belongsToDestination: (destination: File, payload: T) -> Boolean
): Pair<File, T>? {
    val destinationState = readState(destination, decode, belongsToDestination)
    val temporaryState = readState(temporary, decode) { _, payload ->
        belongsToDestination(destination, payload)
    }

    return when (destinationState) {
        StoredFileState.Missing -> when (temporaryState) {
            StoredFileState.Missing -> null
            is StoredFileState.Invalid -> {
                quarantine(temporary, QuarantineReason.Invalid)
                null
            }
            is StoredFileState.Valid -> {
                moveWithoutReplacement(temporary, destination)
                destination to temporaryState.payload
            }
        }

        is StoredFileState.Invalid -> when (temporaryState) {
            StoredFileState.Missing -> {
                quarantine(destination, QuarantineReason.Invalid)
                null
            }
            is StoredFileState.Invalid -> {
                quarantine(destination, QuarantineReason.Invalid)
                quarantine(temporary, QuarantineReason.Invalid)
                null
            }
            is StoredFileState.Valid -> {
                quarantine(destination, QuarantineReason.Invalid)
                moveWithoutReplacement(temporary, destination)
                destination to temporaryState.payload
            }
        }

        is StoredFileState.Valid -> {
            when (temporaryState) {
                StoredFileState.Missing -> Unit
                is StoredFileState.Invalid -> quarantine(temporary, QuarantineReason.Invalid)
                is StoredFileState.Valid -> {
                    if (destinationState.payload == temporaryState.payload) {
                        Files.delete(temporary.toPath())
                    } else {
                        quarantine(temporary, QuarantineReason.Conflict)
                    }
                }
            }
            destination to destinationState.payload
        }
    }
}

private fun <T> readState(
    file: File,
    decode: (String) -> T,
    belongsToDestination: (File, T) -> Boolean
): StoredFileState<T> {
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        return StoredFileState.Missing
    }

    // Keep reading outside runCatching: permission failures, directories named *.json and other
    // I/O problems require operator attention and must not be renamed as malformed payloads.
    val text = file.readText(Charsets.UTF_8)
    val payload = try {
        decode(text)
    } catch (_: IllegalArgumentException) {
        return StoredFileState.Invalid
    }
    return if (belongsToDestination(file, payload)) {
        StoredFileState.Valid(payload)
    } else {
        StoredFileState.Invalid
    }
}

private fun quarantine(file: File, reason: QuarantineReason): File {
    val baseName = "${file.name}.quarantine-${reason.fileLabel}"
    var attempt = 0
    while (true) {
        val suffix = if (attempt == 0) "" else ".$attempt"
        val target = File(file.parentFile, "$baseName$suffix")
        try {
            // No REPLACE_EXISTING: earlier evidence always survives repeated recovery attempts.
            return Files.move(file.toPath(), target.toPath()).toFile()
        } catch (_: FileAlreadyExistsException) {
            attempt += 1
        }
    }
}

private fun moveWithoutReplacement(source: File, destination: File) {
    // Both files are siblings, so a normal move remains on one filesystem. Omitting
    // REPLACE_EXISTING protects a destination created by an external operator mid-recovery.
    Files.move(source.toPath(), destination.toPath())
}

private sealed interface StoredFileState<out T> {
    data object Missing : StoredFileState<Nothing>
    data object Invalid : StoredFileState<Nothing>
    data class Valid<T>(val payload: T) : StoredFileState<T>
}

private enum class QuarantineReason(val fileLabel: String) {
    Invalid("invalid"),
    Conflict("conflict")
}

private const val JSON_SUFFIX = ".json"
private const val TEMP_MARKER = ".tmp"
private const val TEMP_SUFFIX = "$JSON_SUFFIX$TEMP_MARKER"

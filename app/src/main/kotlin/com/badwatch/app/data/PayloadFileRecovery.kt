package com.badwatch.app.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Reconciles crash-left JSON temp files and removes undecodable payloads from the live corpus.
 *
 * A valid destination always wins over an orphan temp. Conflicting evidence is quarantined
 * under a unique sibling name; no quarantine or recovery move uses replacement semantics.
 */
internal fun recoverAndQuarantineJsonPayloads(
    directory: File,
    validatePayload: (File) -> PayloadValidation,
    onPayloadIdentityReset: (File) -> Unit = {}
) {
    directory.mkdirs()
    directory.listFiles { file -> file.name.endsWith(".json.tmp") }
        ?.sortedBy { it.name }
        ?.forEach { temporary ->
            when (validatePayload(temporary)) {
                PayloadValidation.Invalid -> {
                    quarantineSafely(temporary, "invalid")
                    return@forEach
                }
                // An I/O error is not evidence of corruption. Leave bytes untouched and retry.
                PayloadValidation.Unreadable -> return@forEach
                PayloadValidation.Valid -> Unit
            }

            val destination = File(
                temporary.parentFile,
                temporary.name.removeSuffix(".tmp")
            )
            if (destination.exists()) {
                when (validatePayload(destination)) {
                    PayloadValidation.Valid -> {
                        // Never replace a known-good payload. Preserve the valid but conflicting
                        // temp for manual inspection instead of silently deleting either copy.
                        quarantineSafely(temporary, "conflict")
                        return@forEach
                    }
                    // Do not demote a payload merely because storage was temporarily unreadable.
                    PayloadValidation.Unreadable -> return@forEach
                    PayloadValidation.Invalid -> Unit
                }
                if (quarantineSafely(destination, "invalid") == null) {
                    // Keep the valid temp in place and retry later if quarantine itself failed.
                    return@forEach
                }
                onPayloadIdentityReset(destination)
            }

            // A filename may retain an old legacy sync marker even when its payload vanished.
            // Reset that identity before installing the recovered bytes.
            onPayloadIdentityReset(destination)
            val recovered = runCatching {
                // No REPLACE_EXISTING: another reader/process winning this race must not lose
                // its good destination. Same-directory atomic move preserves the fsynced temp's
                // crash boundary; OEM filesystems that do not advertise it use the safe fallback.
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath())
                }
            }.isSuccess
            if (!recovered && destination.exists()) {
                quarantineSafely(temporary, "conflict")
            }
        }

    directory.listFiles { file -> file.extension == "json" }
        ?.sortedBy { it.name }
        ?.forEach { payload ->
            if (validatePayload(payload) == PayloadValidation.Invalid &&
                quarantineSafely(payload, "invalid") != null
            ) {
                onPayloadIdentityReset(payload)
            }
        }
}

internal enum class PayloadValidation { Valid, Invalid, Unreadable }

/** Moves [source] to a unique sibling and never overwrites an earlier quarantine. */
private fun quarantineSafely(source: File, label: String): File? {
    if (!source.exists()) return null
    var index = 0
    while (index < MAX_QUARANTINE_ATTEMPTS) {
        val suffix = if (index == 0) label else "$label.$index"
        val candidate = File(source.parentFile, "${source.name}.$suffix")
        if (!candidate.exists()) {
            val moved = runCatching {
                Files.move(source.toPath(), candidate.toPath())
                candidate
            }.getOrNull()
            if (moved != null) return moved
            if (!source.exists()) return candidate.takeIf { it.exists() }
            if (!candidate.exists()) return null
        }
        index++
    }
    return null
}

private const val MAX_QUARANTINE_ATTEMPTS = 10_000

package com.badwatch.app.data

import com.badwatch.core.session.SessionRecorderCheckpoint
import com.badwatch.core.sync.BadWatchJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** App/version identity and compact recorder state for one still-active session. */
@Serializable
data class ActiveSessionJournalEntry(
    val schemaVersion: Int = SCHEMA_VERSION,
    val checkpoint: SessionRecorderCheckpoint,
    val deviceId: String,
    val appVersion: String,
    val recoveryCount: Int = 0,
    val updatedAtMillis: Long
) {
    init {
        require(deviceId.isNotBlank()) { "Journal device id must not be blank" }
        require(appVersion.isNotBlank()) { "Journal app version must not be blank" }
        require(recoveryCount >= 0) { "Recovery count must not be negative" }
        require(updatedAtMillis >= 0L) { "Journal timestamp must not be negative" }
    }

    val isSupported: Boolean
        get() = schemaVersion == SCHEMA_VERSION &&
            checkpoint.schemaVersion == SessionRecorderCheckpoint.SCHEMA_VERSION

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Single-file active-session journal.
 *
 * A corrupt or unsupported document is moved aside so a service restart cannot loop forever.
 * A fully written orphan `.tmp` is recoverable when the process died before its first rename.
 */
class ActiveSessionJournal(private val file: File) {
    private val mutex = Mutex()

    suspend fun save(entry: ActiveSessionJournalEntry): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                writeDurableAtomically(
                    file,
                    BadWatchJson.encodeToString(ActiveSessionJournalEntry.serializer(), entry)
                )
            }.isSuccess
        }
    }

    suspend fun load(): ActiveSessionJournalEntry? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val temporary = temporaryFile()
            val candidate = when {
                file.exists() -> {
                    // A main file is a complete older checkpoint; a sibling temp can only be
                    // an interrupted newer replacement and must not outrank it.
                    temporary.delete()
                    file
                }
                temporary.exists() -> temporary
                else -> return@withContext null
            }

            val entry = runCatching {
                BadWatchJson.decodeFromString(
                    ActiveSessionJournalEntry.serializer(),
                    candidate.readText()
                ).also { decoded ->
                    require(decoded.isSupported) {
                        "Unsupported active-session journal schema ${decoded.schemaVersion}"
                    }
                }
            }.getOrElse {
                quarantine(candidate)
                return@withContext null
            }

            if (candidate == temporary) {
                runCatching {
                    file.parentFile?.mkdirs()
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
            entry
        }
    }

    /** Idempotent explicit discard/final-save cleanup. */
    suspend fun clear(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val mainCleared = !file.exists() || file.delete()
            val temporary = temporaryFile()
            val tempCleared = !temporary.exists() || temporary.delete()
            mainCleared && tempCleared
        }
    }

    private fun quarantine(candidate: File) {
        val invalid = File(candidate.parentFile, "${file.name}.invalid")
        invalid.delete()
        val moved = runCatching {
            Files.move(
                candidate.toPath(),
                invalid.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }.isSuccess
        if (!moved) candidate.delete()
        temporaryFile().takeIf { it != candidate }?.delete()
    }

    private fun temporaryFile(): File = File(file.parentFile, "${file.name}.tmp")
}

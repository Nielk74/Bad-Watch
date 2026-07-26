package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.training.ShadowRoutineState
import java.io.File
import kotlinx.serialization.Serializable

/** Versioned durable document for the one shadow routine currently in progress. */
@Serializable
data class ShadowRoutineDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val state: ShadowRoutineState,
    val updatedAtMillis: Long
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported shadow routine schema $schemaVersion"
        }
        require(updatedAtMillis >= 0L) { "Shadow update time cannot be negative" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** Atomic app-private checkpoint for a restorable watch-guided routine. */
interface ShadowRoutinePersistence {
    fun load(): ShadowRoutineLoadResult
    fun save(document: ShadowRoutineDocument)
    fun clear()
}

class ShadowRoutineStore(private val file: File) : ShadowRoutinePersistence {

    override fun load(): ShadowRoutineLoadResult {
        if (!file.exists()) return ShadowRoutineLoadResult.Empty
        return runCatching {
            ShadowRoutineLoadResult.Loaded(
                BadWatchJson.decodeFromString(
                    ShadowRoutineDocument.serializer(),
                    file.readText()
                )
            )
        }.getOrElse { error ->
            ShadowRoutineLoadResult.Corrupt(error.message ?: "Invalid saved shadow routine")
        }
    }

    override fun save(document: ShadowRoutineDocument) {
        writeDurableAtomically(
            file = file,
            text = BadWatchJson.encodeToString(ShadowRoutineDocument.serializer(), document)
        )
    }

    override fun clear() {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        check(!file.exists() || file.delete()) { "Could not remove saved shadow routine" }
        check(!temporary.exists() || temporary.delete()) {
            "Could not remove temporary shadow routine"
        }
    }
}

sealed interface ShadowRoutineLoadResult {
    data object Empty : ShadowRoutineLoadResult
    data class Loaded(val document: ShadowRoutineDocument) : ShadowRoutineLoadResult
    data class Corrupt(val reason: String) : ShadowRoutineLoadResult
}

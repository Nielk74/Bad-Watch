package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Stores labelled capture runs, mirroring [SessionStore].
 *
 * Kept separate from sessions because the two have very different lifecycles: sessions are
 * the product, captures are development data that gets pulled off the watch and deleted.
 * Mixing them would mean a training run bloating the player's session history.
 */
class CaptureStore(private val directory: File) {

    private val _captures = MutableStateFlow<List<StoredCapture>>(emptyList())
    val captures: StateFlow<List<StoredCapture>> = _captures.asStateFlow()

    suspend fun refresh(): List<StoredCapture> = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val loaded = directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> readOrNull(file) }
            ?.sortedByDescending { it.export.capture.startedAtMillis }
            .orEmpty()
        _captures.value = loaded
        loaded
    }

    suspend fun save(export: CaptureExport): StoredCapture = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val file = File(
            directory,
            "${export.capture.startedAtMillis}-${export.capture.label.name}-${export.capture.id}.json"
        )
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(BadWatchJson.encodeToString(CaptureExport.serializer(), export))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        val stored = StoredCapture(file = file, export = export, synced = false)
        _captures.value = (_captures.value + stored)
            .sortedByDescending { it.export.capture.startedAtMillis }
        stored
    }

    suspend fun unsynced(): List<StoredCapture> = refresh().filterNot { it.synced }

    suspend fun markSynced(captureIds: Collection<String>) = withContext(Dispatchers.IO) {
        val ids = captureIds.toSet()
        _captures.value
            .filter { it.export.capture.id in ids }
            .forEach { markerFor(it.file).writeText(System.currentTimeMillis().toString()) }
        refresh()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        refresh()
        Unit
    }

    /** Total labelled swings on the watch — the number that matters for dataset progress. */
    suspend fun totalSwings(): Int = refresh().sumOf { it.export.capture.swingCount }

    private fun readOrNull(file: File): StoredCapture? = runCatching {
        val export = BadWatchJson.decodeFromString(CaptureExport.serializer(), file.readText())
        StoredCapture(file = file, export = export, synced = markerFor(file).exists())
    }.getOrNull()

    private fun markerFor(file: File) = File(file.parentFile, "${file.name}.synced")
}

data class StoredCapture(
    val file: File,
    val export: CaptureExport,
    val synced: Boolean
)

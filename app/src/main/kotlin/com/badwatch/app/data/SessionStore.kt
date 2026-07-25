package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.SessionExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-watch session storage: one JSON file per session.
 *
 * The original plan called for Room. That was over-engineered for the actual access
 * pattern: the watch only ever needs to list sessions, read one back, mark it synced and
 * delete it — all the aggregation and querying happens on the dashboard server. A file per
 * session gives us durable storage, an export format and the sync payload in a single
 * representation, with no annotation processor in the build. Revisit if on-watch trend
 * queries over hundreds of sessions ever become a real feature.
 *
 * Files are named `<epochMillis>-<sessionId>.json` so a directory listing sorts
 * chronologically without parsing anything.
 */
class SessionStore(private val directory: File) {

    private val _sessions = MutableStateFlow<List<StoredSession>>(emptyList())
    val sessions: StateFlow<List<StoredSession>> = _sessions.asStateFlow()

    suspend fun refresh(): List<StoredSession> = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val loaded = directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> readOrNull(file) }
            ?.sortedByDescending { it.export.session.startedAtMillis }
            .orEmpty()
        _sessions.value = loaded
        loaded
    }

    suspend fun save(export: SessionExport): StoredSession = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val file = File(directory, fileNameFor(export))
        // Write to a temp file and rename, so a crash mid-write cannot leave a truncated
        // session that fails to parse on next launch.
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(BadWatchJson.encodeToString(SessionExport.serializer(), export))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        val stored = StoredSession(file = file, export = export, synced = false)
        _sessions.value = (_sessions.value + stored)
            .sortedByDescending { it.export.session.startedAtMillis }
        stored
    }

    suspend fun unsynced(): List<StoredSession> =
        refresh().filterNot { it.synced }

    /**
     * Records that the server accepted these sessions.
     *
     * Sync state lives in a sibling marker file rather than inside the JSON, so the synced
     * payload stays byte-identical to what was uploaded and re-uploads are idempotent.
     */
    suspend fun markSynced(sessionIds: Collection<String>) = withContext(Dispatchers.IO) {
        val ids = sessionIds.toSet()
        _sessions.value
            .filter { it.export.session.id in ids }
            .forEach { stored -> markerFor(stored.file).writeText(System.currentTimeMillis().toString()) }
        refresh()
        Unit
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        _sessions.value.firstOrNull { it.export.session.id == sessionId }?.let { stored ->
            stored.file.delete()
            markerFor(stored.file).delete()
        }
        refresh()
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { it.delete() }
        refresh()
        Unit
    }

    /** The full corpus as one envelope-ready list, for manual export via `adb pull` or share. */
    suspend fun exportAllJson(): String = withContext(Dispatchers.IO) {
        val all = refresh().map { it.export }
        BadWatchJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SessionExport.serializer()),
            all
        )
    }

    private fun readOrNull(file: File): StoredSession? = runCatching {
        val export = BadWatchJson.decodeFromString(SessionExport.serializer(), file.readText())
        StoredSession(file = file, export = export, synced = markerFor(file).exists())
    }.getOrNull()

    private fun markerFor(file: File) = File(file.parentFile, "${file.name}.synced")

    private fun fileNameFor(export: SessionExport): String =
        "${export.session.startedAtMillis}-${export.session.id}.json"
}

data class StoredSession(
    val file: File,
    val export: SessionExport,
    val synced: Boolean
)

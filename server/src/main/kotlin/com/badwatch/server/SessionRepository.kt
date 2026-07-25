package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.SessionExport
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Server-side session storage: one JSON file per session, mirroring the watch.
 *
 * A real database is not yet warranted. A heavy player produces a few hundred sessions a
 * year, each a few hundred kilobytes — the entire corpus fits comfortably in memory, and
 * keeping the on-disk format identical to the sync payload means an operator can back up,
 * inspect or hand-edit the data with nothing but a text editor. The read-through cache
 * below is the only concession to performance, and it is bounded by the same reasoning.
 */
class SessionRepository(private val directory: File) {

    private val lock = ReentrantReadWriteLock()
    private var cache: MutableMap<String, SessionExport>? = null

    /** @return true if this session was new, false if it replaced an existing one. */
    fun save(export: SessionExport): Boolean = lock.write {
        directory.mkdirs()
        val sessions = loadUnlocked()
        val isNew = !sessions.containsKey(export.session.id)
        val file = File(directory, "${export.session.id}.json")
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(BadWatchJson.encodeToString(SessionExport.serializer(), export))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        sessions[export.session.id] = export
        isNew
    }

    fun all(): List<SessionExport> = lock.read {
        loadUnlocked().values.sortedByDescending { it.session.startedAtMillis }
    }

    fun find(sessionId: String): SessionExport? = lock.read { loadUnlocked()[sessionId] }

    fun delete(sessionId: String): Boolean = lock.write {
        val removed = loadUnlocked().remove(sessionId) != null
        File(directory, "$sessionId.json").delete()
        removed
    }

    private fun loadUnlocked(): MutableMap<String, SessionExport> {
        cache?.let { return it }
        directory.mkdirs()
        val loaded = directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    BadWatchJson.decodeFromString(SessionExport.serializer(), file.readText())
                }.getOrNull()
            }
            ?.associateBy { it.session.id }
            ?.toMutableMap()
            ?: mutableMapOf()
        cache = loaded
        return loaded
    }
}

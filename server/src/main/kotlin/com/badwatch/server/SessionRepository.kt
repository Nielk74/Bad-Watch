package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.SessionExport
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
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
    // The first cache fill may move recovery files, so every path that can call loadUnlocked
    // holds the exclusive lock. Subsequent cached reads remain tiny at this data scale.
    private var cache: MutableMap<String, SessionExport>? = null

    /** @return true if this session was new, false if it replaced or matched an existing one. */
    fun save(export: SessionExport): Boolean = upsert(export) == StoreResult.Created

    fun upsert(export: SessionExport): StoreResult = upsertAll(listOf(export)).single()

    /**
     * Preflights every merge before the first write, then persists each merged record atomically.
     * A retry after an I/O interruption is safe because all merge operations are idempotent.
     */
    fun upsertAll(exports: List<SessionExport>): List<StoreResult> = lock.write {
        val sessions = loadUnlocked()
        val working = sessions.toMutableMap()
        val planned = exports.map { incoming ->
            if (!isSafeStorageId(incoming.session.id)) {
                throw StoredRecordValidationException("Unsafe session id")
            }
            val existing = working[incoming.session.id]
            val merged = existing?.let { mergeSessionExports(it, incoming) }
                ?: incoming.normalizedServerDiaryLineage()
            val result = when {
                existing == null -> StoreResult.Created
                existing == merged -> StoreResult.Unchanged
                else -> StoreResult.Replaced
            }
            working[incoming.session.id] = merged
            PlannedSessionWrite(merged, result)
        }

        directory.mkdirs()
        planned.forEach { plan ->
            if (plan.result != StoreResult.Unchanged) {
                val export = plan.export
                val file = File(directory, "${export.session.id}.json")
                writeDurableAtomically(
                    file,
                    BadWatchJson.encodeToString(SessionExport.serializer(), export)
                )
                // Keep the in-memory view aligned with any writes completed before a later I/O
                // failure. Retrying the full plan remains safe and converges on the same bytes.
                sessions[export.session.id] = export
            }
        }
        planned.map { it.result }
    }

    /** Validates merge compatibility without writing, used to preflight archive restores. */
    fun validateUpserts(exports: List<SessionExport>) = lock.write {
        val working = loadUnlocked().toMutableMap()
        exports.forEach { incoming ->
            if (!isSafeStorageId(incoming.session.id)) {
                throw StoredRecordValidationException("Unsafe session id")
            }
            val merged = working[incoming.session.id]
                ?.let { mergeSessionExports(it, incoming) }
                ?: incoming.normalizedServerDiaryLineage()
            working[incoming.session.id] = merged
        }
    }

    /**
     * Atomically applies an optimistic diary edit. A missing base revision is accepted only for
     * legacy revision-zero records; once versioning starts every browser must send what it read.
     */
    fun updateDiary(
        sessionId: String,
        baseDiaryRevision: Long?,
        update: (SessionExport) -> SessionExport
    ): SessionExport? = lock.write {
        if (!isSafeStorageId(sessionId)) return@write null
        val sessions = loadUnlocked()
        val existing = sessions[sessionId] ?: return@write null
        val expected = baseDiaryRevision ?: 0L
        if (expected != existing.diaryRevision) {
            throw StoredRecordConflictException(
                "Diary changed since it was opened; reload session '$sessionId' before saving"
            )
        }
        if (existing.diaryRevision == Long.MAX_VALUE) {
            throw StoredRecordConflictException("Diary revision limit reached for '$sessionId'")
        }
        val nextRevision = existing.diaryRevision + 1L
        val updated = update(existing).copy(
            diaryRevision = nextRevision,
            diaryBaseRevision = nextRevision
        )
        if (!existing.hasSameRawEvidenceForUpdate(updated) ||
            existing.corrections != updated.corrections
        ) {
            throw StoredRecordValidationException(
                "Diary update changed immutable session evidence or correction history"
            )
        }
        val file = File(directory, "$sessionId.json")
        writeDurableAtomically(
            file,
            BadWatchJson.encodeToString(SessionExport.serializer(), updated)
        )
        sessions[sessionId] = updated
        updated
    }

    fun all(): List<SessionExport> = lock.write {
        loadUnlocked().values.sortedByDescending { it.session.startedAtMillis }
    }

    fun find(sessionId: String): SessionExport? = lock.write { loadUnlocked()[sessionId] }

    fun delete(sessionId: String): Boolean = lock.write {
        if (!isSafeStorageId(sessionId)) return@write false
        val sessions = loadUnlocked()
        if (sessionId !in sessions) return@write false
        val file = File(directory, "$sessionId.json")
        if (file.exists() && !file.delete()) return@write false
        sessions.remove(sessionId)
        true
    }

    private fun loadUnlocked(): MutableMap<String, SessionExport> {
        cache?.let { return it }
        val loaded = recoverStoredJsonRecords(
            directory = directory,
            decode = { text ->
                BadWatchJson.decodeFromString(SessionExport.serializer(), text)
            },
            belongsToDestination = { file, export ->
                isSafeStorageId(export.session.id) &&
                    file.name == "${export.session.id}.json"
            }
        ).associate { (_, export) -> export.session.id to export }
            .toMutableMap()
        cache = loaded
        return loaded
    }

    private data class PlannedSessionWrite(
        val export: SessionExport,
        val result: StoreResult
    )
}

private fun SessionExport.hasSameRawEvidenceForUpdate(other: SessionExport): Boolean =
    copy(
        context = com.badwatch.core.sync.SessionContext(),
        report = com.badwatch.core.sync.PostSessionReport(),
        corrections = com.badwatch.core.sync.SessionCorrections(),
        diaryRevision = 0L,
        diaryBaseRevision = null
    ) == other.copy(
        context = com.badwatch.core.sync.SessionContext(),
        report = com.badwatch.core.sync.PostSessionReport(),
        corrections = com.badwatch.core.sync.SessionCorrections(),
        diaryRevision = 0L,
        diaryBaseRevision = null
    )

private fun SessionExport.normalizedServerDiaryLineage(): SessionExport =
    copy(diaryBaseRevision = diaryRevision)

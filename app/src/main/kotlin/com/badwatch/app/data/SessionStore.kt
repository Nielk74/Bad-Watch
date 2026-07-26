package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class SessionStore(
    private val directory: File,
    private val onSessionsChanged: () -> Unit = {},
    private val atomicWriter: (File, String) -> Unit = ::writeDurableAtomically
) {

    private val _sessions = MutableStateFlow<List<StoredSession>>(emptyList())
    val sessions: StateFlow<List<StoredSession>> = _sessions.asStateFlow()
    /** Serializes payload and marker transitions so two reviews cannot race on one `.tmp`. */
    private val mutex = Mutex()

    suspend fun refresh(): List<StoredSession> = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val loaded = loadAll()
            _sessions.value = loaded
            loaded
        }
    }

    suspend fun save(export: SessionExport): StoredSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val file = File(directory, fileNameFor(export))
            // A repeated stop after a binder retry or crash-after-save returns the exact durable
            // export instead of replacing it or adding a second history row.
            readOrNull(file)?.let { existing ->
                _sessions.value = (_sessions.value.filterNot {
                    it.export.session.id == existing.export.session.id
                } + existing).sortedByDescending { it.export.session.startedAtMillis }
                return@withLock existing
            }
            val payloadText = BadWatchJson.encodeToString(SessionExport.serializer(), export)
            atomicWriter(file, payloadText)
            val stored = StoredSession(
                file = file,
                export = export,
                synced = false,
                syncPayloadFingerprint = payloadFingerprint(payloadText)
            )
            _sessions.value = loadAll()
            notifySessionsChanged()
            stored
        }
    }

    /**
     * Replaces the mutable diary/review envelope for an existing stable session.
     *
     * Unlike [save], this is an explicit edit operation. It keeps the same raw session ID
     * and start boundary, atomically replaces the JSON, and clears the sync marker so the
     * revised envelope is uploaded again.
     */
    suspend fun update(export: SessionExport): StoredSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val existing = findUnlocked(export.session.id)
            updateUnlocked(existing, export)
        }
    }

    /**
     * Applies a diary/correction transform to the latest durable envelope under the store lock.
     *
     * Callers intentionally provide a transform rather than a prebuilt copy: an in-flight diary
     * save can therefore never put back an older correction list (or vice versa). Raw detector
     * evidence and capture provenance are immutable on this path.
     */
    suspend fun mutateReview(
        sessionId: String,
        transform: (SessionExport) -> SessionExport
    ): StoredSession = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val existing = findUnlocked(sessionId)
            val revised = transform(existing.export)
            require(revised.session.id == sessionId) { "A review cannot change the session id" }
            require(revised.schemaVersion == existing.export.schemaVersion) {
                "A review cannot change the session schema"
            }
            require(revised.deviceId == existing.export.deviceId) {
                "A review cannot change device provenance"
            }
            require(revised.appVersion == existing.export.appVersion) {
                "A review cannot change app provenance"
            }
            require(revised.profile == existing.export.profile) {
                "A review cannot change the recorded player profile"
            }
            require(revised.session == existing.export.session) {
                "A review cannot replace raw session evidence"
            }
            require(revised.rallyProfile == existing.export.rallyProfile) {
                "A review cannot replace the raw rally profile"
            }
            require(revised.notes == existing.export.notes) {
                "A review cannot replace legacy extension metadata"
            }
            val diaryChanged = revised.context != existing.export.context ||
                revised.report != existing.export.report
            if (diaryChanged) {
                require(existing.export.diaryRevision < Long.MAX_VALUE) {
                    "Diary revision is exhausted"
                }
                require(revised.diaryRevision == existing.export.diaryRevision + 1L) {
                    "A diary edit must advance exactly one revision"
                }
                require(
                    revised.diaryBaseRevision ==
                        (existing.export.diaryBaseRevision ?: existing.export.diaryRevision)
                ) { "Offline diary edits must preserve their acknowledged base revision" }
            } else {
                require(revised.diaryRevision == existing.export.diaryRevision) {
                    "A correction-only edit cannot change the diary revision"
                }
                require(revised.diaryBaseRevision == existing.export.diaryBaseRevision) {
                    "A correction-only edit cannot change diary lineage"
                }
            }
            require(
                revised.corrections.hitRevisions.take(
                    existing.export.corrections.hitRevisions.size
                ) == existing.export.corrections.hitRevisions
            ) { "Hit correction history is append-only" }
            require(
                revised.corrections.trimRevisions.take(
                    existing.export.corrections.trimRevisions.size
                ) == existing.export.corrections.trimRevisions
            ) { "Trim correction history is append-only" }
            updateUnlocked(existing, revised)
        }
    }

    suspend fun findById(sessionId: String): StoredSession? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prepareDirectory()
                loadAll().firstOrNull { it.export.session.id == sessionId }
            }
        }

    suspend fun unsynced(): List<StoredSession> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prepareDirectory()
                loadAll().filter { !it.synced && it.syncRejection == null }
            }
        }

    /**
     * Records that the server accepted these sessions.
     *
     * Sync state lives in a sibling marker file rather than inside the JSON, so the synced
     * payload stays byte-identical to what was uploaded and re-uploads are idempotent.
     */
    suspend fun markSynced(sessionIds: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val ids = sessionIds.toSet()
            loadAll()
                .filter { it.export.session.id in ids }
                .forEach { stored ->
                    normalizeLineageAndMarkAccepted(stored)
                }
            _sessions.value = loadAll()
            Unit
        }
    }

    /** Quarantines exact payloads the server rejected, retaining a durable player-facing reason. */
    suspend fun markRejected(rejections: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            loadAll()
                .filter { it.export.session.id in rejections }
                .forEach { stored ->
                    writeRejectedSyncMarker(
                        payloadFile = stored.file,
                        payloadFingerprint = stored.syncPayloadFingerprint,
                        reason = rejections.getValue(stored.export.session.id),
                        writer = atomicWriter
                    )
                }
            _sessions.value = loadAll()
            Unit
        }
    }

    /**
     * Applies one server response only to the exact records included in that upload.
     *
     * If the player edits a session while the request is in flight, equality fails and the
     * stale acknowledgement is ignored. Accepted wins over rejected for a contradictory ID.
     */
    suspend fun applySyncResponse(
        uploaded: Collection<StoredSession>,
        response: SyncResponse
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val uploadedById = uploaded.associateBy { it.export.session.id }
            val accepted = response.accepted.toSet()
            loadAll().forEach { current ->
                val id = current.export.session.id
                val sent = uploadedById[id] ?: return@forEach
                if (current.export != sent.export) return@forEach
                when {
                    id in accepted -> normalizeLineageAndMarkAccepted(current)
                    id in response.rejected -> writeRejectedSyncMarker(
                        payloadFile = current.file,
                        payloadFingerprint = sent.syncPayloadFingerprint,
                        reason = response.rejected.getValue(id),
                        writer = atomicWriter
                    )
                }
            }
            _sessions.value = loadAll()
            Unit
        }
    }

    suspend fun delete(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val stored = loadAll().firstOrNull { it.export.session.id == sessionId }
            val removed = stored?.file?.delete() == true
            if (removed) {
                clearStoredSyncState(stored.file, requireSuccess = false)
            }
            _sessions.value = loadAll()
            if (removed) notifySessionsChanged()
            Unit
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var removedStoredSession = false
            directory.listFiles()?.forEach { file ->
                if (file.extension == "json" && file.delete()) {
                    removedStoredSession = true
                    clearStoredSyncState(file, requireSuccess = false)
                } else if (file.extension != "json") {
                    file.delete()
                }
            }
            _sessions.value = loadAll()
            if (removedStoredSession) notifySessionsChanged()
            Unit
        }
    }

    /** The full corpus as one envelope-ready list, for manual export via `adb pull` or share. */
    suspend fun exportAllJson(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val all = loadAll().map { it.export }
            BadWatchJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(SessionExport.serializer()),
                all
            )
        }
    }

    private fun findUnlocked(sessionId: String): StoredSession =
        loadAll().firstOrNull { it.export.session.id == sessionId }
            ?: throw IllegalArgumentException("Unknown session $sessionId")

    private fun updateUnlocked(existing: StoredSession, export: SessionExport): StoredSession {
        require(export.session.startedAtMillis == existing.export.session.startedAtMillis) {
            "A session update cannot change its stable start boundary"
        }
        if (existing.export == export) return existing

        // Make a changed payload pending before replacement. If the write then fails, an
        // unchanged old export may be retried, which is safer than silently marking new data
        // as already synced.
        clearStoredSyncState(existing.file, requireSuccess = true)
        val payloadText = BadWatchJson.encodeToString(SessionExport.serializer(), export)
        atomicWriter(existing.file, payloadText)
        val stored = StoredSession(
            file = existing.file,
            export = export,
            synced = false,
            syncPayloadFingerprint = payloadFingerprint(payloadText)
        )
        _sessions.value = loadAll()
        notifySessionsChanged()
        return stored
    }

    /**
     * Server acceptance turns the local branch into an acknowledged head. Persist the normalized
     * envelope first, then fingerprint those exact bytes in the marker. A crash between the two
     * leaves a safe pending head whose idempotent retry will be accepted again.
     */
    private fun normalizeLineageAndMarkAccepted(stored: StoredSession) {
        val normalized = stored.export.copy(
            diaryBaseRevision = stored.export.diaryRevision
        )
        val payloadText = BadWatchJson.encodeToString(SessionExport.serializer(), normalized)
        if (normalized != stored.export) {
            atomicWriter(stored.file, payloadText)
        }
        writeAcceptedSyncMarker(
            payloadFile = stored.file,
            payloadFingerprint = payloadFingerprint(payloadText),
            writer = atomicWriter
        )
    }

    private fun readOrNull(file: File): StoredSession? = runCatching {
        val payloadText = file.readText()
        val export = BadWatchJson.decodeFromString(SessionExport.serializer(), payloadText)
        val syncState = readStoredSyncState(file, payloadText)
        StoredSession(
            file = file,
            export = export,
            synced = syncState.synced,
            syncRejection = syncState.rejection,
            syncPayloadFingerprint = payloadFingerprint(payloadText)
        )
    }.getOrNull()

    private fun prepareDirectory() {
        recoverAndQuarantineJsonPayloads(
            directory = directory,
            validatePayload = { file ->
                val text = runCatching { file.readText() }.getOrNull()
                    ?: return@recoverAndQuarantineJsonPayloads PayloadValidation.Unreadable
                if (runCatching {
                        BadWatchJson.decodeFromString(SessionExport.serializer(), text)
                    }.isSuccess
                ) {
                    PayloadValidation.Valid
                } else {
                    PayloadValidation.Invalid
                }
            },
            onPayloadIdentityReset = { payload ->
                clearStoredSyncState(payload, requireSuccess = false)
            }
        )
    }

    private fun loadAll(): List<StoredSession> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull(::readOrNull)
            ?.sortedByDescending { it.export.session.startedAtMillis }
            .orEmpty()

    private fun notifySessionsChanged() {
        // Complication refresh is a best-effort observer; storage must remain authoritative
        // even if a platform callback is temporarily unavailable.
        runCatching(onSessionsChanged)
    }

    private fun fileNameFor(export: SessionExport): String =
        "${export.session.startedAtMillis}-${export.session.id}.json"
}

data class StoredSession(
    val file: File,
    val export: SessionExport,
    /** Existing UI compatibility: true only after server acceptance. */
    val synced: Boolean,
    /** Null unless this exact unchanged payload was explicitly rejected by the server. */
    val syncRejection: SyncRejection? = null,
    /** Fingerprint captured with this immutable store snapshot; used for race-safe acks. */
    val syncPayloadFingerprint: String = ""
) {
    val rejected: Boolean get() = !synced && syncRejection != null
}

package com.badwatch.app.data

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureExport
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
 * Stores labelled capture runs, mirroring [SessionStore].
 *
 * Kept separate from sessions because the two have very different lifecycles: sessions are
 * the product, captures are development data that gets pulled off the watch and deleted.
 * Mixing them would mean a training run bloating the player's session history.
 */
class CaptureStore(
    private val directory: File,
    private val atomicWriter: (File, String) -> Unit = ::writeDurableAtomically
) {

    private val _captures = MutableStateFlow<List<StoredCapture>>(emptyList())
    val captures: StateFlow<List<StoredCapture>> = _captures.asStateFlow()
    private val mutex = Mutex()

    suspend fun refresh(): List<StoredCapture> = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val loaded = loadAll()
            _captures.value = loaded
            loaded
        }
    }

    suspend fun save(export: CaptureExport): StoredCapture = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val file = File(
                directory,
                "${export.capture.startedAtMillis}-${export.capture.label.name}-${export.capture.id}.json"
            )
            readOrNull(file)?.let { existing ->
                _captures.value = loadAll()
                return@withLock existing
            }
            val payloadText = BadWatchJson.encodeToString(CaptureExport.serializer(), export)
            atomicWriter(file, payloadText)
            val stored = StoredCapture(
                file = file,
                export = export,
                synced = false,
                syncPayloadFingerprint = payloadFingerprint(payloadText)
            )
            _captures.value = loadAll()
            stored
        }
    }

    suspend fun unsynced(): List<StoredCapture> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prepareDirectory()
                loadAll().filter { !it.synced && it.syncRejection == null }
            }
        }

    suspend fun markSynced(captureIds: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val ids = captureIds.toSet()
            loadAll()
                .filter { it.export.capture.id in ids }
                .forEach { stored ->
                    writeAcceptedSyncMarker(
                        stored.file,
                        stored.syncPayloadFingerprint,
                        atomicWriter
                    )
                }
            _captures.value = loadAll()
            Unit
        }
    }

    suspend fun markRejected(rejections: Map<String, String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            loadAll()
                .filter { it.export.capture.id in rejections }
                .forEach { stored ->
                    writeRejectedSyncMarker(
                        payloadFile = stored.file,
                        payloadFingerprint = stored.syncPayloadFingerprint,
                        reason = rejections.getValue(stored.export.capture.id),
                        writer = atomicWriter
                    )
                }
            _captures.value = loadAll()
            Unit
        }
    }

    /** Applies an acknowledgement only while the immutable capture still matches its upload. */
    suspend fun applySyncResponse(
        uploaded: Collection<StoredCapture>,
        response: SyncResponse
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val uploadedById = uploaded.associateBy { it.export.capture.id }
            val accepted = response.accepted.toSet()
            loadAll().forEach { current ->
                val id = current.export.capture.id
                val sent = uploadedById[id] ?: return@forEach
                if (current.export != sent.export) return@forEach
                when {
                    id in accepted -> writeAcceptedSyncMarker(
                        current.file,
                        sent.syncPayloadFingerprint,
                        atomicWriter
                    )
                    id in response.rejected -> writeRejectedSyncMarker(
                        payloadFile = current.file,
                        payloadFingerprint = sent.syncPayloadFingerprint,
                        reason = response.rejected.getValue(id),
                        writer = atomicWriter
                    )
                }
            }
            _captures.value = loadAll()
            Unit
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
            _captures.value = emptyList()
            Unit
        }
    }

    /** Total labelled swings on the watch — the number that matters for dataset progress. */
    suspend fun totalSwings(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareDirectory()
            val loaded = loadAll()
            _captures.value = loaded
            loaded.sumOf { it.export.capture.swingCount }
        }
    }

    private fun readOrNull(file: File): StoredCapture? = runCatching {
        val payloadText = file.readText()
        val export = BadWatchJson.decodeFromString(CaptureExport.serializer(), payloadText)
        val syncState = readStoredSyncState(file, payloadText)
        StoredCapture(
            file = file,
            export = export,
            synced = syncState.synced,
            syncRejection = syncState.rejection,
            syncPayloadFingerprint = payloadFingerprint(payloadText)
        )
    }.getOrNull()

    private fun loadAll(): List<StoredCapture> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull(::readOrNull)
            ?.sortedByDescending { it.export.capture.startedAtMillis }
            .orEmpty()

    private fun prepareDirectory() {
        recoverAndQuarantineJsonPayloads(
            directory = directory,
            validatePayload = { file ->
                val text = runCatching { file.readText() }.getOrNull()
                    ?: return@recoverAndQuarantineJsonPayloads PayloadValidation.Unreadable
                if (runCatching {
                        BadWatchJson.decodeFromString(CaptureExport.serializer(), text)
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
}

data class StoredCapture(
    val file: File,
    val export: CaptureExport,
    /** Existing UI compatibility: true only after server acceptance. */
    val synced: Boolean,
    val syncRejection: SyncRejection? = null,
    val syncPayloadFingerprint: String = ""
) {
    val rejected: Boolean get() = !synced && syncRejection != null
}

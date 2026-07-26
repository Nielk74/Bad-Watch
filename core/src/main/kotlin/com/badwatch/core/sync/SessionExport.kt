package com.badwatch.core.sync

import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.TrainingSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire contract between the watch and the dashboard server.
 *
 * Both sides depend on `:core`, so this is the *same* Kotlin type on the watch and in the
 * server — there is no hand-maintained schema to drift. [SCHEMA_VERSION] is bumped when a
 * breaking change lands; the server rejects envelopes it does not understand rather than
 * silently misreading them.
 */
@Serializable
data class SessionExport(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Stable per-install identifier. Not a user account — sync needs no login. */
    val deviceId: String,
    val appVersion: String,
    val profile: PlayerProfile,
    val session: TrainingSession,
    val rallyProfile: RallyProfile,
    /**
     * Legacy extension metadata retained for schema-1 compatibility. New diary data belongs
     * in [context] and [report], where its meaning and evidence source are explicit.
     */
    val notes: Map<String, String> = emptyMap(),
    val context: SessionContext = SessionContext(),
    val report: PostSessionReport = PostSessionReport(),
    /** Append-only edits; [session] and [rallyProfile] always remain the original output. */
    val corrections: SessionCorrections = SessionCorrections(),
    /**
     * Monotonic optimistic-concurrency token for the complete [context]/[report] document.
     *
     * Schema-1 payloads written before diary editing default to zero. Every diary save copies
     * the current value and increments it. A server can therefore merge a stale watch upload or
     * archive without allowing its older diary snapshot to replace a newer reviewed one.
     */
    val diaryRevision: Long = 0L,
    /**
     * Server diary revision acknowledged before this local branch was edited.
     *
     * A watch can make several offline edits while preserving this base. The server accepts a
     * differing newer diary only when the base still equals its current revision, which prevents
     * a stale branch from leapfrogging edits made in the dashboard. Null is retained solely so
     * stored schema-1 payloads written before lineage tracking continue to decode safely.
     */
    val diaryBaseRevision: Long? = null
) {
    init {
        require(diaryRevision >= 0L) { "Diary revision must not be negative" }
        require(diaryBaseRevision == null || diaryBaseRevision >= 0L) {
            "Diary base revision must not be negative"
        }
        require(diaryBaseRevision == null || diaryBaseRevision <= diaryRevision) {
            "Diary base revision must not exceed the diary revision"
        }
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * A batch upload. The watch may have accumulated several sessions while offline.
 */
@Serializable
data class SyncEnvelope(
    val schemaVersion: Int = SessionExport.SCHEMA_VERSION,
    val sessions: List<SessionExport>
)

/**
 * Server acknowledgement. [accepted] lets the watch mark exactly those sessions as synced,
 * so a partial failure does not force a full re-upload.
 */
@Serializable
data class SyncResponse(
    val accepted: List<String> = emptyList(),
    val rejected: Map<String, String> = emptyMap()
)

/**
 * Shared JSON configuration. `encodeDefaults` is on so the server always sees an explicit
 * schema version, and `ignoreUnknownKeys` lets an older watch talk to a newer server.
 */
val BadWatchJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

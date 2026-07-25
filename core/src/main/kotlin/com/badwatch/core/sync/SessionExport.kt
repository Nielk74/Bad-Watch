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
    /** Optional free-text context: hall, shuttle grade, opponent, string tension. */
    val notes: Map<String, String> = emptyMap()
) {
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

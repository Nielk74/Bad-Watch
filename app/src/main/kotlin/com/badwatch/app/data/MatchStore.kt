package com.badwatch.app.data

import com.badwatch.core.match.MatchLog
import com.badwatch.core.sync.BadWatchJson
import java.io.File

/**
 * One atomic document for the match currently on the scoreboard.
 *
 * Match history and cloud export are intentionally out of scope here. This file exists so a
 * score cannot disappear when Wear recreates the Activity or kills and later restores the
 * process. The action log, rather than derived points, is the durable source of truth.
 */
interface MatchPersistence {
    fun load(): MatchLoadResult
    fun save(log: MatchLog)
    fun clear()
}

class MatchStore(private val file: File) : MatchPersistence {

    override fun load(): MatchLoadResult {
        if (!file.exists()) return MatchLoadResult.Empty
        return runCatching {
            MatchLoadResult.Loaded(
                BadWatchJson.decodeFromString(MatchLog.serializer(), file.readText())
            )
        }.getOrElse { error ->
            MatchLoadResult.Corrupt(error.message ?: "Invalid saved match")
        }
    }

    override fun save(log: MatchLog) {
        writeDurableAtomically(
            file = file,
            text = BadWatchJson.encodeToString(MatchLog.serializer(), log)
        )
    }

    override fun clear() {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        check(!file.exists() || file.delete()) { "Could not remove saved match" }
        check(!temporary.exists() || temporary.delete()) { "Could not remove temporary match" }
    }
}

sealed interface MatchLoadResult {
    data object Empty : MatchLoadResult
    data class Loaded(val log: MatchLog) : MatchLoadResult
    data class Corrupt(val reason: String) : MatchLoadResult
}

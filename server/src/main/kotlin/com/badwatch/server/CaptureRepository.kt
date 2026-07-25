package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureExport
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Stores labelled capture drills.
 *
 * Unlike sessions these are not cached in memory: capture files carry raw sample windows and
 * can run to megabytes each, so the corpus is read from disk on demand. The training
 * pipeline in `tools/` reads the same directory directly.
 */
class CaptureRepository(private val directory: File) {

    private val lock = ReentrantReadWriteLock()

    fun save(export: CaptureExport): Boolean = lock.write {
        directory.mkdirs()
        val file = File(
            directory,
            "${export.capture.startedAtMillis}-${export.capture.label.name}-${export.capture.id}.json"
        )
        val isNew = !file.exists()
        val temp = File(directory, "${file.name}.tmp")
        temp.writeText(BadWatchJson.encodeToString(CaptureExport.serializer(), export))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        isNew
    }

    fun all(): List<CaptureExport> = lock.read {
        directory.mkdirs()
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    BadWatchJson.decodeFromString(CaptureExport.serializer(), file.readText())
                }.getOrNull()
            }
            ?.sortedByDescending { it.capture.startedAtMillis }
            .orEmpty()
    }

    /**
     * Dataset progress. This is the number that tells you whether Phase 2 is viable yet —
     * a usable bootstrap set needs on the order of a few hundred swings per stroke, from
     * more than one player.
     */
    fun summary(): CaptureSummary {
        val captures = all()
        val perLabel = captures
            .groupBy { it.capture.label.name }
            .mapValues { (_, group) -> group.sumOf { it.capture.swingCount } }
            .toSortedMap()
        return CaptureSummary(
            drillCount = captures.size,
            totalSwings = perLabel.values.sum(),
            contributingDevices = captures.map { it.deviceId }.distinct().size,
            swingsPerLabel = perLabel.map { (label, count) -> LabelCount(label, count) }
        )
    }
}

@Serializable
data class CaptureSummary(
    /** Number of capture drills recorded. */
    val drillCount: Int,
    val totalSwings: Int,
    val contributingDevices: Int,
    val swingsPerLabel: List<LabelCount>
)

@Serializable
data class LabelCount(val label: String, val swings: Int)

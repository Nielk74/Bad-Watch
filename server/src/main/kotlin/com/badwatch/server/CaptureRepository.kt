package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.eval.ClassifierEvaluation
import com.badwatch.core.eval.ClassifierEvaluator
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.isEligibleForModelTrainingUpload
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * Stores labelled capture drills.
 *
 * Unlike sessions these are not cached in memory: capture files carry raw sample windows and
 * can run to megabytes each, so the corpus is read from disk on demand. The training
 * pipeline in `tools/` reads the same directory directly.
 */
class CaptureRepository(private val directory: File) {

    // Disk reads also perform orphan recovery and quarantine moves, so they are serialized with
    // writes rather than sharing a read lock during filesystem mutation.
    private val lock = ReentrantReadWriteLock()

    fun save(export: CaptureExport): Boolean = upsert(export) == StoreResult.Created

    fun upsert(export: CaptureExport): StoreResult = lock.write {
        if (!isSafeStorageId(export.capture.id)) {
            throw StoredRecordValidationException("Unsafe capture id")
        }
        directory.mkdirs()
        val existingFiles = storedFilesUnlocked()
            .filter { (_, stored) -> stored.capture.id == export.capture.id }
        val existing = existingFiles.firstOrNull()?.second
        if (existingFiles.any { (_, stored) -> stored != export }) {
            throw StoredRecordValidationException(
                "Capture '${export.capture.id}' conflicts with immutable recorded evidence"
            )
        }
        if (existing == export && existingFiles.size == 1) return@write StoreResult.Unchanged
        val file = File(directory, export.storageFileName())
        writeDurableAtomically(
            file,
            BadWatchJson.encodeToString(CaptureExport.serializer(), export)
        )
        // A restored record may legitimately correct its timestamp or label, both of which
        // affect the historical filename. Remove stale same-id files only after the new file
        // is safely in place.
        existingFiles.map { it.first }.filter { it != file }.forEach { it.delete() }
        when {
            existing == null -> StoreResult.Created
            existing == export -> StoreResult.Unchanged
            else -> error("Conflicting immutable capture reached the write path")
        }
    }

    /** Validates immutable-id compatibility without writing, for archive preflight. */
    fun validateUpserts(exports: List<CaptureExport>) = lock.write {
        val stored = storedFilesUnlocked().groupBy { (_, export) -> export.capture.id }
        exports.forEach { incoming ->
            if (!isSafeStorageId(incoming.capture.id)) {
                throw StoredRecordValidationException("Unsafe capture id")
            }
            if (stored[incoming.capture.id].orEmpty().any { (_, existing) ->
                    existing != incoming
                }
            ) {
                throw StoredRecordValidationException(
                    "Capture '${incoming.capture.id}' conflicts with immutable recorded evidence"
                )
            }
        }
    }

    fun all(): List<CaptureExport> = lock.write {
        directory.mkdirs()
        storedFilesUnlocked()
            .map { it.second }
            .distinctBy { it.capture.id }
            .sortedByDescending { it.capture.startedAtMillis }
    }

    fun find(captureId: String): CaptureExport? = lock.write {
        storedFilesUnlocked().firstOrNull { (_, export) ->
            export.capture.id == captureId
        }?.second
    }

    /** Raw windows that carried explicit recording-time consent and complete provenance. */
    fun trainingEligible(): List<CaptureExport> = all()
        .filter { it.isEligibleForModelTrainingUpload }

    /**
     * Scores the shipped rule-based classifier against the collected ground truth.
     *
     * Evaluated per handedness, because the classifier mirrors its pronation feature and
     * pooling both hands would blur the one stroke that discriminator exists for.
     */
    fun evaluateClassifier(): ClassifierEvaluation {
        val captures = trainingEligible()
        if (captures.isEmpty()) return ClassifierEvaluation.EMPTY
        // Group by handedness, evaluate with a matching classifier, then merge the swings
        // that each classifier saw. Simplest correct approach: evaluate the dominant group.
        val byHandedness = captures.groupBy { it.profile.handedness }
        val (handedness, group) = byHandedness.maxByOrNull { (_, list) ->
            list.sumOf { it.capture.swingCount }
        } ?: return ClassifierEvaluation.EMPTY

        return ClassifierEvaluator(
            com.badwatch.core.classifier.ShotClassifier(handedness = handedness)
        ).evaluate(group.map { it.capture })
    }

    /**
     * Dataset progress. This is the number that tells you whether Phase 2 is viable yet —
     * a usable bootstrap set needs on the order of a few hundred swings per stroke, from
     * more than one player.
     */
    fun summary(): CaptureSummary {
        val captures = trainingEligible()
        val perLabel = captures
            .groupBy { it.capture.label.name }
            .mapValues { (_, group) -> group.sumOf { it.capture.swingCount } }
            .toSortedMap()
        return CaptureSummary(
            drillCount = captures.size,
            totalSwings = perLabel.values.sum(),
            contributingDevices = captures.map { it.deviceId }.distinct().size,
            contributingParticipants = captures.mapNotNull { it.participantId }.distinct().size,
            swingsPerLabel = perLabel.map { (label, count) -> LabelCount(label, count) }
        )
    }

    private fun storedFilesUnlocked(): List<Pair<File, CaptureExport>> {
        return recoverStoredJsonRecords(
            directory = directory,
            decode = { text ->
                BadWatchJson.decodeFromString(CaptureExport.serializer(), text)
            },
            belongsToDestination = { file, export ->
                isSafeStorageId(export.capture.id) && file.name == export.storageFileName()
            }
        )
    }
}

private fun CaptureExport.storageFileName(): String =
    "${capture.startedAtMillis}-${capture.label.name}-${capture.id}.json"

@Serializable
data class CaptureSummary(
    /** Number of capture drills recorded. */
    val drillCount: Int,
    val totalSwings: Int,
    val contributingDevices: Int,
    val contributingParticipants: Int = 0,
    val swingsPerLabel: List<LabelCount>
)

@Serializable
data class LabelCount(val label: String, val swings: Int)

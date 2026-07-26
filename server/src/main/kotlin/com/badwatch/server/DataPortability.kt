package com.badwatch.server

import com.badwatch.core.sync.BadWatchJson
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.effectiveMetrics
import com.badwatch.core.sync.isEligibleForModelTrainingUpload
import com.badwatch.core.sync.reviewedAnalysis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Instant

/** Lossless, self-describing backup of both user sessions and labelled capture drills. */
@Serializable
data class BadWatchArchive(
    val format: String,
    val archiveVersion: Int,
    val schemaVersion: Int,
    val sessions: List<SessionExport> = emptyList(),
    val captures: List<CaptureExport> = emptyList()
) {
    companion object {
        const val FORMAT = "bad-watch-archive"
        const val ARCHIVE_VERSION = 1
    }
}

@Serializable
data class RestoreCounts(
    val created: Int = 0,
    val replaced: Int = 0,
    val unchanged: Int = 0
)

@Serializable
data class ArchiveRestoreResponse(
    val sessions: RestoreCounts,
    val captures: RestoreCounts
)

/** Builds deterministic exports and validates a complete restore before the first write. */
object DataPortability {

    fun archive(
        sessions: List<SessionExport>,
        captures: List<CaptureExport>
    ): BadWatchArchive = BadWatchArchive(
        format = BadWatchArchive.FORMAT,
        archiveVersion = BadWatchArchive.ARCHIVE_VERSION,
        schemaVersion = SessionExport.SCHEMA_VERSION,
        sessions = sessions.sortedWith(
            compareBy<SessionExport>({ it.session.startedAtMillis }, { it.session.id })
        ),
        captures = captures.sortedWith(
            compareBy<CaptureExport>({ it.capture.startedAtMillis }, { it.capture.id })
        ).filter { it.isEligibleForModelTrainingUpload }
    )

    /**
     * Canonical object-key ordering makes repeated backups byte-for-byte identical. Array
     * order remains meaningful; [archive] has already normalized top-level record order.
     */
    fun encodeArchive(archive: BadWatchArchive): String {
        val element = BadWatchJson.encodeToJsonElement(BadWatchArchive.serializer(), archive)
        return BadWatchJson.encodeToString(JsonElement.serializer(), canonical(element)) + "\n"
    }

    fun validationErrors(archive: BadWatchArchive): List<String> = buildList {
        if (archive.format != BadWatchArchive.FORMAT) {
            add("Unsupported archive format '${archive.format}'")
        }
        if (archive.archiveVersion != BadWatchArchive.ARCHIVE_VERSION) {
            add("Unsupported archive version ${archive.archiveVersion}")
        }
        if (archive.schemaVersion != SessionExport.SCHEMA_VERSION) {
            add(
                "Unsupported session schema ${archive.schemaVersion}; " +
                    "this server speaks ${SessionExport.SCHEMA_VERSION}"
            )
        }

        duplicateValues(archive.sessions.map { it.session.id }).forEach { id ->
            add("Duplicate session id '$id'")
        }
        duplicateValues(archive.captures.map { it.capture.id }).forEach { id ->
            add("Duplicate capture id '$id'")
        }

        archive.sessions.forEachIndexed { index, export ->
            addAll(sessionValidationErrors(export, prefix = "Session ${index + 1}"))
        }

        archive.captures.forEachIndexed { index, export ->
            addAll(captureValidationErrors(export, prefix = "Capture ${index + 1}"))
        }
    }

    /**
     * Restore is merge/upsert, not deletion: new ids are added and existing ids are replaced
     * only when their payload changed. Semantic validation completes before either repository
     * is touched; individual files are then written through each repository's temp-file path.
     */
    fun restore(
        archive: BadWatchArchive,
        sessionRepository: SessionRepository,
        captureRepository: CaptureRepository
    ): ArchiveRestoreResponse {
        val errors = validationErrors(archive)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        // Detect immutable-id collisions and divergent edit histories before the first archive
        // record is written. I/O can still fail part-way through, but a retry is idempotent.
        sessionRepository.validateUpserts(archive.sessions)
        captureRepository.validateUpserts(archive.captures)
        return ArchiveRestoreResponse(
            sessions = sessionRepository.upsertAll(archive.sessions).toRestoreCounts(),
            captures = archive.captures.map(captureRepository::upsert).toRestoreCounts()
        )
    }

    internal fun sessionValidationErrors(
        export: SessionExport,
        prefix: String = "Session '${export.session.id}'"
    ): List<String> = buildList {
        if (export.schemaVersion != SessionExport.SCHEMA_VERSION) {
            add("$prefix has unsupported schema ${export.schemaVersion}")
        }
        if (!isSafeStorageId(export.session.id)) add("$prefix has an unsafe id")
        if (export.session.endedAtMillis < export.session.startedAtMillis) {
            add("$prefix ends before it starts")
        }
        if (export.session.summary.durationMillis < 0L) {
            add("$prefix has a negative duration")
        }
        duplicateValues(export.session.shots.map { it.id }).forEach { id ->
            add("$prefix repeats detected-hit id '$id'")
        }
        val revisionIds = export.corrections.hitRevisions.map { it.provenance.revisionId } +
            export.corrections.trimRevisions.map { it.provenance.revisionId }
        duplicateValues(revisionIds).forEach { id ->
            add("$prefix repeats correction revision '$id'")
        }
    }

    internal fun captureValidationErrors(
        export: CaptureExport,
        prefix: String = "Capture '${export.capture.id}'"
    ): List<String> = buildList {
        if (export.schemaVersion != SessionExport.SCHEMA_VERSION) {
            add("$prefix has unsupported schema ${export.schemaVersion}")
        }
        if (!isSafeStorageId(export.capture.id)) add("$prefix has an unsafe id")
        if (export.capture.endedAtMillis < export.capture.startedAtMillis) {
            add("$prefix ends before it starts")
        }
        if (export.samplingRateHz !in 1..1_000) {
            add("$prefix has an invalid sampling rate")
        }
        duplicateValues(export.capture.swings.map { it.id }).forEach { id ->
            add("$prefix repeats labelled-swing id '$id'")
        }
        export.trainingUploadRejectionReason()?.let { reason -> add("$prefix $reason") }
    }

    private fun List<StoreResult>.toRestoreCounts(): RestoreCounts = RestoreCounts(
        created = count { it == StoreResult.Created },
        replaced = count { it == StoreResult.Replaced },
        unchanged = count { it == StoreResult.Unchanged }
    )

    private fun duplicateValues(values: List<String>): List<String> = values
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associateTo(linkedMapOf()) { (key, value) -> key to canonical(value) }
        )
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }
}

/** Exact server-side reason used by upload and restore responses. */
internal fun CaptureExport.trainingUploadRejectionReason(): String? = when {
    dataUse != CaptureDataUse.SelfHostedModelTraining ->
        "was not consented for self-hosted model training"
    participantId.isNullOrBlank() -> "has no participant id"
    protocol == null -> "has no collection protocol"
    else -> null
}

/** Stable, spreadsheet-friendly view. JSON archives remain the lossless restore format. */
object SessionCsvExporter {

    private val columns = listOf(
        "session_id",
        "started_at_utc",
        "ended_at_utc",
        "activity_mode",
        "comparison_tag",
        "completion",
        "recording_quality",
        "opponent",
        "partner",
        "hall",
        "goal",
        "diary_review_status",
        "racket",
        "string",
        "string_tension_lbs",
        "shoes",
        "shuttle_brand",
        "shuttle_speed",
        "temperature_celsius",
        "draft",
        "raw_duration_seconds",
        "effective_duration_seconds",
        "model_detected_hits",
        "raw_event_count",
        "corrected_detected_hits",
        "reported_missed_hits",
        "effective_hit_count",
        "detected_exchange_count",
        "estimated_active_seconds",
        "average_heart_rate_bpm",
        "max_heart_rate_bpm",
        "heart_rate_coverage",
        "rpe",
        "soreness",
        "soreness_reviewed",
        "notes",
        "correction_revisions",
        "device_id",
        "app_version"
    )

    fun encode(sessions: List<SessionExport>): String {
        val records = sessions
            .sortedWith(
                compareByDescending<SessionExport> { it.session.startedAtMillis }
                    .thenBy { it.session.id }
            )
            .map(::row)
        return (listOf(columns) + records)
            .joinToString(separator = "\r\n", postfix = "\r\n") { fields ->
                fields.joinToString(",", transform = ::escape)
            }
    }

    private fun row(export: SessionExport): List<String> {
        val session = export.session
        val reviewed = export.reviewedAnalysis()
        val rawSummary = session.summary
        val reviewedSummary = reviewed.session.summary
        val context = export.context
        val report = export.report
        val effective = export.effectiveMetrics()
        val soreness = report.soreness.joinToString("; ") { item ->
            val side = item.side.name.takeUnless { it == "Unspecified" }
                ?.let { humanize(it) + " " }
                .orEmpty()
            "$side${humanize(item.bodyArea.name)} ${item.severity}/10"
        }
        return listOf(
            session.id,
            Instant.ofEpochMilli(session.startedAtMillis).toString(),
            Instant.ofEpochMilli(session.endedAtMillis).toString(),
            context.activityMode.name,
            context.comparisonTag.orEmpty(),
            context.completion.name,
            context.recordingQuality.name,
            context.opponent.orEmpty(),
            context.partner.orEmpty(),
            context.hall.orEmpty(),
            context.goal.orEmpty(),
            context.diaryReviewStatus.name,
            context.equipment.racket.orEmpty(),
            context.equipment.string.orEmpty(),
            context.equipment.stringTensionLbs?.finiteString().orEmpty(),
            context.equipment.shoes.orEmpty(),
            context.conditions.shuttleBrand.orEmpty(),
            context.conditions.shuttleSpeed.orEmpty(),
            context.conditions.temperatureCelsius?.finiteString().orEmpty(),
            context.conditions.draft.name,
            seconds(rawSummary.durationMillis),
            seconds(effective.window.durationMillis),
            rawSummary.totalShots.toString(),
            effective.rawDetectedHitCount.toString(),
            effective.correctedDetectedHitCount.toString(),
            effective.reportedMissedHitCount.toString(),
            effective.effectiveHitCount.toString(),
            reviewed.rallyProfile.rallyCount.toString(),
            seconds(reviewed.rallyProfile.totalWorkMillis),
            reviewedSummary.averageHeartRate?.finiteString().orEmpty(),
            reviewedSummary.maxHeartRate?.finiteString().orEmpty(),
            reviewedSummary.heartRateCoverage.finiteString(),
            report.rpe?.toString().orEmpty(),
            soreness,
            report.sorenessReviewed.toString(),
            report.notes.orEmpty(),
            (export.corrections.hitRevisions.size +
                export.corrections.trimRevisions.size).toString(),
            export.deviceId,
            export.appVersion
        )
    }

    private fun seconds(millis: Long): String =
        BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()

    private fun Float.finiteString(): String = if (isFinite()) toString() else ""

    private fun humanize(value: String): String = value
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar { it.uppercase() }

    private fun escape(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (value.any { it == ',' || it == '\"' || it == '\r' || it == '\n' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }
}

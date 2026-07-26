package com.badwatch.server

import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionCorrections
import com.badwatch.core.sync.SessionExport

/**
 * Merges the only mutable parts of a session while treating detector/model output as immutable.
 *
 * Diary revisions are a monotonic document version. A differing newer diary is accepted only when
 * its base names the server revision it extends, so multiple offline edits can arrive together but
 * a stale branch cannot leapfrog an intervening browser edit. Correction arrays are append-only
 * logs whose order is authoritative, so only a prefix can be safely extended; divergent branches
 * are exposed as conflicts instead of choosing a winner and silently deleting review evidence.
 */
internal fun mergeSessionExports(
    stored: SessionExport,
    incoming: SessionExport
): SessionExport {
    if (!stored.hasSameRawEvidenceAs(incoming)) {
        throw StoredRecordValidationException(
            "Session '${incoming.session.id}' conflicts with immutable recorded evidence"
        )
    }

    val diary = mergeDiary(stored, incoming)
    val corrections = SessionCorrections(
        hitRevisions = mergeAppendOnlyHistory(
            stored.corrections.hitRevisions,
            incoming.corrections.hitRevisions,
            "hit-correction",
            incoming.session.id
        ),
        trimRevisions = mergeAppendOnlyHistory(
            stored.corrections.trimRevisions,
            incoming.corrections.trimRevisions,
            "trim-correction",
            incoming.session.id
        )
    )

    return stored.copy(
        context = diary.context,
        report = diary.report,
        corrections = corrections,
        diaryRevision = diary.revision,
        // Every server head is self-acknowledged. The watch applies the same normalization after
        // an accepted acknowledgement before fingerprinting the synced payload.
        diaryBaseRevision = diary.revision
    )
}

private data class MergedDiary(
    val context: SessionContext,
    val report: PostSessionReport,
    val revision: Long
)

private fun mergeDiary(stored: SessionExport, incoming: SessionExport): MergedDiary {
    val storedDiary = MergedDiary(stored.context, stored.report, stored.diaryRevision)
    val incomingDiary = MergedDiary(incoming.context, incoming.report, incoming.diaryRevision)
    return when {
        incomingDiary.revision < storedDiary.revision -> storedDiary
        incomingDiary.revision > storedDiary.revision &&
            incoming.diaryBaseRevision == storedDiary.revision -> incomingDiary
        incomingDiary.revision > storedDiary.revision -> throw StoredRecordConflictException(
            "Session '${incoming.session.id}' has a divergent diary branch based on " +
                "revision ${incoming.diaryBaseRevision ?: "legacy/unknown"}; " +
                "server revision is ${stored.diaryRevision}"
        )
        incomingDiary.context == storedDiary.context && incomingDiary.report == storedDiary.report ->
            storedDiary

        // Revision zero is the legacy schema-1 state. A populated legacy diary can upgrade an
        // empty record, while an empty/stale payload must never erase an already reviewed diary.
        storedDiary.revision == 0L && storedDiary.isEmpty() -> incomingDiary
        incomingDiary.revision == 0L && incomingDiary.isEmpty() -> storedDiary

        else -> throw StoredRecordConflictException(
            "Session '${incoming.session.id}' has divergent diary revision " +
                incoming.diaryRevision
        )
    }
}

private fun MergedDiary.isEmpty(): Boolean =
    context == SessionContext() && report == PostSessionReport()

private fun SessionExport.hasSameRawEvidenceAs(other: SessionExport): Boolean =
    copy(
        context = SessionContext(),
        report = PostSessionReport(),
        corrections = SessionCorrections(),
        diaryRevision = 0L,
        diaryBaseRevision = null
    ) == other.copy(
        context = SessionContext(),
        report = PostSessionReport(),
        corrections = SessionCorrections(),
        diaryRevision = 0L,
        diaryBaseRevision = null
    )

private fun <T> mergeAppendOnlyHistory(
    stored: List<T>,
    incoming: List<T>,
    label: String,
    sessionId: String
): List<T> {
    val sharedLength = minOf(stored.size, incoming.size)
    val sharesPrefix = (0 until sharedLength).all { index -> stored[index] == incoming[index] }
    if (!sharesPrefix) {
        throw StoredRecordConflictException(
            "Session '$sessionId' has divergent $label history"
        )
    }
    return if (incoming.size > stored.size) incoming else stored
}

package com.badwatch.app.domain

import com.badwatch.core.sync.PostSessionReport
import com.badwatch.core.sync.SessionContext
import com.badwatch.core.sync.SessionExport

/**
 * Replaces the complete diary document and advances its optimistic-concurrency token.
 * This must run inside [com.badwatch.app.data.SessionStore.mutateReview], against the latest
 * durable export, so an offline watch edit cannot reuse a stale server-synced revision.
 */
internal fun SessionExport.revisedDiary(
    context: SessionContext,
    report: PostSessionReport
): SessionExport {
    if (this.context == context && this.report == report) return this
    check(diaryRevision < Long.MAX_VALUE) { "Diary revision is exhausted" }
    return copy(
        context = context,
        report = report,
        diaryRevision = diaryRevision + 1L,
        // Null denotes a legacy/unacknowledged head. The first local edit branches from its
        // current revision; later offline edits retain that same base until server acceptance.
        diaryBaseRevision = diaryBaseRevision ?: diaryRevision
    )
}

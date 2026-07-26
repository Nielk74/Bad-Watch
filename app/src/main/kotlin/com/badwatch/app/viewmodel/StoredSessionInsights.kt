package com.badwatch.app.viewmodel

import com.badwatch.core.insight.Insight
import com.badwatch.core.insight.SessionInsightEngine
import com.badwatch.core.sync.RecordingQuality
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.reviewedAnalysis
import com.badwatch.core.sync.reviewedInsightBaseline

/** Deterministic historical-recap projection shared by open, diary-save, and correction flows. */
internal fun buildStoredSessionInsights(
    selected: SessionExport,
    history: Iterable<SessionExport>,
    insightEngine: SessionInsightEngine = SessionInsightEngine()
): List<Insight> {
    if (selected.context.recordingQuality == RecordingQuality.Partial ||
        selected.context.recordingQuality == RecordingQuality.Unusable
    ) {
        return emptyList()
    }
    val analysis = selected.reviewedAnalysis()
    return insightEngine.generate(
        session = analysis.session,
        rallyProfile = analysis.rallyProfile,
        baseline = selected.reviewedInsightBaseline(history)
    )
}

package com.badwatch.app.viewmodel

import com.badwatch.core.sync.ReviewedSessionAnalysis
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.reviewedAnalysis

/**
 * Memoises [reviewedAnalysis] per immutable export.
 *
 * The projection is pure but expensive: it sorts every shot, filters the whole heart-rate
 * trace, rebuilds the session summary and re-runs rally segmentation. Screens used to call it
 * from inside composable item bodies, so the cost was paid again on every recomposition and
 * every time a scrolled row rebound — the dominant cause of scroll jank on history-heavy
 * watches.
 *
 * [SessionExport] is an immutable data class and every review edit produces a new instance, so
 * the export itself is a sound cache key: an entry can never describe stale evidence. Lookup is
 * by identity first, which is the common case (the store hands the same instances to every
 * screen) and avoids a deep structural hash over the full shot list.
 */
class SessionAnalysisCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private val entries = object : LinkedHashMap<Key, ReviewedSessionAnalysis>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, ReviewedSessionAnalysis>?
        ): Boolean = size > maxEntries
    }

    /** Returns the reviewed view for [export], computing it only on a miss. */
    @Synchronized
    fun analysisFor(export: SessionExport): ReviewedSessionAnalysis =
        entries.getOrPut(Key(export)) { export.reviewedAnalysis() }

    @Synchronized
    fun clear() = entries.clear()

    /**
     * Identity-first cache key.
     *
     * Equal-but-distinct exports (a decode of the same bytes) merely miss and recompute once;
     * they can never collide onto a wrong entry, because equality still implies an identical
     * projection.
     */
    private class Key(val export: SessionExport) {
        override fun hashCode(): Int = System.identityHashCode(export)
        override fun equals(other: Any?): Boolean =
            other is Key && other.export === export
    }

    private companion object {
        /** Comfortably covers a full history screen plus the aggregate screens' working set. */
        const val DEFAULT_MAX_ENTRIES = 64
    }
}

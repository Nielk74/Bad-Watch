package com.badwatch.core.insight

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * One observation about a session.
 *
 * Every insight must be **falsifiable and evidenced**. [evidence] carries the number the
 * claim rests on, so a player can disagree with the interpretation while still trusting the
 * measurement — and so a wrong insight is debuggable rather than merely annoying.
 *
 * The previous version of this app generated "insights" like *"Swing variance is 62%. Focus
 * on repeatable arcs"* from raw gyroscope magnitude, with no grounding at all. The rule here
 * is the opposite: an insight is only produced when the underlying signal is one we actually
 * trust, and when there is enough of it. Silence is a valid, and frequently correct, output.
 */
@Serializable
data class Insight(
    val id: String,
    val headline: String,
    val detail: String,
    val severity: InsightSeverity,
    /** The measurement behind the claim, e.g. "1:3.8 estimated active:rest". */
    val evidence: String,
    /** Runtime-only structured values used to localize the same evidenced claim on-device. */
    @Transient val localizationArgs: Map<String, String> = emptyMap()
)

@Serializable
enum class InsightSeverity {
    /** Worth knowing. */
    Info,

    /** Stands out against this player's own history or against the sport's norms. */
    Notable,

    /** A directly observed caution; never a fatigue, readiness, or injury diagnosis. */
    Caution
}

/**
 * What "normal" looks like for this player, derived from their previous sessions.
 *
 * Comparisons against a player's own like-for-like history are more meaningful than mixed
 * population norms, so rules prefer this and otherwise stay descriptive.
 */
@Serializable
data class InsightBaseline(
    val sessionCount: Int,
    val medianRestRatio: Float?,
    val medianRallyShots: Float?,
    val bestRallyShots: Int?
) {
    /**
     * Below this, per-player comparisons are noise. Three sessions is already generous for a
     * median; the rules that use it also require the current value to differ substantially.
     */
    val hasEnoughHistory: Boolean get() = sessionCount >= MINIMUM_SESSIONS

    companion object {
        const val MINIMUM_SESSIONS = 3
        val NONE = InsightBaseline(0, null, null, null)
    }
}

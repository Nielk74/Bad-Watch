package com.badwatch.core.training

import kotlinx.serialization.Serializable

/** General education selected by the player, never a diagnosis generated from wrist data. */
@Serializable
data class PracticeDrill(
    val id: String,
    val title: String,
    val focus: String,
    val durationMinutes: Int,
    val steps: List<String>,
    val sourceTitle: String,
    val sourceUrl: String,
    val measurementNote: String
)

/**
 * A compact, source-visible starter library adapted from BWF Level 1 coach education.
 *
 * The watch may time or cue these drills. It does not claim to see grip, knee alignment,
 * shuttle contact, court position, or technique quality.
 */
object BwfPracticeLibrary {

    const val SOURCE_TITLE = "BWF Coach Education Level 1"
    const val SOURCE_URL = "https://development.bwfbadminton.com/coaches/level-1"

    val drills: List<PracticeDrill> = listOf(
        PracticeDrill(
            id = "six-corner-shadow",
            title = "Six-corner shadow",
            focus = "Start · approach · hit · recover",
            durationMinutes = 6,
            steps = listOf(
                "Begin from a balanced base position.",
                "Move to the watch's racket-side court cue.",
                "Shadow a controlled contact, then recover before confirming the repetition.",
                "Keep the sequence smooth before increasing pace."
            ),
            sourceTitle = SOURCE_TITLE,
            sourceUrl = SOURCE_URL,
            measurementNote = "The watch records cues and confirmations, not corner arrival or footwork quality."
        ),
        PracticeDrill(
            id = "split-step-rhythm",
            title = "Split-step rhythm",
            focus = "Prepare as the feeder acts",
            durationMinutes = 5,
            steps = listOf(
                "Ask a partner to point or feed in an unpredictable direction.",
                "Use a small preparation jump as the partner begins the feed.",
                "Land balanced, move to the shuttle, and recover to base.",
                "Use the watch only as an interval timer."
            ),
            sourceTitle = SOURCE_TITLE,
            sourceUrl = SOURCE_URL,
            measurementNote = "The watch does not detect opponent contact or split-step timing."
        ),
        PracticeDrill(
            id = "balanced-lunge",
            title = "Balanced lunge pattern",
            focus = "Controlled approach and recovery",
            durationMinutes = 5,
            steps = listOf(
                "Move from base to a comfortable front-court lunge.",
                "Pause briefly in balance instead of reaching for extra depth.",
                "Push back under control and reset before the next repetition.",
                "Stop if the movement causes pain."
            ),
            sourceTitle = SOURCE_TITLE,
            sourceUrl = SOURCE_URL,
            measurementNote = "The watch does not assess knee, foot, balance, depth, pain, or injury risk."
        ),
        PracticeDrill(
            id = "overhead-preparation",
            title = "Overhead preparation",
            focus = "Side-on preparation and recovery",
            durationMinutes = 6,
            steps = listOf(
                "Start side-on with room to rotate.",
                "Load comfortably before a relaxed shadow overhead action.",
                "Finish balanced and recover before repeating.",
                "Add a shuttle feed only after the pattern feels controlled."
            ),
            sourceTitle = SOURCE_TITLE,
            sourceUrl = SOURCE_URL,
            measurementNote = "The watch cannot verify body position or shuttle-contact location."
        ),
        PracticeDrill(
            id = "grip-change",
            title = "Relaxed grip changes",
            focus = "Adapt the grip without excess tension",
            durationMinutes = 4,
            steps = listOf(
                "Hold the racket lightly in a neutral ready position.",
                "Alternate forehand- and backhand-side preparation without a full swing.",
                "Add gentle fed contacts while keeping the hand relaxed between actions.",
                "Finish before tension builds in the forearm."
            ),
            sourceTitle = SOURCE_TITLE,
            sourceUrl = SOURCE_URL,
            measurementNote = "Grip shape and finger tightening are not visible to a wrist watch."
        )
    )

    fun byId(id: String): PracticeDrill? = drills.firstOrNull { it.id == id }
}

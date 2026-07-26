package com.badwatch.core.training

import kotlinx.serialization.Serializable
import kotlin.math.max

/** Court directions relative to the player's racket side, so left-handers need no mirroring. */
@Serializable
enum class CourtCorner {
    ForehandFront,
    BackhandFront,
    ForehandMid,
    BackhandMid,
    ForehandRear,
    BackhandRear
}

@Serializable
enum class ShadowStatus { Active, Paused, Complete }

/**
 * One player-confirmed shadow repetition.
 *
 * Confirmation is an interaction timestamp, not proof that the player reached a corner,
 * returned to base, or used correct footwork. Those claims require validated movement data.
 */
@Serializable
data class ShadowRepetition(
    val corner: CourtCorner,
    val cuedAtMillis: Long,
    val confirmedAtMillis: Long
) {
    val responseMillis: Long get() = max(0L, confirmedAtMillis - cuedAtMillis)
}

/** Serializable reducer state; safe to checkpoint after every tap and restore after a restart. */
@Serializable
data class ShadowRoutineState(
    val seed: Long,
    val targetRepetitions: Int,
    val nextCueIndex: Int,
    val currentCorner: CourtCorner?,
    val currentCuedAtMillis: Long?,
    val repetitions: List<ShadowRepetition>,
    val status: ShadowStatus,
    val pausedAtMillis: Long? = null
) {
    init {
        require(targetRepetitions > 0) { "targetRepetitions must be positive" }
        require(nextCueIndex >= 0) { "nextCueIndex must not be negative" }
        require(repetitions.size <= targetRepetitions) { "too many completed repetitions" }
        if (status == ShadowStatus.Active) {
            require(currentCorner != null && currentCuedAtMillis != null)
        }
        if (status == ShadowStatus.Paused) require(pausedAtMillis != null)
    }

    val completedRepetitions: Int get() = repetitions.size
    val progress: Float get() = repetitions.size.toFloat() / targetRepetitions
}

@Serializable
data class ShadowRoutineSummary(
    val completedRepetitions: Int,
    val targetRepetitions: Int,
    val medianResponseMillis: Long?,
    val fastestResponseMillis: Long?,
    val finished: Boolean
)

/**
 * Pure shadow-footwork routine reducer.
 *
 * Every six cues contain all six directions exactly once, and adjacent repetitions never
 * repeat across batch boundaries. The sequence is generated from a stable local PRNG so a
 * persisted seed/index resumes identically on every JVM and Android version.
 */
object ShadowTrainer {

    fun start(seed: Long, targetRepetitions: Int, nowMillis: Long): ShadowRoutineState {
        require(targetRepetitions in 1..MAX_REPETITIONS) {
            "targetRepetitions must be between 1 and $MAX_REPETITIONS"
        }
        return ShadowRoutineState(
            seed = seed,
            targetRepetitions = targetRepetitions,
            nextCueIndex = 1,
            currentCorner = cornerAt(seed, 0),
            currentCuedAtMillis = nowMillis,
            repetitions = emptyList(),
            status = ShadowStatus.Active
        )
    }

    fun confirm(state: ShadowRoutineState, nowMillis: Long): ShadowRoutineState {
        if (state.status != ShadowStatus.Active) return state
        val corner = requireNotNull(state.currentCorner)
        val cuedAt = requireNotNull(state.currentCuedAtMillis)
        val completed = state.repetitions + ShadowRepetition(
            corner = corner,
            cuedAtMillis = cuedAt,
            confirmedAtMillis = max(cuedAt, nowMillis)
        )
        if (completed.size >= state.targetRepetitions) {
            return state.copy(
                currentCorner = null,
                currentCuedAtMillis = null,
                repetitions = completed,
                status = ShadowStatus.Complete,
                pausedAtMillis = null
            )
        }
        return state.copy(
            nextCueIndex = state.nextCueIndex + 1,
            currentCorner = cornerAt(state.seed, state.nextCueIndex),
            currentCuedAtMillis = nowMillis,
            repetitions = completed
        )
    }

    fun pause(state: ShadowRoutineState, nowMillis: Long): ShadowRoutineState =
        if (state.status == ShadowStatus.Active) {
            state.copy(status = ShadowStatus.Paused, pausedAtMillis = nowMillis)
        } else {
            state
        }

    fun resume(state: ShadowRoutineState, nowMillis: Long): ShadowRoutineState {
        if (state.status != ShadowStatus.Paused) return state
        val pausedAt = requireNotNull(state.pausedAtMillis)
        val pauseDuration = max(0L, nowMillis - pausedAt)
        return state.copy(
            status = ShadowStatus.Active,
            currentCuedAtMillis = state.currentCuedAtMillis?.plus(pauseDuration),
            pausedAtMillis = null
        )
    }

    /** End early while preserving the player-confirmed repetitions already completed. */
    fun finish(state: ShadowRoutineState): ShadowRoutineState = state.copy(
        currentCorner = null,
        currentCuedAtMillis = null,
        status = ShadowStatus.Complete,
        pausedAtMillis = null
    )

    fun summary(state: ShadowRoutineState): ShadowRoutineSummary {
        val sorted = state.repetitions.map { it.responseMillis }.sorted()
        val median = when {
            sorted.isEmpty() -> null
            sorted.size % 2 == 1 -> sorted[sorted.size / 2]
            else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2L
        }
        return ShadowRoutineSummary(
            completedRepetitions = state.repetitions.size,
            targetRepetitions = state.targetRepetitions,
            medianResponseMillis = median,
            fastestResponseMillis = sorted.firstOrNull(),
            finished = state.status == ShadowStatus.Complete
        )
    }

    fun sequence(seed: Long, count: Int): List<CourtCorner> {
        require(count >= 0) { "count must not be negative" }
        return (0 until count).map { cornerAt(seed, it) }
    }

    private fun cornerAt(seed: Long, index: Int): CourtCorner {
        require(index >= 0) { "index must not be negative" }
        val targetBatch = index / COURT_SIZE
        var previousLast: CourtCorner? = null
        var targetDeck = emptyList<CourtCorner>()
        for (batch in 0..targetBatch) {
            val deck = CourtCorner.entries.toMutableList()
            val random = StableRandom(seed xor (GOLDEN_STEP * (batch + 1L)))
            for (position in deck.lastIndex downTo 1) {
                val swapWith = random.nextInt(position + 1)
                val value = deck[position]
                deck[position] = deck[swapWith]
                deck[swapWith] = value
            }
            if (previousLast != null && deck.first() == previousLast) {
                val value = deck[0]
                deck[0] = deck[1]
                deck[1] = value
            }
            previousLast = deck.last()
            targetDeck = deck
        }
        return targetDeck[index % COURT_SIZE]
    }

    private class StableRandom(seed: Long) {
        private var state = if (seed == 0L) NON_ZERO_SEED else seed

        fun nextInt(bound: Int): Int {
            require(bound > 0)
            var value = state
            value = value xor (value shl 13)
            value = value xor (value ushr 7)
            value = value xor (value shl 17)
            state = value
            return Math.floorMod(value, bound.toLong()).toInt()
        }
    }

    private const val COURT_SIZE = 6
    private const val MAX_REPETITIONS = 300
    private const val GOLDEN_STEP = -7046029254386353131L
    private const val NON_ZERO_SEED = 0x5DEECE66DL
}

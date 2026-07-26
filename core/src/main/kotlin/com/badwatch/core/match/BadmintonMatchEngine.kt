package com.badwatch.core.match

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** The two sides of a match, expressed from the watch wearer's point of view. */
@Serializable
enum class MatchSide {
    Player,
    Opponent;

    fun other(): MatchSide = if (this == Player) Opponent else Player
}

@Serializable
enum class MatchFormat {
    Singles,
    Doubles
}

/** A server stands on the right at an even score and on the left at an odd score. */
@Serializable
enum class ServiceCourt {
    Right,
    Left
}

@Serializable
data class GameScore(
    val player: Int,
    val opponent: Int
) {
    val winner: MatchSide
        get() = if (player > opponent) MatchSide.Player else MatchSide.Opponent
}

@Serializable
enum class MatchPrompt {
    IntervalAtEleven,
    GameInterval,
    ChangeEnds,
    MatchComplete
}

/**
 * Complete, persistable state for one badminton match.
 *
 * The engine follows BWF rally scoring: first to 21, win by two, hard cap at 30, best of
 * three by default. It deliberately tracks the *side* and service court rather than naming
 * a doubles server: partner rotation needs a four-player lineup, which is optional context
 * and should never be guessed.
 */
@Serializable
data class MatchState(
    val format: MatchFormat = MatchFormat.Singles,
    val bestOfGames: Int = 3,
    val completedGames: List<GameScore> = emptyList(),
    val playerPoints: Int = 0,
    val opponentPoints: Int = 0,
    val server: MatchSide = MatchSide.Player,
    val playerAtNearEnd: Boolean = true,
    val decidingGameEndsChanged: Boolean = false,
    val prompt: MatchPrompt? = null,
    val intervalEndsAtMillis: Long? = null,
    val winner: MatchSide? = null
) {
    init {
        require(bestOfGames > 0 && bestOfGames % 2 == 1) {
            "bestOfGames must be a positive odd number"
        }
        require(playerPoints >= 0 && opponentPoints >= 0) { "Scores cannot be negative" }
    }

    val gamesNeededToWin: Int get() = bestOfGames / 2 + 1
    val playerGames: Int get() = completedGames.count { it.winner == MatchSide.Player }
    val opponentGames: Int get() = completedGames.count { it.winner == MatchSide.Opponent }
    val currentGameNumber: Int get() = completedGames.size + 1
    val isComplete: Boolean get() = winner != null

    val servingCourt: ServiceCourt
        get() {
            val servingScore = if (server == MatchSide.Player) playerPoints else opponentPoints
            return if (servingScore % 2 == 0) ServiceCourt.Right else ServiceCourt.Left
        }
}

sealed interface MatchEvent {
    data class PointAwarded(val side: MatchSide) : MatchEvent
    data class GameWon(val side: MatchSide, val score: GameScore) : MatchEvent
    data class MatchWon(val side: MatchSide) : MatchEvent
    data class IntervalStarted(val seconds: Int) : MatchEvent
    data object ChangeEnds : MatchEvent
}

data class MatchUpdate(
    val state: MatchState,
    val events: List<MatchEvent>
)

/**
 * One durable, user-authored change to a manual match.
 *
 * Point winners are never inferred from motion. Keeping the clock on each action makes
 * interval deadlines deterministic when a match is replayed after process death.
 */
@Serializable
sealed interface MatchAction {
    val atMillis: Long

    @Serializable
    @SerialName("point")
    data class AwardPoint(
        val side: MatchSide,
        override val atMillis: Long
    ) : MatchAction

    @Serializable
    @SerialName("acknowledge_prompt")
    data class AcknowledgePrompt(
        override val atMillis: Long
    ) : MatchAction
}

/**
 * Append-only source of truth for a match. [BadmintonMatchTimeline.replay] derives the
 * current score; persisted derived state can therefore never drift from the action history.
 */
@Serializable
data class MatchLog(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val startedAtMillis: Long,
    val format: MatchFormat = MatchFormat.Singles,
    val bestOfGames: Int = 3,
    val initialServer: MatchSide = MatchSide.Player,
    val playerAtNearEnd: Boolean = true,
    val actions: List<MatchAction> = emptyList()
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported match schema $schemaVersion" }
        require(id.isNotBlank()) { "Match id cannot be blank" }
        require(startedAtMillis >= 0L) { "Match start cannot be negative" }
        require(bestOfGames > 0 && bestOfGames % 2 == 1) {
            "bestOfGames must be a positive odd number"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Pure action-log operations used by both the Wear controller and JVM tests.
 *
 * Undo means "undo the last point", not "pop an arbitrary UI action". If the player has
 * already dismissed an interval prompt, that acknowledgement is removed with the point that
 * created it. Replaying then restores the exact preceding game, server and court end.
 */
object BadmintonMatchTimeline {

    fun replay(log: MatchLog): MatchState = log.actions.fold(initialState(log)) { state, action ->
        when (action) {
            is MatchAction.AwardPoint ->
                BadmintonMatchEngine.awardPoint(state, action.side, action.atMillis).state

            is MatchAction.AcknowledgePrompt ->
                BadmintonMatchEngine.acknowledgePrompt(state)
        }
    }

    fun awardPoint(log: MatchLog, side: MatchSide, atMillis: Long): MatchLog {
        require(atMillis >= 0L) { "Action time cannot be negative" }
        if (replay(log).isComplete) return log
        return log.copy(actions = log.actions + MatchAction.AwardPoint(side, atMillis))
    }

    fun acknowledgePrompt(log: MatchLog, atMillis: Long): MatchLog {
        require(atMillis >= 0L) { "Action time cannot be negative" }
        if (replay(log).prompt == null) return log
        return log.copy(actions = log.actions + MatchAction.AcknowledgePrompt(atMillis))
    }

    fun undoLastPoint(log: MatchLog): MatchLog {
        val pointIndex = log.actions.indexOfLast { it is MatchAction.AwardPoint }
        return if (pointIndex < 0) log else log.copy(actions = log.actions.take(pointIndex))
    }

    fun canUndo(log: MatchLog): Boolean = log.actions.any { it is MatchAction.AwardPoint }

    private fun initialState(log: MatchLog) = MatchState(
        format = log.format,
        bestOfGames = log.bestOfGames,
        server = log.initialServer,
        playerAtNearEnd = log.playerAtNearEnd
    )
}

/** Pure scoring reducer. UI and persistence can replay actions and test every edge case. */
object BadmintonMatchEngine {

    private const val GAME_TARGET = 21
    private const val GAME_CAP = 30
    private const val MID_GAME_INTERVAL_POINT = 11
    private const val MID_GAME_INTERVAL_SECONDS = 60
    private const val BETWEEN_GAMES_INTERVAL_SECONDS = 120

    fun awardPoint(
        state: MatchState,
        side: MatchSide,
        nowMillis: Long = 0L
    ): MatchUpdate {
        if (state.isComplete) return MatchUpdate(state, emptyList())

        val nextPlayer = state.playerPoints + if (side == MatchSide.Player) 1 else 0
        val nextOpponent = state.opponentPoints + if (side == MatchSide.Opponent) 1 else 0
        val events = mutableListOf<MatchEvent>(MatchEvent.PointAwarded(side))

        val gameWon = isGameWon(nextPlayer, nextOpponent)
        if (gameWon) {
            val score = GameScore(nextPlayer, nextOpponent)
            val games = state.completedGames + score
            events += MatchEvent.GameWon(side, score)

            val playerGames = games.count { it.winner == MatchSide.Player }
            val opponentGames = games.count { it.winner == MatchSide.Opponent }
            val matchWinner = when {
                playerGames >= state.gamesNeededToWin -> MatchSide.Player
                opponentGames >= state.gamesNeededToWin -> MatchSide.Opponent
                else -> null
            }

            if (matchWinner != null) {
                events += MatchEvent.MatchWon(matchWinner)
                return MatchUpdate(
                    state.copy(
                        completedGames = games,
                        playerPoints = nextPlayer,
                        opponentPoints = nextOpponent,
                        server = side,
                        prompt = MatchPrompt.MatchComplete,
                        intervalEndsAtMillis = null,
                        winner = matchWinner
                    ),
                    events
                )
            }

            events += MatchEvent.ChangeEnds
            events += MatchEvent.IntervalStarted(BETWEEN_GAMES_INTERVAL_SECONDS)
            return MatchUpdate(
                state.copy(
                    completedGames = games,
                    playerPoints = 0,
                    opponentPoints = 0,
                    // The side winning a game serves first in the next one.
                    server = side,
                    playerAtNearEnd = !state.playerAtNearEnd,
                    decidingGameEndsChanged = false,
                    prompt = MatchPrompt.GameInterval,
                    intervalEndsAtMillis = intervalEnd(nowMillis, BETWEEN_GAMES_INTERVAL_SECONDS)
                ),
                events
            )
        }

        var nearEnd = state.playerAtNearEnd
        var decidingEndsChanged = state.decidingGameEndsChanged
        var prompt: MatchPrompt? = null
        var intervalEndsAt: Long? = null

        val isDecidingGame = state.currentGameNumber == state.bestOfGames
        // Trigger on the transition to eleven, not on every following rally while the
        // first side remains at eleven.
        val firstSideToEleven =
            maxOf(state.playerPoints, state.opponentPoints) < MID_GAME_INTERVAL_POINT &&
                maxOf(nextPlayer, nextOpponent) == MID_GAME_INTERVAL_POINT
        if (firstSideToEleven) {
            prompt = MatchPrompt.IntervalAtEleven
            intervalEndsAt = intervalEnd(nowMillis, MID_GAME_INTERVAL_SECONDS)
            events += MatchEvent.IntervalStarted(MID_GAME_INTERVAL_SECONDS)

            if (isDecidingGame && !decidingEndsChanged) {
                nearEnd = !nearEnd
                decidingEndsChanged = true
                // Change ends is the action the player must not miss, so it wins visually
                // over the generic interval prompt while the timer still runs.
                prompt = MatchPrompt.ChangeEnds
                events += MatchEvent.ChangeEnds
            }
        }

        return MatchUpdate(
            state.copy(
                playerPoints = nextPlayer,
                opponentPoints = nextOpponent,
                server = side,
                playerAtNearEnd = nearEnd,
                decidingGameEndsChanged = decidingEndsChanged,
                prompt = prompt,
                intervalEndsAtMillis = intervalEndsAt
            ),
            events
        )
    }

    /** Clears a transient prompt after the player has acknowledged it. */
    fun acknowledgePrompt(state: MatchState): MatchState =
        state.copy(prompt = null, intervalEndsAtMillis = null)

    private fun isGameWon(player: Int, opponent: Int): Boolean {
        val leader = maxOf(player, opponent)
        val margin = kotlin.math.abs(player - opponent)
        return leader >= GAME_CAP || (leader >= GAME_TARGET && margin >= 2)
    }

    private fun intervalEnd(nowMillis: Long, seconds: Int): Long? =
        nowMillis.takeIf { it > 0L }?.plus(seconds * 1_000L)
}

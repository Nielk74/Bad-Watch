package com.badwatch.core.match

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class BadmintonMatchEngineTest {

    @Test
    fun `winner serves from court matching score parity`() {
        var state = MatchState(server = MatchSide.Opponent)

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Player).state
        assertThat(state.server).isEqualTo(MatchSide.Player)
        assertThat(state.servingCourt).isEqualTo(ServiceCourt.Left)

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Player).state
        assertThat(state.servingCourt).isEqualTo(ServiceCourt.Right)

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Opponent).state
        assertThat(state.server).isEqualTo(MatchSide.Opponent)
        assertThat(state.servingCourt).isEqualTo(ServiceCourt.Left)
    }

    @Test
    fun `game requires two points after twenty all`() {
        var state = MatchState(playerPoints = 20, opponentPoints = 20)

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Player).state
        assertThat(state.completedGames).isEmpty()

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Opponent).state
        assertThat(state.completedGames).isEmpty()

        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Player).state
        state = BadmintonMatchEngine.awardPoint(state, MatchSide.Player).state

        assertThat(state.completedGames).containsExactly(GameScore(23, 21))
        assertThat(state.playerPoints).isEqualTo(0)
        assertThat(state.opponentPoints).isEqualTo(0)
        assertThat(state.server).isEqualTo(MatchSide.Player)
    }

    @Test
    fun `thirty is a hard cap`() {
        val state = MatchState(playerPoints = 29, opponentPoints = 29)

        val update = BadmintonMatchEngine.awardPoint(state, MatchSide.Opponent)

        assertThat(update.state.completedGames).containsExactly(GameScore(29, 30))
        assertThat(update.events).contains(MatchEvent.GameWon(MatchSide.Opponent, GameScore(29, 30)))
    }

    @Test
    fun `eleven starts sixty second interval once`() {
        val before = MatchState(playerPoints = 10, opponentPoints = 7)

        val update = BadmintonMatchEngine.awardPoint(before, MatchSide.Player, nowMillis = 1_000L)

        assertThat(update.state.prompt).isEqualTo(MatchPrompt.IntervalAtEleven)
        assertThat(update.state.intervalEndsAtMillis).isEqualTo(61_000L)
        assertThat(update.events).contains(MatchEvent.IntervalStarted(60))

        val after = BadmintonMatchEngine.awardPoint(update.state, MatchSide.Opponent, 2_000L)
        assertThat(after.state.prompt).isNull()
        assertThat(after.events).doesNotContain(MatchEvent.IntervalStarted(60))
    }

    @Test
    fun `second side reaching eleven does not start another interval`() {
        val afterFirstInterval = MatchState(
            playerPoints = 14,
            opponentPoints = 10,
            prompt = null,
            intervalEndsAtMillis = null
        )

        val update = BadmintonMatchEngine.awardPoint(
            afterFirstInterval,
            MatchSide.Opponent,
            nowMillis = 25_000L
        )

        assertThat(update.state.opponentPoints).isEqualTo(11)
        assertThat(update.state.prompt).isNull()
        assertThat(update.state.intervalEndsAtMillis).isNull()
        assertThat(update.events).doesNotContain(MatchEvent.IntervalStarted(60))
    }

    @Test
    fun `game win changes ends and starts two minute interval`() {
        val before = MatchState(playerPoints = 20, opponentPoints = 12, playerAtNearEnd = true)

        val update = BadmintonMatchEngine.awardPoint(before, MatchSide.Player, nowMillis = 5_000L)

        assertThat(update.state.completedGames).containsExactly(GameScore(21, 12))
        assertThat(update.state.playerAtNearEnd).isFalse()
        assertThat(update.state.prompt).isEqualTo(MatchPrompt.GameInterval)
        assertThat(update.state.intervalEndsAtMillis).isEqualTo(125_000L)
        assertThat(update.events).contains(MatchEvent.ChangeEnds)
        assertThat(update.events).contains(MatchEvent.IntervalStarted(120))
    }

    @Test
    fun `eleven in deciding game prompts change of ends`() {
        val before = MatchState(
            completedGames = listOf(GameScore(21, 15), GameScore(17, 21)),
            playerPoints = 10,
            opponentPoints = 8,
            playerAtNearEnd = true
        )

        val update = BadmintonMatchEngine.awardPoint(before, MatchSide.Player, nowMillis = 10_000L)

        assertThat(update.state.playerAtNearEnd).isFalse()
        assertThat(update.state.decidingGameEndsChanged).isTrue()
        assertThat(update.state.prompt).isEqualTo(MatchPrompt.ChangeEnds)
        assertThat(update.state.intervalEndsAtMillis).isEqualTo(70_000L)
    }

    @Test
    fun `best of three ends when a side wins two games`() {
        val before = MatchState(
            completedGames = listOf(GameScore(21, 18)),
            playerPoints = 20,
            opponentPoints = 9
        )

        val update = BadmintonMatchEngine.awardPoint(before, MatchSide.Player)

        assertThat(update.state.isComplete).isTrue()
        assertThat(update.state.winner).isEqualTo(MatchSide.Player)
        assertThat(update.state.prompt).isEqualTo(MatchPrompt.MatchComplete)
        assertThat(update.state.completedGames).containsExactly(
            GameScore(21, 18),
            GameScore(21, 9)
        ).inOrder()
        assertThat(update.events).contains(MatchEvent.MatchWon(MatchSide.Player))
    }

    @Test
    fun `points after match completion are ignored`() {
        val complete = MatchState(
            completedGames = listOf(GameScore(21, 4), GameScore(21, 7)),
            playerPoints = 21,
            opponentPoints = 7,
            winner = MatchSide.Player
        )

        val update = BadmintonMatchEngine.awardPoint(complete, MatchSide.Opponent)

        assertThat(update.state).isEqualTo(complete)
        assertThat(update.events).isEmpty()
    }

    @Test
    fun `action log replay restores score server and interval deadline`() {
        val original = MatchLog(
            id = "match-1",
            startedAtMillis = 1_000L,
            format = MatchFormat.Doubles,
            initialServer = MatchSide.Opponent
        ).let { log ->
            (1..11).fold(log) { next, point ->
                BadmintonMatchTimeline.awardPoint(
                    next,
                    side = MatchSide.Player,
                    atMillis = 1_000L + point * 500L
                )
            }
        }

        val encoded = Json.encodeToString(MatchLog.serializer(), original)
        val restored = Json.decodeFromString(MatchLog.serializer(), encoded)
        val state = BadmintonMatchTimeline.replay(restored)

        assertThat(state.format).isEqualTo(MatchFormat.Doubles)
        assertThat(state.playerPoints).isEqualTo(11)
        assertThat(state.opponentPoints).isEqualTo(0)
        assertThat(state.server).isEqualTo(MatchSide.Player)
        assertThat(state.servingCourt).isEqualTo(ServiceCourt.Left)
        assertThat(state.prompt).isEqualTo(MatchPrompt.IntervalAtEleven)
        assertThat(state.intervalEndsAtMillis).isEqualTo(66_500L)
    }

    @Test
    fun `undo removes last point and its later prompt acknowledgement`() {
        val atTen = (1..10).fold(newLog()) { log, point ->
            BadmintonMatchTimeline.awardPoint(log, MatchSide.Player, point * 1_000L)
        }
        val atEleven = BadmintonMatchTimeline.awardPoint(
            atTen,
            MatchSide.Player,
            atMillis = 11_000L
        )
        val acknowledged = BadmintonMatchTimeline.acknowledgePrompt(
            atEleven,
            atMillis = 12_000L
        )

        val undone = BadmintonMatchTimeline.undoLastPoint(acknowledged)
        val state = BadmintonMatchTimeline.replay(undone)

        assertThat(undone.actions).hasSize(10)
        assertThat(state.playerPoints).isEqualTo(10)
        assertThat(state.prompt).isNull()
        assertThat(state.server).isEqualTo(MatchSide.Player)
    }

    @Test
    fun `undoing a game point restores the previous game and court end`() {
        val beforeGame = score(newLog(), MatchSide.Player, 20)
        val gameWon = BadmintonMatchTimeline.awardPoint(
            beforeGame,
            MatchSide.Player,
            atMillis = 21_000L
        )
        assertThat(BadmintonMatchTimeline.replay(gameWon).completedGames).hasSize(1)

        val restored = BadmintonMatchTimeline.replay(
            BadmintonMatchTimeline.undoLastPoint(gameWon)
        )

        assertThat(restored.completedGames).isEmpty()
        assertThat(restored.playerPoints).isEqualTo(20)
        assertThat(restored.opponentPoints).isEqualTo(0)
        assertThat(restored.playerAtNearEnd).isTrue()
        assertThat(restored.prompt).isNull()
    }

    @Test
    fun `undoing a match point reopens the match`() {
        var log = newLog()
        log = score(log, MatchSide.Player, 21)
        log = BadmintonMatchTimeline.acknowledgePrompt(log, 22_000L)
        log = score(log, MatchSide.Player, 20, firstTimestamp = 30_000L)
        val complete = BadmintonMatchTimeline.awardPoint(log, MatchSide.Player, 60_000L)
        assertThat(BadmintonMatchTimeline.replay(complete).winner).isEqualTo(MatchSide.Player)

        val reopened = BadmintonMatchTimeline.replay(
            BadmintonMatchTimeline.undoLastPoint(complete)
        )

        assertThat(reopened.isComplete).isFalse()
        assertThat(reopened.completedGames).containsExactly(GameScore(21, 0))
        assertThat(reopened.playerPoints).isEqualTo(20)
        assertThat(reopened.opponentPoints).isEqualTo(0)
        assertThat(reopened.prompt).isNull()
    }

    @Test
    fun `completed match rejects extra point actions`() {
        var log = newLog()
        log = score(log, MatchSide.Player, 21)
        log = BadmintonMatchTimeline.acknowledgePrompt(log, 22_000L)
        log = score(log, MatchSide.Player, 21, firstTimestamp = 30_000L)
        val before = log.actions

        val ignored = BadmintonMatchTimeline.awardPoint(log, MatchSide.Opponent, 90_000L)

        assertThat(ignored.actions).isEqualTo(before)
        assertThat(BadmintonMatchTimeline.replay(ignored).winner).isEqualTo(MatchSide.Player)
    }

    @Test
    fun `undo with no points is a no-op`() {
        val log = newLog()

        assertThat(BadmintonMatchTimeline.undoLastPoint(log)).isSameInstanceAs(log)
        assertThat(BadmintonMatchTimeline.canUndo(log)).isFalse()
    }

    private fun newLog() = MatchLog(id = "test-match", startedAtMillis = 0L)

    private fun score(
        initial: MatchLog,
        side: MatchSide,
        points: Int,
        firstTimestamp: Long = 1_000L
    ): MatchLog = (0 until points).fold(initial) { log, index ->
        BadmintonMatchTimeline.awardPoint(log, side, firstTimestamp + index * 1_000L)
    }
}

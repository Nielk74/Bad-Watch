package com.badwatch.app.domain

import com.badwatch.app.data.MatchLoadResult
import com.badwatch.app.data.MatchPersistence
import com.badwatch.app.data.MatchStore
import com.badwatch.core.match.MatchFormat
import com.badwatch.core.match.MatchLog
import com.badwatch.core.match.MatchSide
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MatchControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rapidPointsAndUndoPersistInCommandOrder() = runTest {
        val file = File(temporaryFolder.newFolder("ordered"), "active.json")
        val controllerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val times = ArrayDeque(listOf(1_000L, 2_000L, 3_000L, 4_000L))
        val controller = MatchController(
            store = MatchStore(file),
            scope = controllerScope,
            now = { times.removeFirst() },
            newId = { "ordered-match" }
        )

        controller.start(MatchFormat.Doubles, MatchSide.Opponent)
        controller.awardPoint(MatchSide.Player)
        controller.awardPoint(MatchSide.Opponent)
        controller.undoLastPoint()
        advanceUntilIdle()

        val state = controller.state.value as MatchControllerState.Active
        assertThat(state.match.format).isEqualTo(MatchFormat.Doubles)
        assertThat(state.match.playerPoints).isEqualTo(1)
        assertThat(state.match.opponentPoints).isEqualTo(0)
        assertThat(state.match.server).isEqualTo(MatchSide.Player)
        assertThat(state.storageWarning).isNull()

        controllerScope.cancel()
        val restoredScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val restored = MatchController(MatchStore(file), restoredScope)
        advanceUntilIdle()

        val restoredState = restored.state.value as MatchControllerState.Active
        assertThat(restoredState.log.id).isEqualTo("ordered-match")
        assertThat(restoredState.match.playerPoints).isEqualTo(1)
        assertThat(restoredState.match.opponentPoints).isEqualTo(0)
        restoredScope.cancel()
    }

    @Test
    fun completedMatchRemainsDurableUntilExplicitlyCleared() = runTest {
        val file = File(temporaryFolder.newFolder("complete"), "active.json")
        val controllerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var timestamp = 1_000L
        val controller = MatchController(
            store = MatchStore(file),
            scope = controllerScope,
            now = { timestamp++ },
            newId = { "complete-match" }
        )
        controller.start(MatchFormat.Singles, MatchSide.Player)
        repeat(21) { controller.awardPoint(MatchSide.Player) }
        controller.acknowledgePrompt()
        repeat(21) { controller.awardPoint(MatchSide.Player) }
        advanceUntilIdle()

        val complete = controller.state.value as MatchControllerState.Active
        assertThat(complete.match.winner).isEqualTo(MatchSide.Player)
        assertThat(file.exists()).isTrue()

        controller.clear()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(MatchControllerState.Idle())
        assertThat(file.exists()).isFalse()
        controllerScope.cancel()
    }

    @Test
    fun corruptSavedMatchIsSurfacedAndCanBeCleared() = runTest {
        val file = File(temporaryFolder.newFolder("corrupt"), "active.json")
        file.writeText("{ definitely broken")
        val controllerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val controller = MatchController(MatchStore(file), controllerScope)
        advanceUntilIdle()

        assertThat(controller.state.value).isInstanceOf(MatchControllerState.Failed::class.java)

        controller.clear()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(MatchControllerState.Idle())
        assertThat(file.exists()).isFalse()
        controllerScope.cancel()
    }

    @Test
    fun failedStartStaysIdleAndRestartFindsNoMatch() = runTest {
        val persistence = FailingMatchPersistence().apply { failNextSave = true }
        val firstScope = controllerScope()
        val controller = MatchController(
            store = persistence,
            scope = firstScope,
            now = { 1_000L },
            newId = { "never-saved" }
        )

        controller.start(MatchFormat.Singles, MatchSide.Player)
        advanceUntilIdle()

        val failed = controller.state.value as MatchControllerState.Idle
        assertThat(failed.storageWarning).isNotNull()
        assertThat(persistence.stored).isNull()
        firstScope.cancel()

        val restartedScope = controllerScope()
        val restarted = MatchController(persistence, restartedScope)
        advanceUntilIdle()
        assertThat(restarted.state.value).isEqualTo(MatchControllerState.Idle())
        restartedScope.cancel()
    }

    @Test
    fun failedPointUndoPromptAndClearKeepLastDurableMatchAuthoritative() = runTest {
        val persistence = FailingMatchPersistence()
        val scope = controllerScope()
        var timestamp = 1_000L
        val controller = MatchController(
            store = persistence,
            scope = scope,
            now = { timestamp++ },
            newId = { "durable-match" }
        )
        controller.start(MatchFormat.Singles, MatchSide.Player)
        advanceUntilIdle()

        var durable = requireNotNull(persistence.stored)
        persistence.failNextSave = true
        controller.awardPoint(MatchSide.Player)
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        controller.awardPoint(MatchSide.Player)
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored)

        persistence.failNextSave = true
        controller.undoLastPoint()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        repeat(10) { controller.awardPoint(MatchSide.Player) }
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored)
        assertThat((controller.state.value as MatchControllerState.Active).match.prompt).isNotNull()

        persistence.failNextSave = true
        controller.acknowledgePrompt()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        persistence.failNextClear = true
        controller.clear()
        advanceUntilIdle()
        val afterFailedClear = controller.state.value as MatchControllerState.Active
        assertThat(afterFailedClear.log).isEqualTo(durable)
        assertThat(afterFailedClear.storageWarning).isNotNull()
        assertThat(persistence.stored).isEqualTo(durable)
        scope.cancel()

        val restartedScope = controllerScope()
        val restarted = MatchController(persistence, restartedScope)
        advanceUntilIdle()
        val restored = restarted.state.value as MatchControllerState.Active
        assertThat(restored.log).isEqualTo(durable)
        assertThat(restored.match).isEqualTo(afterFailedClear.match)
        restartedScope.cancel()
    }

    private fun assertFailedMutationKept(
        controller: MatchController,
        persistence: FailingMatchPersistence,
        durable: MatchLog
    ) {
        val visible = controller.state.value as MatchControllerState.Active
        assertThat(visible.log).isEqualTo(durable)
        assertThat(visible.storageWarning).isNotNull()
        assertThat(persistence.stored).isEqualTo(durable)
    }

    private fun kotlinx.coroutines.test.TestScope.controllerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private class FailingMatchPersistence : MatchPersistence {
        var stored: MatchLog? = null
        var failNextSave = false
        var failNextClear = false

        override fun load(): MatchLoadResult = stored?.let(MatchLoadResult::Loaded)
            ?: MatchLoadResult.Empty

        override fun save(log: MatchLog) {
            if (failNextSave) {
                failNextSave = false
                error("injected save failure")
            }
            stored = log
        }

        override fun clear() {
            if (failNextClear) {
                failNextClear = false
                error("injected clear failure")
            }
            stored = null
        }
    }
}

package com.badwatch.app.domain

import com.badwatch.app.data.ShadowRoutineDocument
import com.badwatch.app.data.ShadowRoutineLoadResult
import com.badwatch.app.data.ShadowRoutinePersistence
import com.badwatch.app.data.ShadowRoutineStore
import com.badwatch.core.training.ShadowRoutineState
import com.badwatch.core.training.ShadowStatus
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

class ShadowRoutineControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeRestartRestoresPausedAndExcludesUnobservedProcessGap() = runTest {
        val file = File(temporaryFolder.newFolder("restore-shadow"), "active.json")
        val firstScope = controllerScope()
        val first = ShadowRoutineController(
            store = ShadowRoutineStore(file),
            scope = firstScope,
            now = { 1_000L },
            newSeed = { 42L }
        )
        first.start(targetRepetitions = 1)
        advanceUntilIdle()
        firstScope.cancel()

        val restoredTimes = ArrayDeque(listOf(10_000L, 11_000L, 12_000L))
        val restoredScope = controllerScope()
        val restored = ShadowRoutineController(
            store = ShadowRoutineStore(file),
            scope = restoredScope,
            now = { restoredTimes.removeFirst() }
        )
        advanceUntilIdle()

        val paused = restored.state.value as ShadowControllerState.Active
        assertThat(paused.restored).isTrue()
        assertThat(paused.routine.status).isEqualTo(ShadowStatus.Paused)
        assertThat(paused.routine.currentCuedAtMillis).isEqualTo(10_000L)

        restored.resume()
        restored.confirm()
        advanceUntilIdle()

        val complete = restored.state.value as ShadowControllerState.Active
        assertThat(complete.routine.status).isEqualTo(ShadowStatus.Complete)
        assertThat(complete.routine.repetitions.single().responseMillis).isEqualTo(1_000L)
        restoredScope.cancel()
    }

    @Test
    fun confirmationsPauseResumeAndEarlyFinishPersistInCommandOrder() = runTest {
        val file = File(temporaryFolder.newFolder("ordered-shadow"), "active.json")
        val times = ArrayDeque(listOf(1_000L, 2_000L, 3_000L, 8_000L, 9_500L, 10_000L))
        val scope = controllerScope()
        val controller = ShadowRoutineController(
            store = ShadowRoutineStore(file),
            scope = scope,
            now = { times.removeFirst() },
            newSeed = { 99L }
        )

        controller.start(6)
        controller.confirm()
        controller.pause()
        controller.resume()
        controller.confirm()
        controller.finishEarly()
        advanceUntilIdle()

        val complete = controller.state.value as ShadowControllerState.Active
        assertThat(complete.routine.status).isEqualTo(ShadowStatus.Complete)
        assertThat(complete.routine.completedRepetitions).isEqualTo(2)
        assertThat(complete.routine.repetitions[0].responseMillis).isEqualTo(1_000L)
        assertThat(complete.routine.repetitions[1].responseMillis).isEqualTo(2_500L)

        scope.cancel()
        val reopenedScope = controllerScope()
        val reopened = ShadowRoutineController(ShadowRoutineStore(file), reopenedScope)
        advanceUntilIdle()
        val persisted = reopened.state.value as ShadowControllerState.Active
        assertThat(persisted.routine).isEqualTo(complete.routine)
        reopened.clear()
        advanceUntilIdle()
        assertThat(reopened.state.value).isEqualTo(ShadowControllerState.Idle())
        assertThat(file.exists()).isFalse()
        reopenedScope.cancel()
    }

    @Test
    fun corruptSavedRoutineIsSurfacedAndCanBeCleared() = runTest {
        val file = File(temporaryFolder.newFolder("bad-shadow"), "active.json")
        file.writeText("{ broken")
        val scope = controllerScope()
        val controller = ShadowRoutineController(ShadowRoutineStore(file), scope)
        advanceUntilIdle()

        assertThat(controller.state.value).isInstanceOf(ShadowControllerState.Failed::class.java)

        controller.clear()
        advanceUntilIdle()
        assertThat(controller.state.value).isEqualTo(ShadowControllerState.Idle())
        assertThat(file.exists()).isFalse()
        scope.cancel()
    }

    @Test
    fun failedStartStaysIdleAndRestartFindsNoRoutine() = runTest {
        val persistence = FailingShadowPersistence().apply { failNextSave = true }
        val firstScope = controllerScope()
        val controller = ShadowRoutineController(
            store = persistence,
            scope = firstScope,
            now = { 1_000L },
            newSeed = { 42L }
        )

        controller.start(6)
        advanceUntilIdle()

        val failed = controller.state.value as ShadowControllerState.Idle
        assertThat(failed.storageWarning).isNotNull()
        assertThat(persistence.stored).isNull()
        firstScope.cancel()

        val restartedScope = controllerScope()
        val restarted = ShadowRoutineController(persistence, restartedScope)
        advanceUntilIdle()
        assertThat(restarted.state.value).isEqualTo(ShadowControllerState.Idle())
        restartedScope.cancel()
    }

    @Test
    fun failedConfirmPauseResumeFinishAndClearKeepLastDurableRoutineAuthoritative() = runTest {
        val persistence = FailingShadowPersistence()
        val scope = controllerScope()
        var timestamp = 1_000L
        val controller = ShadowRoutineController(
            store = persistence,
            scope = scope,
            now = { timestamp += 1_000L; timestamp },
            newSeed = { 99L }
        )
        controller.start(6)
        advanceUntilIdle()

        var durable = requireNotNull(persistence.stored).state
        persistence.failNextSave = true
        controller.confirm()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        controller.confirm()
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored).state

        persistence.failNextSave = true
        controller.pause()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        controller.pause()
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored).state

        persistence.failNextSave = true
        controller.resume()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        controller.resume()
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored).state

        persistence.failNextSave = true
        controller.finishEarly()
        advanceUntilIdle()
        assertFailedMutationKept(controller, persistence, durable)

        controller.pause()
        advanceUntilIdle()
        durable = requireNotNull(persistence.stored).state
        assertThat(durable.status).isEqualTo(ShadowStatus.Paused)

        persistence.failNextClear = true
        controller.clear()
        advanceUntilIdle()
        val afterFailedClear = controller.state.value as ShadowControllerState.Active
        assertThat(afterFailedClear.routine).isEqualTo(durable)
        assertThat(afterFailedClear.storageWarning).isNotNull()
        assertThat(persistence.stored?.state).isEqualTo(durable)
        scope.cancel()

        val restartedScope = controllerScope()
        val restarted = ShadowRoutineController(persistence, restartedScope)
        advanceUntilIdle()
        val restored = restarted.state.value as ShadowControllerState.Active
        assertThat(restored.routine).isEqualTo(durable)
        restartedScope.cancel()
    }

    @Test
    fun failedRestoreCheckpointDoesNotPublishAnUnsavedPausedRoutine() = runTest {
        val persistence = FailingShadowPersistence()
        val originalScope = controllerScope()
        val original = ShadowRoutineController(
            store = persistence,
            scope = originalScope,
            now = { 1_000L },
            newSeed = { 7L }
        )
        original.start(6)
        advanceUntilIdle()
        val durableActive = requireNotNull(persistence.stored)
        originalScope.cancel()

        persistence.failNextSave = true
        val failedRestoreScope = controllerScope()
        val failedRestore = ShadowRoutineController(
            store = persistence,
            scope = failedRestoreScope,
            now = { 10_000L }
        )
        advanceUntilIdle()

        assertThat(failedRestore.state.value).isInstanceOf(ShadowControllerState.Failed::class.java)
        assertThat(persistence.stored).isEqualTo(durableActive)
        failedRestoreScope.cancel()

        val retryScope = controllerScope()
        val retry = ShadowRoutineController(
            store = persistence,
            scope = retryScope,
            now = { 10_000L }
        )
        advanceUntilIdle()
        val safelyPaused = retry.state.value as ShadowControllerState.Active
        assertThat(safelyPaused.routine.status).isEqualTo(ShadowStatus.Paused)
        assertThat(persistence.stored?.state).isEqualTo(safelyPaused.routine)
        retryScope.cancel()
    }

    private fun assertFailedMutationKept(
        controller: ShadowRoutineController,
        persistence: FailingShadowPersistence,
        durable: ShadowRoutineState
    ) {
        val visible = controller.state.value as ShadowControllerState.Active
        assertThat(visible.routine).isEqualTo(durable)
        assertThat(visible.storageWarning).isNotNull()
        assertThat(persistence.stored?.state).isEqualTo(durable)
    }

    private fun kotlinx.coroutines.test.TestScope.controllerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private class FailingShadowPersistence : ShadowRoutinePersistence {
        var stored: ShadowRoutineDocument? = null
        var failNextSave = false
        var failNextClear = false

        override fun load(): ShadowRoutineLoadResult = stored?.let(ShadowRoutineLoadResult::Loaded)
            ?: ShadowRoutineLoadResult.Empty

        override fun save(document: ShadowRoutineDocument) {
            if (failNextSave) {
                failNextSave = false
                error("injected save failure")
            }
            stored = document
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

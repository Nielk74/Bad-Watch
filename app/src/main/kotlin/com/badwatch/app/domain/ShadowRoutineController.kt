package com.badwatch.app.domain

import com.badwatch.app.data.ShadowRoutineDocument
import com.badwatch.app.data.ShadowRoutineLoadResult
import com.badwatch.app.data.ShadowRoutinePersistence
import com.badwatch.core.training.ShadowRoutineState
import com.badwatch.core.training.ShadowStatus
import com.badwatch.core.training.ShadowTrainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

/**
 * Application-scoped, serialized command owner for the watch-guided shadow routine.
 *
 * An active routine restored after process death is shifted by the unobserved time since its
 * last checkpoint and reopened paused. That gap therefore cannot masquerade as a slow player
 * confirmation in the summary.
 */
class ShadowRoutineController(
    private val store: ShadowRoutinePersistence,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val newSeed: () -> Long = {
        UUID.randomUUID().mostSignificantBits xor UUID.randomUUID().leastSignificantBits
    }
) {
    private val _state = MutableStateFlow<ShadowControllerState>(ShadowControllerState.Loading)
    val state: StateFlow<ShadowControllerState> = _state.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            _state.value = hydrate()
            for (command in commands) handle(command)
        }
    }

    fun start(targetRepetitions: Int) {
        commands.trySend(Command.Start(targetRepetitions, newSeed(), now()))
    }

    /** Records the player's explicit "back at base" tap and advances to the next cue. */
    fun confirm() {
        commands.trySend(Command.Confirm(now()))
    }

    fun pause() {
        commands.trySend(Command.Pause(now()))
    }

    fun resume() {
        commands.trySend(Command.Resume(now()))
    }

    /** Completes early while keeping every already confirmed repetition. */
    fun finishEarly() {
        commands.trySend(Command.FinishEarly(now()))
    }

    fun clear() {
        commands.trySend(Command.Clear)
    }

    private fun hydrate(): ShadowControllerState = when (val loaded = store.load()) {
        ShadowRoutineLoadResult.Empty -> ShadowControllerState.Idle()
        is ShadowRoutineLoadResult.Corrupt -> ShadowControllerState.Failed(
            "The saved shadow routine could not be read. ${loaded.reason}"
        )

        is ShadowRoutineLoadResult.Loaded -> {
            val restoreAt = now()
            val document = loaded.document
            if (document.state.status == ShadowStatus.Active) {
                val unobservedGap = max(0L, restoreAt - document.updatedAtMillis)
                val shifted = document.state.copy(
                    currentCuedAtMillis = document.state.currentCuedAtMillis
                        ?.plus(unobservedGap)
                )
                val paused = ShadowTrainer.pause(shifted, restoreAt)
                if (persist(paused, restoreAt).isSuccess) {
                    active(paused, restored = true)
                } else {
                    ShadowControllerState.Failed(
                        "The restored routine could not be paused safely."
                    )
                }
            } else {
                active(document.state, restored = true)
            }
        }
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Start -> {
                if (_state.value is ShadowControllerState.Active) return
                val routine = ShadowTrainer.start(
                    seed = command.seed,
                    targetRepetitions = command.targetRepetitions,
                    nowMillis = command.atMillis
                )
                _state.value = if (persist(routine, command.atMillis).isSuccess) {
                    active(routine, restored = false)
                } else {
                    ShadowControllerState.Idle(
                        storageWarning = "Routine could not start because it could not be saved."
                    )
                }
            }

            is Command.Confirm -> updateActive(command.atMillis) { state ->
                ShadowTrainer.confirm(state, command.atMillis)
            }

            is Command.Pause -> updateActive(command.atMillis) { state ->
                ShadowTrainer.pause(state, command.atMillis)
            }

            is Command.Resume -> updateActive(command.atMillis) { state ->
                ShadowTrainer.resume(state, command.atMillis)
            }

            is Command.FinishEarly -> updateActive(command.atMillis, ShadowTrainer::finish)

            Command.Clear -> {
                val cleared = runCatching { store.clear() }
                _state.value = if (cleared.isSuccess) {
                    ShadowControllerState.Idle()
                } else {
                    val current = _state.value
                    if (current is ShadowControllerState.Active) {
                        current.copy(storageWarning = "Could not close the saved routine.")
                    } else if (current is ShadowControllerState.Idle) {
                        current.copy(storageWarning = "Could not close the saved routine.")
                    } else {
                        ShadowControllerState.Failed(
                            "Could not remove the damaged shadow routine."
                        )
                    }
                }
            }
        }
    }

    private fun updateActive(
        atMillis: Long,
        transform: (ShadowRoutineState) -> ShadowRoutineState
    ) {
        val current = _state.value as? ShadowControllerState.Active ?: return
        val updated = transform(current.routine)
        if (updated != current.routine) persistAndPublish(updated, atMillis)
    }

    private fun persistAndPublish(state: ShadowRoutineState, atMillis: Long) {
        val current = _state.value as? ShadowControllerState.Active ?: return
        _state.value = if (persist(state, atMillis).isSuccess) {
            active(state, restored = false)
        } else {
            current.copy(
                storageWarning = "Routine change was not applied because it could not be saved."
            )
        }
    }

    private fun persist(state: ShadowRoutineState, atMillis: Long): Result<Unit> = runCatching {
        store.save(ShadowRoutineDocument(state = state, updatedAtMillis = atMillis))
    }

    private fun active(
        state: ShadowRoutineState,
        restored: Boolean,
        storageWarning: String? = null
    ) = ShadowControllerState.Active(
        routine = state,
        restored = restored,
        storageWarning = storageWarning
    )

    private sealed interface Command {
        data class Start(
            val targetRepetitions: Int,
            val seed: Long,
            val atMillis: Long
        ) : Command

        data class Confirm(val atMillis: Long) : Command
        data class Pause(val atMillis: Long) : Command
        data class Resume(val atMillis: Long) : Command
        data class FinishEarly(val atMillis: Long) : Command
        data object Clear : Command
    }
}

sealed interface ShadowControllerState {
    data object Loading : ShadowControllerState
    data class Idle(val storageWarning: String? = null) : ShadowControllerState

    data class Active(
        val routine: ShadowRoutineState,
        val restored: Boolean = false,
        val storageWarning: String? = null
    ) : ShadowControllerState

    data class Failed(val message: String) : ShadowControllerState
}

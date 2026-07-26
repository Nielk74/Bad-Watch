package com.badwatch.app.domain

import com.badwatch.app.data.MatchLoadResult
import com.badwatch.app.data.MatchPersistence
import com.badwatch.core.match.BadmintonMatchTimeline
import com.badwatch.core.match.MatchFormat
import com.badwatch.core.match.MatchLog
import com.badwatch.core.match.MatchSide
import com.badwatch.core.match.MatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Application-scoped owner of the manual scoreboard.
 *
 * Commands run through one channel, so rapid alternating taps cannot write old snapshots over
 * new ones. Every score change is persisted before it is published. A failed write leaves the
 * last durable score authoritative and surfaces a retryable warning.
 */
class MatchController(
    private val store: MatchPersistence,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val _state = MutableStateFlow<MatchControllerState>(MatchControllerState.Loading)
    val state: StateFlow<MatchControllerState> = _state.asStateFlow()

    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            _state.value = when (val loaded = store.load()) {
                MatchLoadResult.Empty -> MatchControllerState.Idle()
                is MatchLoadResult.Loaded -> active(loaded.log)
                is MatchLoadResult.Corrupt -> MatchControllerState.Failed(
                    "The saved match could not be read. ${loaded.reason}"
                )
            }
            for (command in commands) handle(command)
        }
    }

    fun start(format: MatchFormat, initialServer: MatchSide) {
        commands.trySend(Command.Start(format, initialServer, now()))
    }

    fun awardPoint(side: MatchSide) {
        commands.trySend(Command.AwardPoint(side, now()))
    }

    fun undoLastPoint() {
        commands.trySend(Command.UndoLastPoint)
    }

    fun acknowledgePrompt() {
        commands.trySend(Command.AcknowledgePrompt(now()))
    }

    /** Clears either a completed match or a deliberately abandoned active match. */
    fun clear() {
        commands.trySend(Command.Clear)
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Start -> {
                if (_state.value is MatchControllerState.Active) return
                val log = MatchLog(
                    id = newId(),
                    startedAtMillis = command.atMillis,
                    format = command.format,
                    initialServer = command.initialServer
                )
                _state.value = if (persist(log).isSuccess) {
                    active(log)
                } else {
                    MatchControllerState.Idle(
                        storageWarning = "Match could not start because it could not be saved."
                    )
                }
            }

            is Command.AwardPoint -> updateActive { log ->
                BadmintonMatchTimeline.awardPoint(log, command.side, command.atMillis)
            }

            Command.UndoLastPoint -> updateActive(BadmintonMatchTimeline::undoLastPoint)

            is Command.AcknowledgePrompt -> updateActive { log ->
                BadmintonMatchTimeline.acknowledgePrompt(log, command.atMillis)
            }

            Command.Clear -> {
                val cleared = runCatching { store.clear() }
                _state.value = if (cleared.isSuccess) {
                    MatchControllerState.Idle()
                } else {
                    val current = _state.value
                    if (current is MatchControllerState.Active) {
                        current.copy(storageWarning = "Could not close the saved match.")
                    } else if (current is MatchControllerState.Idle) {
                        current.copy(storageWarning = "Could not close the saved match.")
                    } else {
                        MatchControllerState.Failed("Could not remove the damaged match file.")
                    }
                }
            }
        }
    }

    private fun updateActive(transform: (MatchLog) -> MatchLog) {
        val current = _state.value as? MatchControllerState.Active ?: return
        val updated = transform(current.log)
        if (updated != current.log) persistAndPublish(updated)
    }

    private fun persistAndPublish(log: MatchLog) {
        val current = _state.value as? MatchControllerState.Active ?: return
        _state.value = if (persist(log).isSuccess) {
            active(log)
        } else {
            current.copy(
                storageWarning = "Score change was not applied because it could not be saved."
            )
        }
    }

    private fun persist(log: MatchLog): Result<Unit> = runCatching { store.save(log) }

    private fun active(
        log: MatchLog,
        storageWarning: String? = null
    ): MatchControllerState.Active = MatchControllerState.Active(
        log = log,
        match = BadmintonMatchTimeline.replay(log),
        canUndo = BadmintonMatchTimeline.canUndo(log),
        storageWarning = storageWarning
    )

    private sealed interface Command {
        data class Start(
            val format: MatchFormat,
            val initialServer: MatchSide,
            val atMillis: Long
        ) : Command

        data class AwardPoint(val side: MatchSide, val atMillis: Long) : Command
        data class AcknowledgePrompt(val atMillis: Long) : Command
        data object UndoLastPoint : Command
        data object Clear : Command
    }
}

sealed interface MatchControllerState {
    data object Loading : MatchControllerState
    data class Idle(val storageWarning: String? = null) : MatchControllerState

    data class Active(
        val log: MatchLog,
        val match: MatchState,
        val canUndo: Boolean,
        val storageWarning: String? = null
    ) : MatchControllerState

    data class Failed(val message: String) : MatchControllerState
}

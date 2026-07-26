package com.badwatch.app.domain

import com.badwatch.app.data.CaptureStore
import com.badwatch.app.sensors.FusedSensorCollector
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.capture.SwingSegmenter
import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.CaptureProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Runs a labelled data-collection drill: pick a stroke, hit repetitions, save the windows.
 *
 * This is the missing link to a trained classifier. Without labelled swings there is no
 * dataset, without a dataset there is no model, and the rule-based heuristics stay in place
 * indefinitely. The flow is deliberately drill-shaped ("hit twenty clears") rather than
 * asking the player to tag shots mid-rally, because a drill gives unambiguous labels and
 * high repetition rate at the cost of some ecological validity — the right trade for a
 * bootstrap dataset.
 */
class CaptureController(
    private val sensorStream: SensorStream,
    private val captureStore: CaptureStore,
    private val runtimeSettings: CaptureRuntimeSettings,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var job: Job? = null
    private var captureMetadata: CaptureRuntimeMetadata? = null
    /** Stable export retained when the durable write fails, so retry never creates a new ID. */
    private var pendingExport: CaptureExport? = null
    private val lifecycleMutex = Mutex()
    private val swingsLock = Any()
    private val swings = mutableListOf<LabeledSwing>()
    private val segmenter = SwingSegmenter()

    val isCapturing: Boolean get() = _state.value is CaptureState.Capturing

    suspend fun start(label: ShotType): Boolean = lifecycleMutex.withLock {
        if (isCapturing || _state.value is CaptureState.Saved) return@withLock false
        pendingExport?.let {
            persistPendingExport(it)
            return@withLock false
        }
        val metadata = try {
            // Freeze identity, profile and—most importantly—consent before the first raw
            // sample. A later settings change must never alter this capture's data use.
            runtimeSettings.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _state.value = CaptureState.Failed(
                message = error.message ?: "Capture could not start",
                recovery = CaptureFailureRecovery.CancelCapture
            )
            return@withLock false
        }
        synchronized(swingsLock) {
            swings.clear()
            segmenter.reset()
        }
        val startedAt = now()
        captureMetadata = metadata
        _state.value = CaptureState.Capturing(
            label = label,
            swings = emptyList(),
            startedAtMillis = startedAt
        )

        job = scope.launch {
            try {
                sensorStream.samples().collect { sample ->
                    val snapshot = synchronized(swingsLock) {
                        val swing = segmenter.addSample(sample, label)
                        if (swing != null) {
                            swings += swing
                            swings.toList()
                        } else {
                            null
                        }
                    }
                    if (snapshot != null) {
                        _state.value = CaptureState.Capturing(
                            label = label,
                            swings = snapshot,
                            startedAtMillis = startedAt
                        )
                    }
                }
                throw IllegalStateException("Sensor stream stopped")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = CaptureState.Failed(
                    message = error.message ?: "Sensor stream stopped",
                    recovery = CaptureFailureRecovery.CancelCapture
                )
            }
        }
        true
    }

    /** Marks the most recent swing as unusable — a mishit, a stumble, a dropped shuttle. */
    fun discardLastSwing() {
        val snapshot = synchronized(swingsLock) {
            val last = swings.lastOrNull() ?: return
            swings[swings.lastIndex] = last.copy(discarded = true)
            swings.toList()
        }
        val current = _state.value
        if (current is CaptureState.Capturing) {
            _state.value = current.copy(swings = snapshot)
        }
    }

    suspend fun finish(): CaptureExport? = lifecycleMutex.withLock {
        (_state.value as? CaptureState.Saved)?.export?.let { return@withLock it }
        pendingExport?.let { return@withLock persistPendingExport(it) }

        val current = _state.value as? CaptureState.Capturing
        // Join before snapshotting `swings`; otherwise a final sensor callback can race the
        // export and make the saved count differ from the confirmation screen.
        job?.cancelAndJoin()
        job = null
        if (current == null) return@withLock null

        val kept = synchronized(swingsLock) { swings.filterNot { it.discarded } }
        if (kept.isEmpty()) {
            captureMetadata = null
            _state.value = CaptureState.Idle
            return@withLock null
        }
        val metadata = captureMetadata ?: run {
            _state.value = CaptureState.Failed(
                message = "Capture metadata was not available",
                recovery = CaptureFailureRecovery.CancelCapture
            )
            return@withLock null
        }

        val export = CaptureExport(
            deviceId = metadata.deviceId,
            participantId = metadata.participantId,
            appVersion = appVersion,
            profile = metadata.profile,
            capture = CaptureSession(
                id = UUID.randomUUID().toString(),
                startedAtMillis = current.startedAtMillis,
                endedAtMillis = now(),
                label = current.label,
                swings = kept
            ),
            samplingRateHz = 1_000_000 / FusedSensorCollector.DEFAULT_SAMPLING_PERIOD_MICROS,
            dataUse = metadata.dataUse,
            protocol = CaptureProtocol(),
            watch = metadata.watch
        )
        pendingExport = export
        persistPendingExport(export)
    }

    /** Explicit cancellation is serialized with Finish; a completed durable save always wins. */
    suspend fun cancel(): Boolean = lifecycleMutex.withLock {
        if (_state.value is CaptureState.Saved) return@withLock false
        job?.cancelAndJoin()
        job = null
        captureMetadata = null
        pendingExport = null
        synchronized(swingsLock) {
            swings.clear()
            segmenter.reset()
        }
        _state.value = CaptureState.Idle
        true
    }

    suspend fun acknowledge() = lifecycleMutex.withLock {
        pendingExport?.let {
            // A storage error's acknowledgement is a safe retry, not silent data loss.
            persistPendingExport(it)
            return@withLock
        }
        if (_state.value !is CaptureState.Capturing) {
            captureMetadata = null
            _state.value = CaptureState.Idle
        }
    }

    private suspend fun persistPendingExport(export: CaptureExport): CaptureExport? {
        val stored = try {
            captureStore.save(export)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            _state.value = CaptureState.Failed(
                message = error.message ?: "Capture could not be saved",
                recovery = CaptureFailureRecovery.RetrySave
            )
            return null
        }
        pendingExport = null
        captureMetadata = null
        _state.value = CaptureState.Saved(stored.export)
        return stored.export
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState

    data class Capturing(
        val label: ShotType,
        val swings: List<LabeledSwing>,
        val startedAtMillis: Long
    ) : CaptureState {
        val keptCount: Int get() = swings.count { !it.discarded }
    }

    data class Saved(val export: CaptureExport) : CaptureState

    data class Failed(
        val message: String,
        /** The only safe primary action for this failure; storage retries retain one ID. */
        val recovery: CaptureFailureRecovery
    ) : CaptureState
}

enum class CaptureFailureRecovery {
    /** No durable export exists. Clear the failed collector before starting another drill. */
    CancelCapture,

    /** A stable pending export exists and must be retried rather than silently discarded. */
    RetrySave
}

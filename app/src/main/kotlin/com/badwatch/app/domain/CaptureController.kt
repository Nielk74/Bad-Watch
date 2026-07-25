package com.badwatch.app.domain

import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.SettingsStore
import com.badwatch.app.sensors.FusedSensorCollector
import com.badwatch.app.sensors.SensorStream
import com.badwatch.core.capture.SwingSegmenter
import com.badwatch.core.model.CaptureSession
import com.badwatch.core.model.LabeledSwing
import com.badwatch.core.model.ShotType
import com.badwatch.core.sync.CaptureExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val settingsStore: SettingsStore,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var job: Job? = null
    private val swings = mutableListOf<LabeledSwing>()
    private val segmenter = SwingSegmenter()

    val isCapturing: Boolean get() = job?.isActive == true

    fun start(label: ShotType) {
        if (isCapturing) return
        swings.clear()
        segmenter.reset()
        val startedAt = now()

        job = scope.launch {
            _state.value = CaptureState.Capturing(label = label, swings = emptyList(), startedAtMillis = startedAt)
            try {
                sensorStream.samples().collect { sample ->
                    val swing = segmenter.addSample(sample, label)
                    if (swing != null) {
                        swings += swing
                        _state.value = CaptureState.Capturing(
                            label = label,
                            swings = swings.toList(),
                            startedAtMillis = startedAt
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.value = CaptureState.Failed(error.message ?: "Sensor stream stopped")
            }
        }
    }

    /** Marks the most recent swing as unusable — a mishit, a stumble, a dropped shuttle. */
    fun discardLastSwing() {
        val last = swings.lastOrNull() ?: return
        swings[swings.lastIndex] = last.copy(discarded = true)
        val current = _state.value
        if (current is CaptureState.Capturing) {
            _state.value = current.copy(swings = swings.toList())
        }
    }

    suspend fun finish(): CaptureExport? {
        val current = _state.value as? CaptureState.Capturing
        job?.cancel()
        job = null
        if (current == null) return null

        val kept = swings.filterNot { it.discarded }
        if (kept.isEmpty()) {
            _state.value = CaptureState.Idle
            return null
        }

        val export = CaptureExport(
            deviceId = settingsStore.ensureDeviceId(),
            appVersion = appVersion,
            profile = settingsStore.profile.first(),
            capture = CaptureSession(
                id = UUID.randomUUID().toString(),
                startedAtMillis = current.startedAtMillis,
                endedAtMillis = now(),
                label = current.label,
                swings = kept
            ),
            samplingRateHz = 1_000_000 / FusedSensorCollector.DEFAULT_SAMPLING_PERIOD_MICROS
        )
        captureStore.save(export)
        _state.value = CaptureState.Saved(export)
        return export
    }

    fun cancel() {
        job?.cancel()
        job = null
        swings.clear()
        segmenter.reset()
        _state.value = CaptureState.Idle
    }

    fun acknowledge() {
        if (_state.value !is CaptureState.Capturing) _state.value = CaptureState.Idle
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

    data class Failed(val message: String) : CaptureState
}

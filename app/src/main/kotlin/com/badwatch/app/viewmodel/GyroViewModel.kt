package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.app.model.GyroReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

private const val TRAIL_CAPACITY = 48
private const val MAX_EXPECTED_MAGNITUDE = 8f
private const val CAPTURE_CAPACITY = 12_000

data class GyroUiState(
    val tracking: Boolean = false,
    val latestReading: GyroReading? = null,
    val magnitude: Float? = null,
    val averageMagnitude: Float? = null,
    val peakMagnitude: Float = 0f,
    val magnitudeTrail: List<Float> = emptyList(),
    val intensityZone: IntensityZone = IntensityZone.Idle,
    val consistencyScore: Float = 100f,
    val burstCount: Int = 0,
    val warmUpDurationMillis: Long? = null,
    val focusArea: FocusArea? = null,
    val insights: List<SessionInsight> = emptyList(),
    val sampleCount: Long = 0,
    val lastUpdatedMillis: Long? = null,
    val sessionDurationMillis: Long = 0L,
    val captureEnabled: Boolean = false,
    val captureLabel: String? = null,
    val recordedSampleCount: Int = 0,
    val error: String? = null
)

enum class IntensityZone {
    Idle,
    WarmUp,
    Rally,
    Burst
}

enum class FocusSeverity {
    Info,
    Caution,
    Alert
}

data class FocusArea(
    val title: String,
    val description: String,
    val severity: FocusSeverity
)

data class SessionInsight(
    val id: String,
    val title: String,
    val detail: String,
    val score: Int
)

data class MotionCaptureSample(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float,
    val intensity: IntensityZone,
    val label: String?
)

class GyroViewModel(
    private val sensorStreamProvider: SensorStreamProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(GyroUiState())
    val uiState: StateFlow<GyroUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null
    private var sessionStartMillis: Long? = null
    private var magnitudeSum: Double = 0.0
    private val magnitudeHistory = ArrayDeque<Float>(TRAIL_CAPACITY)
    private var runningMean: Double = 0.0
    private var runningM2: Double = 0.0
    private var burstEvents: Int = 0
    private var warmUpCaptured: Boolean = false
    private var warmUpDuration: Long? = null
    private var lastZone: IntensityZone = IntensityZone.Idle
    private var captureEnabled: Boolean = false
    private var captureLabel: String? = null
    private val captureBuffer = ArrayDeque<MotionCaptureSample>(CAPTURE_CAPACITY)

    fun start() {
        if (collectionJob != null) return
        sessionStartMillis = System.currentTimeMillis()
        magnitudeSum = 0.0
        magnitudeHistory.clear()
        runningMean = 0.0
        runningM2 = 0.0
        burstEvents = 0
        warmUpCaptured = false
        warmUpDuration = null
        lastZone = IntensityZone.Idle
        if (captureEnabled) {
            captureBuffer.clear()
        }
        _uiState.update {
            GyroUiState(
                tracking = true,
                intensityZone = IntensityZone.WarmUp,
                captureEnabled = captureEnabled,
                captureLabel = if (captureEnabled) captureLabel else null,
                recordedSampleCount = 0
            )
        }
        val job = viewModelScope.launch {
            try {
                sensorStreamProvider.sensorStream().collect { reading ->
                    val magnitude = sqrt(
                        reading.x * reading.x +
                            reading.y * reading.y +
                            reading.z * reading.z
                    )
                    magnitudeSum += magnitude.toDouble()
                    magnitudeHistory.addLast(magnitude)
                    if (magnitudeHistory.size > TRAIL_CAPACITY) {
                        magnitudeHistory.removeFirst()
                    }
                    val startMillis = sessionStartMillis ?: reading.timestampMillis
                    sessionStartMillis = startMillis
                    val sampleCount = _uiState.value.sampleCount + 1
                    val average = (magnitudeSum / sampleCount).toFloat()
                    val peak = max(_uiState.value.peakMagnitude, magnitude)
                    val previousMean = runningMean
                    runningMean += (magnitude - runningMean) / sampleCount
                    runningM2 += (magnitude - runningMean) * (magnitude - previousMean)
                    val variance = if (sampleCount > 1) runningM2 / (sampleCount - 1) else 0.0
                    val stdDev = sqrt(variance).toFloat()
                    val consistencyScore = ((1f - (stdDev / MAX_EXPECTED_MAGNITUDE).coerceIn(0f, 1f)) * 100f).coerceIn(0f, 100f)
                    val zone = classifyIntensity(magnitude, average)
                    if (zone == IntensityZone.Burst && lastZone != IntensityZone.Burst) {
                        burstEvents += 1
                    }
                    if (!warmUpCaptured && (zone == IntensityZone.Rally || zone == IntensityZone.Burst)) {
                        warmUpDuration = reading.timestampMillis - startMillis
                        warmUpCaptured = true
                    }
                    lastZone = zone
                    if (captureEnabled) {
                        if (captureBuffer.size >= CAPTURE_CAPACITY) {
                            captureBuffer.removeFirst()
                        }
                        captureBuffer.addLast(
                            MotionCaptureSample(
                                timestampMillis = reading.timestampMillis,
                                x = reading.x,
                                y = reading.y,
                                z = reading.z,
                                magnitude = magnitude,
                                intensity = zone,
                                label = captureLabel
                            )
                        )
                    }
                    val sessionDuration = reading.timestampMillis - startMillis
                    val focusArea = determineFocusArea(
                        consistencyScore = consistencyScore,
                        burstCount = burstEvents,
                        sessionDurationMillis = sessionDuration,
                        warmUpDurationMillis = warmUpDuration
                    )
                    val insights = buildInsights(
                        averageMagnitude = average,
                        peakMagnitude = peak,
                        consistencyScore = consistencyScore,
                        burstCount = burstEvents,
                        sessionDurationMillis = sessionDuration,
                        warmUpDurationMillis = warmUpDuration
                    )
                    _uiState.update { current ->
                        current.copy(
                            tracking = true,
                            latestReading = reading,
                            magnitude = magnitude,
                            averageMagnitude = average,
                            peakMagnitude = peak,
                            magnitudeTrail = magnitudeHistory.toList(),
                            intensityZone = zone,
                            consistencyScore = consistencyScore,
                            burstCount = burstEvents,
                            warmUpDurationMillis = warmUpDuration,
                            focusArea = focusArea,
                            insights = insights,
                            sampleCount = current.sampleCount + 1,
                            lastUpdatedMillis = reading.timestampMillis,
                            sessionDurationMillis = sessionDuration,
                            captureEnabled = captureEnabled,
                            captureLabel = captureLabel,
                            recordedSampleCount = captureBuffer.size,
                            error = null
                        )
                    }
                }
                _uiState.update { current -> current.copy(tracking = false) }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    // stop() or lifecycle canceled the stream; leave state updates to the caller.
                } else {
                    _uiState.update { current ->
                        current.copy(
                            tracking = false,
                            error = throwable.message ?: "Unable to read sensor data"
                        )
                    }
                }
            } finally {
                collectionJob = null
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause == null && !_uiState.value.tracking) {
                return@invokeOnCompletion
            }
            if (cause == null) {
                _uiState.update { current -> current.copy(tracking = false) }
            } else if (cause !is CancellationException) {
                _uiState.update { current ->
                    current.copy(
                        tracking = false,
                        error = cause.message ?: "Unable to read sensor data"
                    )
                }
            }
        }
        collectionJob = job
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
        sessionStartMillis = null
        _uiState.update { current -> current.copy(tracking = false) }
    }

    fun enableDatasetCapture(label: String) {
        captureEnabled = true
        captureLabel = label
        captureBuffer.clear()
        _uiState.update { current ->
            current.copy(
                captureEnabled = true,
                captureLabel = label,
                recordedSampleCount = 0
            )
        }
    }

    fun disableDatasetCapture() {
        captureEnabled = false
        captureLabel = null
        captureBuffer.clear()
        _uiState.update { current ->
            current.copy(
                captureEnabled = false,
                captureLabel = null,
                recordedSampleCount = 0
            )
        }
    }

    fun captureSnapshot(): List<MotionCaptureSample> = captureBuffer.toList()

    fun exportCaptureCsv(): String {
        if (captureBuffer.isEmpty()) return ""
        val header = "timestamp,x,y,z,magnitude,intensity,label\n"
        val rows = captureBuffer.joinToString(separator = "\n") { sample ->
            listOf(
                sample.timestampMillis,
                sample.x,
                sample.y,
                sample.z,
                sample.magnitude,
                sample.intensity.name,
                sample.label.orEmpty()
            ).joinToString(",")
        }
        return buildString {
            append(header)
            append(rows)
            append('\n')
        }
    }

    private fun classifyIntensity(
        magnitude: Float,
        averageMagnitude: Float
    ): IntensityZone {
        return when {
            magnitude < 1.2f && averageMagnitude < 1f -> IntensityZone.Idle
            magnitude < 2.5f -> IntensityZone.WarmUp
            magnitude < 4.5f -> IntensityZone.Rally
            else -> IntensityZone.Burst
        }
    }

    private fun determineFocusArea(
        consistencyScore: Float,
        burstCount: Int,
        sessionDurationMillis: Long,
        warmUpDurationMillis: Long?
    ): FocusArea? {
        if (sessionDurationMillis < 45_000L) return null
        return when {
            consistencyScore < 55f -> FocusArea(
                title = "Smooth Tempo",
                description = "Swing variance is ${consistencyScore.roundToInt()}%. Focus on repeatable arcs before pushing pace.",
                severity = FocusSeverity.Caution
            )
            burstCount <= 1 && sessionDurationMillis > 90_000L -> FocusArea(
                title = "Add Power Plays",
                description = "Only $burstCount burst spike detected. Layer overhead smashes to stress-test form.",
                severity = FocusSeverity.Info
            )
            warmUpDurationMillis != null && warmUpDurationMillis > 120_000L -> FocusArea(
                title = "Faster Warm-up",
                description = "It took ${formatShortDuration(warmUpDurationMillis)} to reach rally pace. Shorten pre-session drills.",
                severity = FocusSeverity.Info
            )
            else -> null
        }
    }

    private fun buildInsights(
        averageMagnitude: Float,
        peakMagnitude: Float,
        consistencyScore: Float,
        burstCount: Int,
        sessionDurationMillis: Long,
        warmUpDurationMillis: Long?
    ): List<SessionInsight> {
        val insights = mutableListOf<SessionInsight>()
        insights += SessionInsight(
            id = "consistency",
            title = "Consistency",
            detail = "${consistencyScore.roundToInt()}% steady swing window",
            score = consistencyScore.roundToInt()
        )
        val burstsPerMinute = if (sessionDurationMillis > 0) {
            burstCount * 60_000f / sessionDurationMillis
        } else {
            0f
        }
        val burstScore = min(100f, burstsPerMinute * 20f).roundToInt()
        insights += SessionInsight(
            id = "bursts",
            title = "Explosive Plays",
            detail = String.format(Locale.US, "%.1f/min · %d bursts", burstsPerMinute, burstCount),
            score = burstScore
        )
        val warmUpDetail = warmUpDurationMillis?.let { duration ->
            "Reached rally after ${formatShortDuration(duration)}"
        } ?: "Building baseline tempo"
        val warmUpScore = warmUpDurationMillis?.let { duration ->
            val clamped = min(duration / 1000f, 240f)
            (100f - (clamped / 240f * 100f)).coerceIn(0f, 100f)
        } ?: 100f
        insights += SessionInsight(
            id = "warmup",
            title = "Warm-up Ramp",
            detail = warmUpDetail,
            score = warmUpScore.roundToInt()
        )
        val powerRatio = if (averageMagnitude > 0f) peakMagnitude / averageMagnitude else 0f
        val powerScore = min(100f, powerRatio * 25f).roundToInt()
        insights += SessionInsight(
            id = "power",
            title = "Power Reserve",
            detail = String.format(Locale.US, "Peak %.2f× avg", powerRatio),
            score = powerScore
        )
        return insights
    }

    private fun formatShortDuration(durationMillis: Long): String {
        val minutes = durationMillis / 60_000
        val seconds = (durationMillis % 60_000) / 1_000
        return if (minutes > 0) {
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        } else {
            String.format(Locale.US, "%ds", seconds)
        }
    }
}

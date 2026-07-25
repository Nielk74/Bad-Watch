package com.badwatch.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Streams fused gyroscope + accelerometer + heart-rate samples.
 *
 * Replaces the original gyroscope-only collector, which had three defects that made real
 * analysis impossible:
 *
 *  1. It stamped samples with `System.currentTimeMillis()` read on the delivery thread, so
 *     timing jitter from dispatch was baked into the data. We now derive the epoch time
 *     from `SensorEvent.timestamp`, the monotonic clock the sensor hub itself used.
 *  2. It applied `distinctUntilChanged()`, silently discarding genuinely repeated readings —
 *     which is exactly what a wrist at rest produces, destroying rest-interval detection.
 *  3. It requested `SENSOR_DELAY_GAME` (~50 Hz). A smash lasts well under 100 ms, so 50 Hz
 *     gives only a handful of samples across the whole stroke. We request 100 Hz and let the
 *     hardware FIFO batch delivery, which is both higher fidelity and lower power than
 *     waking the CPU per sample.
 */
class FusedSensorCollector(
    context: Context,
    private val samplingPeriodMicros: Int = DEFAULT_SAMPLING_PERIOD_MICROS,
    private val maxReportLatencyMicros: Int = DEFAULT_MAX_REPORT_LATENCY_MICROS
) : SensorStream {

    private val sensorManager = context.getSystemService(SensorManager::class.java)

    override fun samples(): Flow<SensorSample> = callbackFlow {
        val manager = sensorManager
            ?: throw IllegalStateException("SensorManager unavailable on this device")
        val gyroscope = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: throw IllegalStateException("This watch has no gyroscope; Bad Watch cannot track sessions")

        // Optional sensors: the app degrades rather than failing if they are absent.
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val heartRate = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        // SensorEvent.timestamp is nanoseconds on the monotonic boot clock. Anchor it to
        // epoch time once, so every sample shares a single consistent conversion.
        val bootEpochMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        // Sensor callbacks may be delivered on a different thread than the one that reads
        // them into a sample, so the cross-sensor carry-over values are published atomically.
        val latestAccel = AtomicReference(Vector3.ZERO)
        // Null until the optical sensor gets a lock — never NaN. NaN cannot be encoded as
        // JSON, so a NaN carried into a sample crashes the app the moment it is persisted.
        val latestHeartRate = AtomicReference<HeartRateReading?>(null)
        val latestAccuracy = AtomicInteger(SensorManager.SENSOR_STATUS_UNRELIABLE)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        if (event.values.size < 3) return
                        val heartRate = latestHeartRate.get()
                        val sample = SensorSample(
                            timestampMillis = bootEpochMillis + event.timestamp / 1_000_000L,
                            gyro = Vector3(event.values[0], event.values[1], event.values[2]),
                            heartRateBpm = heartRate?.beatsPerMinute,
                            accel = latestAccel.get(),
                            accuracy = latestAccuracy.get(),
                            heartRateSampleTimestampMillis = heartRate?.timestampMillis
                        )
                        trySend(sample)
                    }

                    Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                        if (event.values.size < 3) return
                        latestAccel.set(Vector3(event.values[0], event.values[1], event.values[2]))
                    }

                    Sensor.TYPE_HEART_RATE -> {
                        val bpm = event.values.firstOrNull() ?: return
                        // The optical sensor reports 0 while it is still acquiring a lock.
                        if (bpm > 0f) {
                            latestHeartRate.set(
                                HeartRateReading(
                                    beatsPerMinute = bpm,
                                    timestampMillis = bootEpochMillis + event.timestamp / 1_000_000L
                                )
                            )
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_GYROSCOPE) latestAccuracy.set(accuracy)
            }
        }

        manager.registerListener(listener, gyroscope, samplingPeriodMicros, maxReportLatencyMicros)
        accelerometer?.let {
            manager.registerListener(listener, it, samplingPeriodMicros, maxReportLatencyMicros)
        }
        heartRate?.let {
            // HR is inherently ~1 Hz; requesting faster only burns battery.
            manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        awaitClose { manager.unregisterListener(listener) }
    }
        // FIFO batching delivers up to 200 ms of samples in one burst, so the consumer needs
        // headroom to absorb a batch without back-pressuring the sensor callback thread.
        .buffer(capacity = 1_024)

    companion object {
        /** 100 Hz. A badminton stroke lasts 80-150 ms, so this yields ~10-15 samples per shot. */
        const val DEFAULT_SAMPLING_PERIOD_MICROS: Int = 10_000

        /**
         * Batch up to 200 ms of samples in the sensor FIFO before waking the CPU. This is the
         * single biggest battery lever in the capture path, and it costs nothing in fidelity
         * because timestamps come from the sensor hub, not from delivery time.
         */
        const val DEFAULT_MAX_REPORT_LATENCY_MICROS: Int = 200_000
    }
}

private data class HeartRateReading(
    val beatsPerMinute: Float,
    val timestampMillis: Long
)

/** Kept separate from the collector so tests and previews can supply synthetic streams. */
interface SensorStream {
    fun samples(): Flow<SensorSample>
}

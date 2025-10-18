package com.badwatch.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.Vector3
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.atomic.AtomicReference

class SensorCollector(
    context: Context
) : SensorStreamProvider {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val heartRate = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val heartRateRef = AtomicReference<Float?>(null)
    private val accuracyRef = AtomicReference(0)

    override fun sensorStream(): Flow<SensorSample> = callbackFlow {
        if (sensorManager == null || gyroscope == null) {
            close(IllegalStateException("Required sensors unavailable"))
            return@callbackFlow
        }

        val hrListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) {
                    heartRateRef.set(event.values[0])
                    accuracyRef.set(event.accuracy)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                accuracyRef.set(accuracy)
            }
        }

        val gyroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val hr = heartRateRef.get() ?: Float.NaN
                val sample = SensorSample(
                    timestampMillis = System.currentTimeMillis(),
                    gyro = Vector3(event.values[0], event.values[1], event.values[2]),
                    heartRateBpm = hr,
                    accuracy = accuracyRef.get()
                )
                trySend(sample).isSuccess
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                accuracyRef.set(accuracy)
            }
        }

        sensorManager.registerListener(
            gyroListener,
            gyroscope,
            SensorManager.SENSOR_DELAY_GAME
        )

        if (heartRate != null) {
            sensorManager.registerListener(
                hrListener,
                heartRate,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        awaitClose {
            sensorManager.unregisterListener(gyroListener)
            if (heartRate != null) {
                sensorManager.unregisterListener(hrListener)
            }
        }
    }
        .distinctUntilChanged()
}

package com.badwatch.app.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.app.model.GyroReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class SensorCollector(
    context: Context
) : SensorStreamProvider {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override fun sensorStream(): Flow<GyroReading> = callbackFlow {
        val manager = sensorManager
        val gyroSensor = gyroscope
        if (manager == null || gyroSensor == null) {
            close(IllegalStateException("Gyroscope sensor unavailable"))
            return@callbackFlow
        }

        val gyroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                val reading = GyroReading(
                    timestampMillis = System.currentTimeMillis(),
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2]
                )
                trySend(reading).isSuccess
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(
            gyroListener,
            gyroSensor,
            SensorManager.SENSOR_DELAY_GAME
        )

        awaitClose {
            manager.unregisterListener(gyroListener)
        }
    }
        .distinctUntilChanged()
}

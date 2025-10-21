package com.badwatch.app.model

/**
 * Lightweight gyroscope reading captured from the watch sensors.
 *
 * @property timestampMillis Epoch timestamp of the sample.
 * @property x Angular velocity around the X axis in rad/s.
 * @property y Angular velocity around the Y axis in rad/s.
 * @property z Angular velocity around the Z axis in rad/s.
 */
data class GyroReading(
    val timestampMillis: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

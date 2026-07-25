package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Combined sensor reading sampled from the watch sensors.
 *
 * Samples are emitted on each gyroscope event, carrying the most recent accelerometer and
 * heart-rate values — the standard fusion approach when sensors run at different rates
 * (gyro/accel ~100 Hz, optical HR ~1 Hz).
 *
 * @property timestampMillis Epoch timestamp, derived from the monotonic sensor clock.
 * @property gyro Angular velocity in rad/s along the device axes.
 * @property accel Linear acceleration in m/s^2, gravity removed. Used for impact and
 *   landing detection; [Vector3.ZERO] when the sensor is unavailable.
 * @property heartRateBpm Current heart rate in beats per minute (NaN if unavailable).
 * @property accuracy Android sensor accuracy constant when available.
 */
@Serializable
data class SensorSample(
    val timestampMillis: Long,
    val gyro: Vector3,
    val heartRateBpm: Float,
    val accel: Vector3 = Vector3.ZERO,
    val accuracy: Int = 0
)

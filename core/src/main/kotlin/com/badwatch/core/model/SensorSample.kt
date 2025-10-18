package com.badwatch.core.model

import kotlinx.serialization.Serializable

/**
 * Combined sensor reading sampled from the watch sensors.
 *
 * @property timestampMillis Epoch timestamp for the reading.
 * @property gyro Angular velocity in rad/s along the device axes.
 * @property heartRateBpm Current heart rate in beats per minute (NaN if unavailable).
 * @property accuracy Android sensor accuracy constant when available.
 */
@Serializable
data class SensorSample(
    val timestampMillis: Long,
    val gyro: Vector3,
    val heartRateBpm: Float,
    val accuracy: Int = 0
)

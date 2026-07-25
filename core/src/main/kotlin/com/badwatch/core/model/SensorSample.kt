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
 * @property heartRateBpm Current heart rate in bpm, or null when there is no reading.
 *   Null rather than NaN: NaN is not representable in JSON, so a NaN sentinel crashes
 *   serialization the moment a sample is persisted without a heart-rate lock.
 * @property accuracy Android sensor accuracy constant when available.
 */
@Serializable
data class SensorSample(
    val timestampMillis: Long,
    val gyro: Vector3,
    val heartRateBpm: Float?,
    val accel: Vector3 = Vector3.ZERO,
    val accuracy: Int = 0,
    /**
     * Timestamp of the underlying optical-sensor reading.
     *
     * A fused sample is emitted for every gyroscope event, so the same ~1 Hz heart-rate
     * reading is carried by roughly one hundred motion samples. Keeping its source time lets
     * aggregation count it once instead of accidentally weighting the final seconds of a
     * session one hundred times over.
     */
    val heartRateSampleTimestampMillis: Long? = null
) {
    init {
        // Fail at the point of construction rather than hours later when the session is
        // written. A NaN here is unencodable as JSON, and the original symptom — the app
        // dying on save and taking a whole drill with it — gave no hint where it came from.
        require(heartRateBpm == null || !heartRateBpm.isNaN()) {
            "heartRateBpm must be null when there is no reading, never NaN"
        }
    }
}

package com.badwatch.core.session

import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.HeartRatePoint
import com.badwatch.core.model.MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.model.heartRateZoneFor
import com.badwatch.core.model.Vector3
import java.util.UUID
import kotlin.math.max

/**
 * Aggregates streaming samples and shot events into live and persisted summaries.
 */
class TrainingSessionAggregator(
    private val baselineHeartRate: Float = 60f,
    private val maxHeartRate: Float = 195f
) {
    private val heartRates = mutableListOf<HeartRatePoint>()
    private val shots = mutableListOf<ShotEvent>()
    private val zoneHistogram = mutableMapOf<HeartRateZone, Int>()
    private var startTimeMillis: Long = 0L
    private var lastSample: SensorSample? = null
    private var accumulatedHeartRate = 0.0
    private var lastHeartRateSourceMillis: Long? = null

    fun reset(startMillis: Long) {
        heartRates.clear()
        shots.clear()
        zoneHistogram.clear()
        startTimeMillis = startMillis
        lastSample = null
        accumulatedHeartRate = 0.0
        lastHeartRateSourceMillis = null
    }

    fun onSample(sample: SensorSample) {
        if (startTimeMillis == 0L) {
            startTimeMillis = sample.timestampMillis
        }
        lastSample = sample
        val hr = sample.heartRateBpm
        if (hr != null && hr > 0f) {
            // Old captures do not carry the optical reading's own timestamp. A one-second
            // bucket is the conservative fallback for those files; new captures use the
            // exact source time and therefore retain every real reading exactly once.
            val sourceMillis = sample.heartRateSampleTimestampMillis
                ?: (sample.timestampMillis / 1_000L) * 1_000L
            if (sourceMillis != lastHeartRateSourceMillis) {
                heartRates += HeartRatePoint(sourceMillis, hr)
                lastHeartRateSourceMillis = sourceMillis
                accumulatedHeartRate += hr.toDouble()
                val zone = heartRateZoneFor(hr, maxHeartRate)
                zoneHistogram[zone] = zoneHistogram.getOrDefault(zone, 0) + 1
            }
        }
    }

    fun onShot(event: ShotEvent) {
        shots += event
    }

    fun snapshot(nowMillis: Long): TrainingSessionSnapshot {
        val duration = max(0L, nowMillis - startTimeMillis)
        // Null, not a baseline stand-in: reporting 60 bpm for a session that recorded no
        // heart rate at all is a quiet lie, and the UI already renders null as "--".
        val hrAverage = if (heartRates.isEmpty()) null else (accumulatedHeartRate / heartRates.size).toFloat()
        val hrMax = heartRates.maxOfOrNull { it.beatsPerMinute }
        val reserve = hrAverage?.let(::heartRateReserve)
        // A wrist heart-rate trace measures cardiovascular intensity. It does not measure
        // technique fatigue or recovery, so those legacy fields remain neutral until a
        // validated signal replaces them. The UI no longer surfaces them.
        val fatigueScore = 0f
        val recoveryScore = 0f
        val effortScore = reserve ?: 0f
        val dominantZone = zoneHistogram.maxByOrNull { it.value }?.key
            ?: heartRateZoneFor(hrAverage ?: baselineHeartRate, maxHeartRate)
        val counts = shots.groupingBy { it.type }.eachCount()
        val totalShots = shots.size
        return TrainingSessionSnapshot(
            startedAtMillis = startTimeMillis,
            durationMillis = duration,
            currentHeartRate = lastSample?.heartRateBpm,
            averageHeartRate = hrAverage,
            maxHeartRate = hrMax,
            totalShots = totalShots,
            lastShot = shots.lastOrNull(),
            shotCounts = counts,
            fatigueScore = fatigueScore,
            effortScore = effortScore,
            recoveryScore = recoveryScore,
            dominantZone = dominantZone,
            lastGyro = lastSample?.gyro ?: Vector3(0f, 0f, 0f),
            heartRateSampleCount = heartRates.size,
            heartRateCoverage = heartRateCoverage(duration),
            averageHeartRateReserve = reserve
        )
    }

    fun buildSession(nowMillis: Long): TrainingSession {
        val duration = max(0L, nowMillis - startTimeMillis)
        val averageHeartRate = recordedAverageHeartRate()
        val reserve = averageHeartRate?.let(::heartRateReserve)
        val coverage = heartRateCoverage(duration)
        val summary = TrainingSummary(
            totalShots = shots.size,
            shotCounts = shots.groupingBy { it.type }.eachCount(),
            durationMillis = duration,
            averageHeartRate = averageHeartRate,
            maxHeartRate = recordedMaxHeartRate(),
            recoveryScore = 0f,
            fatigueScore = 0f,
            effortScore = reserve ?: 0f,
            heartRateZoneHistogram = zoneHistogram.toMap(),
            heartRateSampleCount = heartRates.size,
            heartRateCoverage = coverage,
            averageHeartRateReserve = reserve,
            cardiovascularLoad = reserve
                ?.takeIf { coverage >= MINIMUM_CARDIOVASCULAR_LOAD_COVERAGE }
                ?.times(duration / 60_000f)
        )
        return TrainingSession(
            id = UUID.randomUUID().toString(),
            startedAtMillis = startTimeMillis,
            endedAtMillis = nowMillis,
            summary = summary,
            shots = shots.toList(),
            heartRateTrace = heartRates.toList()
        )
    }

    private fun recordedAverageHeartRate(): Float? =
        if (heartRates.isEmpty()) null else (accumulatedHeartRate / heartRates.size).toFloat()

    private fun recordedMaxHeartRate(): Float? = heartRates.maxOfOrNull { it.beatsPerMinute }

    private fun heartRateReserve(heartRate: Float): Float =
        ((heartRate - baselineHeartRate) / (maxHeartRate - baselineHeartRate)).coerceIn(0f, 1f)

    private fun heartRateCoverage(durationMillis: Long): Float {
        if (durationMillis <= 0L || heartRates.isEmpty()) return 0f
        val expectedReadings = maxOf(1f, durationMillis / 1_000f)
        return (heartRates.size / expectedReadings).coerceIn(0f, 1f)
    }
}

package com.badwatch.core.session

import com.badwatch.core.model.HeartRateZone
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
    private val heartRates = ArrayDeque<Float>()
    private val shots = mutableListOf<ShotEvent>()
    private val zoneHistogram = mutableMapOf<HeartRateZone, Int>()
    private var startTimeMillis: Long = 0L
    private var lastSample: SensorSample? = null
    private var accumulatedHeartRate = 0.0

    fun reset(startMillis: Long) {
        heartRates.clear()
        shots.clear()
        zoneHistogram.clear()
        startTimeMillis = startMillis
        lastSample = null
        accumulatedHeartRate = 0.0
    }

    fun onSample(sample: SensorSample) {
        if (startTimeMillis == 0L) {
            startTimeMillis = sample.timestampMillis
        }
        lastSample = sample
        val hr = sample.heartRateBpm
        if (hr != null && hr > 0f) {
            heartRates.addLast(hr)
            accumulatedHeartRate += hr.toDouble()
            val zone = heartRateZoneFor(hr, maxHeartRate)
            zoneHistogram[zone] = zoneHistogram.getOrDefault(zone, 0) + 1
            if (heartRates.size > MAX_HR_SAMPLES) {
                val removed = heartRates.removeFirst()
                accumulatedHeartRate -= removed.toDouble()
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
        val hrMax = heartRates.maxOrNull()
        val fatigueScore = computeFatigueScore(hrAverage ?: baselineHeartRate, hrMax ?: baselineHeartRate)
        val recoveryScore = computeRecoveryScore()
        val effortScore = computeEffortScore(fatigueScore, hrMax ?: baselineHeartRate)
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
            lastGyro = lastSample?.gyro ?: Vector3(0f, 0f, 0f)
        )
    }

    fun buildSession(nowMillis: Long): TrainingSession {
        val fatigue = computeFatigueScore(
            recordedAverageHeartRate() ?: baselineHeartRate,
            recordedMaxHeartRate() ?: baselineHeartRate
        )
        val summary = TrainingSummary(
            totalShots = shots.size,
            shotCounts = shots.groupingBy { it.type }.eachCount(),
            durationMillis = max(0L, nowMillis - startTimeMillis),
            averageHeartRate = recordedAverageHeartRate(),
            maxHeartRate = recordedMaxHeartRate(),
            recoveryScore = computeRecoveryScore(),
            fatigueScore = fatigue,
            effortScore = computeEffortScore(fatigue, recordedMaxHeartRate() ?: baselineHeartRate),
            heartRateZoneHistogram = zoneHistogram.toMap()
        )
        return TrainingSession(
            id = UUID.randomUUID().toString(),
            startedAtMillis = startTimeMillis,
            endedAtMillis = nowMillis,
            summary = summary,
            shots = shots.toList()
        )
    }

    private fun recordedAverageHeartRate(): Float? =
        if (heartRates.isEmpty()) null else (accumulatedHeartRate / heartRates.size).toFloat()

    private fun recordedMaxHeartRate(): Float? = heartRates.maxOrNull()

    private fun computeFatigueScore(avg: Float, maxHr: Float): Float {
        val loadRatio = (avg - baselineHeartRate) / (maxHeartRate - baselineHeartRate)
        val peakRatio = (maxHr - baselineHeartRate) / (maxHeartRate - baselineHeartRate)
        val normalized = (0.7f * loadRatio + 0.3f * peakRatio).coerceIn(0f, 1f)
        return normalized
    }

    private fun computeRecoveryScore(): Float {
        if (heartRates.size < 4) return 0.5f
        val samples = heartRates.toList()
        val last = samples.takeLast(4)
        val first = samples.take(4)
        val drop = (first.average() - last.average()).toFloat()
        val normalized = (drop / 20f).coerceIn(0f, 1f)
        return normalized
    }

    private fun computeEffortScore(fatigue: Float, maxHr: Float): Float {
        val intensity = (maxHr - baselineHeartRate) / (maxHeartRate - baselineHeartRate)
        return (0.6f * intensity + 0.4f * fatigue).coerceIn(0f, 1f)
    }

    companion object {
        private const val MAX_HR_SAMPLES = 120
    }
}

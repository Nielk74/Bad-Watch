package com.badwatch.core.session

import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.model.heartRateZoneFor
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
        if (!hr.isNaN() && hr > 0f) {
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
        val hrAverage = if (heartRates.isEmpty()) baselineHeartRate else (accumulatedHeartRate / heartRates.size).toFloat()
        val hrMax = heartRates.maxOrNull() ?: baselineHeartRate
        val fatigueScore = computeFatigueScore(hrAverage, hrMax)
        val recoveryScore = computeRecoveryScore()
        val effortScore = computeEffortScore(fatigueScore, hrMax)
        val dominantZone = zoneHistogram.maxByOrNull { it.value }?.key
            ?: heartRateZoneFor(hrAverage, maxHeartRate)
        val counts = shots.groupingBy { it.type }.eachCount()
        val totalShots = shots.size
        return TrainingSessionSnapshot(
            startedAtMillis = startTimeMillis,
            durationMillis = duration,
            currentHeartRate = lastSample?.heartRateBpm ?: Float.NaN,
            averageHeartRate = hrAverage,
            maxHeartRate = hrMax,
            totalShots = totalShots,
            lastShot = shots.lastOrNull(),
            shotCounts = counts,
            fatigueScore = fatigueScore,
            effortScore = effortScore,
            recoveryScore = recoveryScore,
            dominantZone = dominantZone
        )
    }

    fun buildSession(nowMillis: Long): TrainingSession {
        val summary = TrainingSummary(
            totalShots = shots.size,
            shotCounts = shots.groupingBy { it.type }.eachCount(),
            durationMillis = max(0L, nowMillis - startTimeMillis),
            averageHeartRate = recordedAverageHeartRate(),
            maxHeartRate = recordedMaxHeartRate(),
            recoveryScore = computeRecoveryScore(),
            fatigueScore = computeFatigueScore(recordedAverageHeartRate(), recordedMaxHeartRate()),
            effortScore = computeEffortScore(
                computeFatigueScore(recordedAverageHeartRate(), recordedMaxHeartRate()),
                recordedMaxHeartRate()
            ),
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

    private fun recordedAverageHeartRate(): Float =
        if (heartRates.isEmpty()) baselineHeartRate else (accumulatedHeartRate / heartRates.size).toFloat()

    private fun recordedMaxHeartRate(): Float =
        heartRates.maxOrNull() ?: baselineHeartRate

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

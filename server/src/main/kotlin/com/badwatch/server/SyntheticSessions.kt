package com.badwatch.server

import com.badwatch.core.model.HeartRateZone
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.ShotType
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSummary
import com.badwatch.core.session.RallySegmenter
import com.badwatch.core.sync.SessionExport
import java.io.File
import java.util.UUID
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates plausible sessions for tests and for filling a development dashboard.
 *
 * This exists so the dashboard can be built and reviewed without a watch on a court, and
 * so the sync contract has fixtures. It is **not** a data source for anything user-facing:
 * synthetic sessions are only ever written by an explicit developer command.
 */
object SyntheticSessions {

    private val rallySegmenter = RallySegmenter()

    /** Shot mix roughly matching club-level singles play. */
    private val shotMix = listOf(
        ShotType.Clear to 30,
        ShotType.Smash to 22,
        ShotType.Drop to 18,
        ShotType.Drive to 16,
        ShotType.BackhandDrive to 9,
        ShotType.Unknown to 5
    )

    fun session(
        startedAtMillis: Long,
        rallies: Int,
        shotsPerRally: Int,
        random: Random = Random(startedAtMillis)
    ): SessionExport {
        val shots = mutableListOf<ShotEvent>()
        var cursor = startedAtMillis
        // Heart rate is modelled as a slowly drifting resting baseline plus a within-rally
        // ramp that mostly recovers during the gap between points. A naive model that simply
        // ratchets upward on every shot produces a 40+ bpm rise across a session, which is
        // not something a human does — and it made the cardiac-drift insight fire on every
        // single seeded session, misrepresenting how often that rule actually triggers.
        var restingBase = 96f

        repeat(rallies) {
            // Rally length is heavily right-skewed in badminton: most points are short,
            // with an occasional long attritional exchange. A uniform length would make the
            // histogram meaningless.
            val length = (shotsPerRally * (0.4f + random.nextFloat() * 1.1f)).roundToInt()
                .coerceAtLeast(1)
                .let { if (random.nextInt(12) == 0) it * 2 + random.nextInt(6) else it }
            repeat(length) { shotIndex ->
                val type = weightedShot(random)
                // Climbs through the rally, steeply at first then flattening.
                val ramp = 58f * (1f - exp(-shotIndex / 4.0)).toFloat()
                val heartRate = (restingBase + ramp + random.nextFloat() * 4f - 2f)
                    .coerceIn(90f, 190f)
                shots += ShotEvent(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    timestampMillis = cursor,
                    confidence = 0.55f + random.nextFloat() * 0.4f,
                    peakAngularVelocity = peakFor(type, random),
                    heartRateBpm = heartRate,
                    swingDurationMillis = (120 + random.nextInt(140)).toLong()
                )
                // Shots inside a rally land 0.7-1.5 s apart.
                cursor += 700 + random.nextInt(800)
            }
            // Between points: retrieve shuttle, walk back, serve. Recovery is nearly
            // complete early on and degrades slightly as the session wears on — roughly
            // 8-12 bpm of genuine drift over an hour.
            cursor += 8_000 + random.nextInt(14_000)
            restingBase = (restingBase + 0.35f + random.nextFloat() * 0.3f).coerceAtMost(132f)
        }

        val endedAt = cursor
        val heartRates = shots.mapNotNull { it.heartRateBpm }
        val summary = TrainingSummary(
            totalShots = shots.size,
            shotCounts = shots.groupingBy { it.type }.eachCount(),
            durationMillis = endedAt - startedAtMillis,
            averageHeartRate = heartRates.average().toFloat(),
            maxHeartRate = heartRates.max(),
            recoveryScore = 0.4f + random.nextFloat() * 0.4f,
            fatigueScore = 0.3f + random.nextFloat() * 0.5f,
            effortScore = 0.4f + random.nextFloat() * 0.4f,
            heartRateZoneHistogram = mapOf(
                HeartRateZone.Tempo to (shots.size * 0.5f).roundToInt(),
                HeartRateZone.Threshold to (shots.size * 0.35f).roundToInt(),
                HeartRateZone.VO2Max to (shots.size * 0.15f).roundToInt()
            )
        )

        val session = TrainingSession(
            id = UUID.randomUUID().toString(),
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAt,
            summary = summary,
            shots = shots
        )

        return SessionExport(
            deviceId = "synthetic-device",
            appVersion = "dev",
            profile = PlayerProfile(),
            session = session,
            rallyProfile = rallySegmenter.segment(shots, sessionEndMillis = endedAt),
            notes = mapOf("source" to "synthetic")
        )
    }

    private fun weightedShot(random: Random): ShotType {
        val total = shotMix.sumOf { it.second }
        var pick = random.nextInt(total)
        for ((type, weight) in shotMix) {
            pick -= weight
            if (pick < 0) return type
        }
        return ShotType.Clear
    }

    private fun peakFor(type: ShotType, random: Random): Float = when (type) {
        ShotType.Smash -> 6.2f + random.nextFloat() * 2.4f
        ShotType.Clear -> 4.6f + random.nextFloat() * 1.2f
        ShotType.Drop -> 2.2f + random.nextFloat() * 1.4f
        ShotType.Drive -> 3.2f + random.nextFloat() * 1.4f
        ShotType.BackhandDrive -> 2.6f + random.nextFloat() * 1.2f
        ShotType.Unknown -> 1.5f + random.nextFloat() * 2f
    }
}

/**
 * Developer command: fills a data directory with a few weeks of synthetic sessions so the
 * dashboard has something to render.
 *
 * `./gradlew :server:seedDemoData`
 */
fun main(args: Array<String>) {
    val directory = File(args.firstOrNull() ?: "badwatch-data")
    val repository = SessionRepository(directory)
    val day = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    val random = Random(7)

    // Three sessions a week for six weeks, with a deliberate volume spike near the end so
    // the acute:chronic chart has something interesting to show.
    var written = 0
    for (dayOffset in 42 downTo 0) {
        if (dayOffset % 7 !in setOf(0, 2, 4)) continue
        val spike = dayOffset in 2..8
        val rallies = if (spike) 26 + random.nextInt(10) else 14 + random.nextInt(10)
        val shotsPerRally = 5 + random.nextInt(6)
        val start = now - dayOffset * day + (18L * 60 * 60 * 1000)
        repository.save(SyntheticSessions.session(start, rallies, shotsPerRally, random))
        written++
    }
    println("[bad-watch] Seeded $written synthetic sessions into ${directory.absolutePath}")
}

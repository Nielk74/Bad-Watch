package com.badwatch.core.session

import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.RallyProfile
import com.badwatch.core.model.SensorSample
import com.badwatch.core.model.ShotEvent
import com.badwatch.core.model.TrainingSession
import com.badwatch.core.model.TrainingSessionSnapshot
import com.badwatch.core.pipeline.ShotDetectionPipeline

/**
 * The single entry point the app layer drives during a live session.
 *
 * Previously the detection pipeline, the aggregator and the classifier existed in `:core`
 * but nothing joined them up, and the app talked to none of them. This class is that join:
 * feed it sensor samples, read snapshots for the HUD, and call [finish] to get a persisted
 * session. It stays platform-free so the whole recording path is unit-testable on the JVM
 * without an emulator.
 */
class SessionRecorder(
    private val profile: PlayerProfile = PlayerProfile(),
    classifier: ShotClassifier = ShotClassifier(handedness = profile.handedness),
    private val pipeline: ShotDetectionPipeline = ShotDetectionPipeline(classifier),
    private val aggregator: TrainingSessionAggregator = TrainingSessionAggregator(
        baselineHeartRate = profile.restingHeartRate,
        maxHeartRate = profile.maxHeartRate
    ),
    private val rallySegmenter: RallySegmenter = RallySegmenter()
) {
    private val shots = mutableListOf<ShotEvent>()
    private var startMillis: Long = 0L
    private var running: Boolean = false
    private var sampleCount: Long = 0L

    val isRunning: Boolean get() = running
    val shotCount: Int get() = shots.size
    val samplesProcessed: Long get() = sampleCount

    fun start(nowMillis: Long) {
        shots.clear()
        pipeline.reset()
        aggregator.reset(nowMillis)
        startMillis = nowMillis
        sampleCount = 0L
        running = true
    }

    /**
     * Feeds one fused sensor sample through the pipeline.
     *
     * @return the shot detected by this sample, or null. Returning the event lets the app
     *   fire haptics immediately rather than diffing snapshots.
     */
    fun onSample(sample: SensorSample): ShotEvent? {
        if (!running) return null
        sampleCount++
        aggregator.onSample(sample)
        val shot = pipeline.addSample(sample) ?: return null
        shots += shot
        aggregator.onShot(shot)
        return shot
    }

    fun snapshot(nowMillis: Long): TrainingSessionSnapshot = aggregator.snapshot(nowMillis)

    /** Live rally structure, recomputed on demand. Cheap: rallies are tens of items. */
    fun rallyProfile(nowMillis: Long): RallyProfile =
        rallySegmenter.segment(shots, sessionEndMillis = nowMillis)

    /**
     * Ends the session and produces the persistable record.
     *
     * @return the session, or null when nothing meaningful was captured.
     */
    fun finish(nowMillis: Long): RecordedSession? {
        if (!running) return null
        running = false
        if (sampleCount == 0L) return null
        val session = aggregator.buildSession(nowMillis)
        return RecordedSession(
            session = session,
            rallyProfile = rallySegmenter.segment(shots, sessionEndMillis = nowMillis),
            profile = profile
        )
    }

    fun abort() {
        running = false
        shots.clear()
        pipeline.reset()
        sampleCount = 0L
    }
}

/** A finished session plus its derived analysis, ready to persist and export. */
data class RecordedSession(
    val session: TrainingSession,
    val rallyProfile: RallyProfile,
    val profile: PlayerProfile
)

package com.badwatch.app.health

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A heart-rate observation with the source sensor's own wall-clock timestamp. */
data class HeartRateReading(
    val beatsPerMinute: Float,
    val timestampMillis: Long
)

/** Read-only seam used by the high-rate motion collector. */
interface HeartRateReadingProvider {
    fun latestReading(): HeartRateReading?
}

/**
 * Observable state for the optional Health Services part of a recording.
 *
 * Every non-[Active] state is deliberately non-fatal: Bad Watch is a motion tracker first,
 * and a denied health permission or unsupported optical sensor must never lose a session.
 */
sealed interface ExerciseHeartRateState {
    data object Idle : ExerciseHeartRateState
    data object Starting : ExerciseHeartRateState
    data class Active(val reattachedAfterProcessRestart: Boolean) : ExerciseHeartRateState
    data object UnsupportedExercise : ExerciseHeartRateState
    data object UnsupportedHeartRate : ExerciseHeartRateState
    data object PermissionDenied : ExerciseHeartRateState
    data object Busy : ExerciseHeartRateState
    data object EndedExternally : ExerciseHeartRateState
    data class Failed(val reason: String) : ExerciseHeartRateState
}

internal data class ExerciseHeartRateCapabilities(
    val badminton: Boolean,
    val heartRate: Boolean
)

internal enum class ExistingExercise {
    NONE,
    OWNED_BADMINTON,
    OWNED_OTHER,
    OTHER_APP
}

internal interface ExerciseHeartRateListener {
    fun onReading(beatsPerMinute: Float, timestampMillis: Long)
    fun onUnavailable()
    fun onEnded()
    fun onFailure(error: Throwable)
}

/** Platform boundary, kept small so all lifecycle decisions can be tested on the JVM. */
internal interface ExerciseHeartRateBackend {
    suspend fun capabilities(): ExerciseHeartRateCapabilities
    suspend fun existingExercise(): ExistingExercise
    fun register(listener: ExerciseHeartRateListener)
    suspend fun startBadmintonExercise()
    suspend fun endExercise()
    suspend fun unregister()
}

/**
 * Owns one Health Services exercise for the lifetime of a Bad Watch session.
 *
 * The coordinator checks capabilities before requesting anything, never supersedes another
 * app's workout, and reattaches to this app's compatible exercise after a short process restart.
 * Callback generations prevent late events from a previous registration contaminating the next
 * session. Replayed and out-of-order points are rejected by source timestamp before they reach the
 * 100 Hz motion stream; the core aggregator independently deduplicates them as a second guard.
 */
class ExerciseHeartRateSession internal constructor(
    private val backend: ExerciseHeartRateBackend
) : HeartRateReadingProvider {

    private val lifecycleMutex = Mutex()
    private val latest = AtomicReference<HeartRateReading?>(null)
    private val latestTimestamp = AtomicLong(NO_TIMESTAMP)
    private val callbackGeneration = AtomicLong(0L)
    private val _state = MutableStateFlow<ExerciseHeartRateState>(ExerciseHeartRateState.Idle)

    val state: StateFlow<ExerciseHeartRateState> = _state.asStateFlow()

    private var callbackRegistered = false
    @Volatile
    private var ownsExercise = false

    override fun latestReading(): HeartRateReading? = latest.get()

    suspend fun start(): ExerciseHeartRateState = lifecycleMutex.withLock {
        if (_state.value is ExerciseHeartRateState.Active ||
            _state.value is ExerciseHeartRateState.Starting
        ) {
            return@withLock _state.value
        }

        resetSessionReadings()
        _state.value = ExerciseHeartRateState.Starting

        val capabilities = try {
            backend.capabilities()
        } catch (_: SecurityException) {
            return@withLock finishWithoutExercise(ExerciseHeartRateState.PermissionDenied)
        } catch (error: Throwable) {
            return@withLock finishWithoutExercise(error.asFailure())
        }

        if (!capabilities.badminton) {
            return@withLock finishWithoutExercise(ExerciseHeartRateState.UnsupportedExercise)
        }
        if (!capabilities.heartRate) {
            return@withLock finishWithoutExercise(ExerciseHeartRateState.UnsupportedHeartRate)
        }

        val existing = try {
            backend.existingExercise()
        } catch (_: SecurityException) {
            return@withLock finishWithoutExercise(ExerciseHeartRateState.PermissionDenied)
        } catch (error: Throwable) {
            return@withLock finishWithoutExercise(error.asFailure())
        }

        if (existing == ExistingExercise.OTHER_APP || existing == ExistingExercise.OWNED_OTHER) {
            return@withLock finishWithoutExercise(ExerciseHeartRateState.Busy)
        }

        val generation = callbackGeneration.incrementAndGet()
        val listener = listenerFor(generation)
        try {
            // An exercise owned by this app is ours to clean up even when callback
            // re-registration fails after a process restart.
            ownsExercise = existing == ExistingExercise.OWNED_BADMINTON
            backend.register(listener)
            callbackRegistered = true
            // Registration failures are normally asynchronous, but the backend is allowed
            // to report one inline. Do not start an exercise after that callback invalidated
            // this generation.
            if (generation != callbackGeneration.get()) {
                cleanUpFailedStart()
                return@withLock _state.value
            }
            if (existing == ExistingExercise.NONE) {
                // Mark ownership before the call so a partially successful remote start is
                // still followed by a best-effort end if the binder reports an error.
                ownsExercise = true
                backend.startBadmintonExercise()
            }
            if (generation != callbackGeneration.get()) {
                cleanUpFailedStart()
                return@withLock _state.value
            }
            ExerciseHeartRateState.Active(
                reattachedAfterProcessRestart = existing == ExistingExercise.OWNED_BADMINTON
            ).also { _state.value = it }
        } catch (_: SecurityException) {
            cleanUpFailedStart()
            finishWithoutExercise(ExerciseHeartRateState.PermissionDenied)
        } catch (error: Throwable) {
            cleanUpFailedStart()
            finishWithoutExercise(error.asFailure())
        }
    }

    suspend fun stop() = lifecycleMutex.withLock {
        // Invalidate callbacks before asking the remote service to end; a late update can no
        // longer become the first reading of a future Bad Watch session.
        callbackGeneration.incrementAndGet()
        resetSessionReadings()

        if (ownsExercise) {
            runCatching { backend.endExercise() }
        }
        if (callbackRegistered) {
            runCatching { backend.unregister() }
        }
        ownsExercise = false
        callbackRegistered = false
        _state.value = ExerciseHeartRateState.Idle
    }

    private fun listenerFor(generation: Long): ExerciseHeartRateListener =
        object : ExerciseHeartRateListener {
            override fun onReading(beatsPerMinute: Float, timestampMillis: Long) {
                if (generation != callbackGeneration.get()) return
                acceptReading(beatsPerMinute, timestampMillis)
            }

            override fun onUnavailable() {
                // Keep the timestamp watermark. Health Services may replay a batch after an
                // acquiring/unavailable transition, and an older point must stay rejected.
                if (generation == callbackGeneration.get()) clearLatestReading()
            }

            override fun onEnded() {
                if (generation != callbackGeneration.get()) return
                callbackGeneration.compareAndSet(generation, generation + 1L)
                clearLatestReading()
                ownsExercise = false
                _state.value = ExerciseHeartRateState.EndedExternally
            }

            override fun onFailure(error: Throwable) {
                if (generation != callbackGeneration.get()) return
                callbackGeneration.compareAndSet(generation, generation + 1L)
                clearLatestReading()
                _state.value = when (error) {
                    is SecurityException -> ExerciseHeartRateState.PermissionDenied
                    else -> error.asFailure()
                }
            }
        }

    private fun acceptReading(beatsPerMinute: Float, timestampMillis: Long) {
        if (!beatsPerMinute.isFinite() || beatsPerMinute <= 0f || timestampMillis <= 0L) return

        while (true) {
            val previous = latestTimestamp.get()
            if (timestampMillis <= previous) return
            if (latestTimestamp.compareAndSet(previous, timestampMillis)) {
                latest.set(HeartRateReading(beatsPerMinute, timestampMillis))
                return
            }
        }
    }

    private suspend fun cleanUpFailedStart() {
        callbackGeneration.incrementAndGet()
        if (ownsExercise) runCatching { backend.endExercise() }
        if (callbackRegistered) runCatching { backend.unregister() }
        callbackRegistered = false
        ownsExercise = false
        resetSessionReadings()
    }

    private fun finishWithoutExercise(state: ExerciseHeartRateState): ExerciseHeartRateState {
        ownsExercise = false
        callbackRegistered = false
        _state.value = state
        return state
    }

    private fun clearLatestReading() {
        latest.set(null)
    }

    private fun resetSessionReadings() {
        clearLatestReading()
        latestTimestamp.set(NO_TIMESTAMP)
    }

    private fun Throwable.asFailure(): ExerciseHeartRateState.Failed =
        ExerciseHeartRateState.Failed(message?.takeIf(String::isNotBlank) ?: javaClass.simpleName)

    private companion object {
        const val NO_TIMESTAMP = Long.MIN_VALUE
    }
}

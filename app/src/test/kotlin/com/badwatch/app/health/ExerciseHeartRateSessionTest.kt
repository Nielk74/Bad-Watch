package com.badwatch.app.health

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExerciseHeartRateSessionTest {

    @Test
    fun supportedSessionStartsBadmintonAndKeepsOnlyNewestUniqueReading() = runTest {
        val backend = FakeBackend()
        val session = ExerciseHeartRateSession(backend)

        assertThat(session.start()).isEqualTo(
            ExerciseHeartRateState.Active(reattachedAfterProcessRestart = false)
        )
        assertThat(backend.registerCalls).isEqualTo(1)
        assertThat(backend.startCalls).isEqualTo(1)

        backend.emit(142f, 10_000L)
        backend.emit(180f, 10_000L) // replay of the same source observation
        backend.emit(90f, 9_000L) // late delivery from an older batch

        assertThat(session.latestReading()).isEqualTo(HeartRateReading(142f, 10_000L))

        backend.emit(146f, 11_000L)
        assertThat(session.latestReading()).isEqualTo(HeartRateReading(146f, 11_000L))
    }

    @Test
    fun unsupportedBadmintonFallsBackWithoutRegisteringOrStarting() = runTest {
        val backend = FakeBackend(
            capabilities = ExerciseHeartRateCapabilities(badminton = false, heartRate = false)
        )

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.UnsupportedExercise)
        assertThat(backend.registerCalls).isEqualTo(0)
        assertThat(backend.startCalls).isEqualTo(0)
    }

    @Test
    fun badmintonWithoutHeartRateFallsBackWithoutStartingExercise() = runTest {
        val backend = FakeBackend(
            capabilities = ExerciseHeartRateCapabilities(badminton = true, heartRate = false)
        )

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.UnsupportedHeartRate)
        assertThat(backend.registerCalls).isEqualTo(0)
        assertThat(backend.startCalls).isEqualTo(0)
    }

    @Test
    fun permissionDenialIsNonFatalAndDoesNotStartExercise() = runTest {
        val backend = FakeBackend(capabilityFailure = SecurityException("denied"))

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.PermissionDenied)
        assertThat(backend.startCalls).isEqualTo(0)
    }

    @Test
    fun anotherAppsExerciseIsNeverSuperseded() = runTest {
        val backend = FakeBackend(existing = ExistingExercise.OTHER_APP)

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.Busy)
        assertThat(backend.registerCalls).isEqualTo(0)
        assertThat(backend.startCalls).isEqualTo(0)
        assertThat(backend.endCalls).isEqualTo(0)
    }

    @Test
    fun compatibleOwnedExerciseIsReattachedThenEndedOnStop() = runTest {
        val backend = FakeBackend(existing = ExistingExercise.OWNED_BADMINTON)
        val session = ExerciseHeartRateSession(backend)

        assertThat(session.start()).isEqualTo(
            ExerciseHeartRateState.Active(reattachedAfterProcessRestart = true)
        )
        assertThat(backend.registerCalls).isEqualTo(1)
        assertThat(backend.startCalls).isEqualTo(0)

        backend.emit(130f, 12_000L)
        session.stop()

        assertThat(session.state.value).isEqualTo(ExerciseHeartRateState.Idle)
        assertThat(session.latestReading()).isNull()
        assertThat(backend.endCalls).isEqualTo(1)
        assertThat(backend.unregisterCalls).isEqualTo(1)
    }

    @Test
    fun unavailableAndEndedCallbacksClearAFormerOpticalLock() = runTest {
        val backend = FakeBackend()
        val session = ExerciseHeartRateSession(backend)
        session.start()
        backend.emit(151f, 15_000L)

        backend.unavailable()
        assertThat(session.latestReading()).isNull()

        backend.emit(149f, 14_000L) // replay after an availability transition
        assertThat(session.latestReading()).isNull()

        backend.emit(152f, 16_000L)
        backend.ended()
        assertThat(session.latestReading()).isNull()
        assertThat(session.state.value).isEqualTo(ExerciseHeartRateState.EndedExternally)
    }

    @Test
    fun inlineCallbackRegistrationFailureCannotStartAnExercise() = runTest {
        val backend = FakeBackend(registerFailure = SecurityException("revoked"))

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.PermissionDenied)
        assertThat(backend.startCalls).isEqualTo(0)
        assertThat(backend.unregisterCalls).isEqualTo(1)
    }

    @Test
    fun callbackFailureDuringRemoteStartCannotLeaveAnOwnedExerciseBehind() = runTest {
        val backend = FakeBackend(startCallbackFailure = IllegalStateException("service died"))

        val state = ExerciseHeartRateSession(backend).start()

        assertThat(state).isEqualTo(ExerciseHeartRateState.Failed("service died"))
        assertThat(backend.startCalls).isEqualTo(1)
        assertThat(backend.endCalls).isEqualTo(1)
        assertThat(backend.unregisterCalls).isEqualTo(1)
    }
}

private class FakeBackend(
    private val capabilities: ExerciseHeartRateCapabilities =
        ExerciseHeartRateCapabilities(badminton = true, heartRate = true),
    private val existing: ExistingExercise = ExistingExercise.NONE,
    private val capabilityFailure: Throwable? = null,
    private val registerFailure: Throwable? = null,
    private val startCallbackFailure: Throwable? = null
) : ExerciseHeartRateBackend {
    var registerCalls = 0
    var startCalls = 0
    var endCalls = 0
    var unregisterCalls = 0
    private var listener: ExerciseHeartRateListener? = null

    override suspend fun capabilities(): ExerciseHeartRateCapabilities {
        capabilityFailure?.let { throw it }
        return capabilities
    }

    override suspend fun existingExercise(): ExistingExercise = existing

    override fun register(listener: ExerciseHeartRateListener) {
        registerCalls += 1
        this.listener = listener
        registerFailure?.let(listener::onFailure)
    }

    override suspend fun startBadmintonExercise() {
        startCalls += 1
        startCallbackFailure?.let { listener?.onFailure(it) }
    }

    override suspend fun endExercise() {
        endCalls += 1
    }

    override suspend fun unregister() {
        unregisterCalls += 1
    }

    fun emit(beatsPerMinute: Float, timestampMillis: Long) {
        listener?.onReading(beatsPerMinute, timestampMillis)
    }

    fun unavailable() {
        listener?.onUnavailable()
    }

    fun ended() {
        listener?.onEnded()
    }
}

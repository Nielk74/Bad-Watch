package com.badwatch.app.service

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionServiceCommandTest {

    @Test
    fun failedSessionAcknowledgementStopsOwnersBeforeClearingFailure() = runTest {
        val events = mutableListOf<String>()

        runFailedSessionAcknowledgement(
            stopHeartRate = { events += "heart-rate" },
            stopService = { events += "service" },
            acknowledgeController = { events += "acknowledge" }
        )

        assertThat(events).containsExactly(
            "heart-rate",
            "service",
            "acknowledge"
        ).inOrder()
    }

    @Test
    fun unexpectedHeartRateCleanupFailureStillStopsService() = runTest {
        val events = mutableListOf<String>()

        val failure = try {
            stopFailedSessionService(
                stopHeartRate = {
                    events += "heart-rate"
                    throw IOException("health service unavailable")
                },
                stopService = { events += "service" }
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(events).containsExactly("heart-rate", "service").inOrder()
    }

    @Test
    fun successfulCaptureRetryEnqueuesBeforeStoppingService() = runTest {
        val events = mutableListOf<String>()

        val saved = retryFailedCaptureSave(
            retrySave = {
                events += "retry"
                "stable-capture"
            },
            enqueueSync = { events += "enqueue" },
            stopService = { events += "service" }
        )

        assertThat(saved).isEqualTo("stable-capture")
        assertThat(events).containsExactly("retry", "enqueue", "service").inOrder()
    }

    @Test
    fun failedCaptureRetryDoesNotEnqueueAndStillStopsService() = runTest {
        val events = mutableListOf<String>()

        val saved = retryFailedCaptureSave<String>(
            retrySave = {
                events += "retry"
                null
            },
            enqueueSync = { events += "enqueue" },
            stopService = { events += "service" }
        )

        assertThat(saved).isNull()
        assertThat(events).containsExactly("retry", "service").inOrder()
    }

    @Test
    fun unexpectedCaptureRetryFailureStillStopsService() = runTest {
        val events = mutableListOf<String>()

        val failure = try {
            retryFailedCaptureSave<String>(
                retrySave = {
                    events += "retry"
                    throw IOException("store unavailable")
                },
                enqueueSync = { events += "enqueue" },
                stopService = { events += "service" }
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(events).containsExactly("retry", "service").inOrder()
    }
}

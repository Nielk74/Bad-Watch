@file:android.annotation.SuppressLint("RestrictedApi")

package com.badwatch.app.health

import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.clearUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import java.time.Instant

/** Android implementation of the narrow, JVM-testable Health Services boundary. */
internal class HealthServicesExerciseBackend(
    context: Context
) : ExerciseHeartRateBackend {

    private val exerciseClient: ExerciseClient = HealthServices.getClient(context).exerciseClient
    private var callback: ExerciseUpdateCallback? = null
    private var bootInstant: Instant = bootInstantNow()

    override suspend fun capabilities(): ExerciseHeartRateCapabilities {
        val capabilities = exerciseClient.getCapabilities()
        if (ExerciseType.BADMINTON !in capabilities.supportedExerciseTypes) {
            return ExerciseHeartRateCapabilities(badminton = false, heartRate = false)
        }
        val badminton = capabilities.getExerciseTypeCapabilities(ExerciseType.BADMINTON)
        return ExerciseHeartRateCapabilities(
            badminton = true,
            heartRate = DataType.HEART_RATE_BPM in badminton.supportedDataTypes
        )
    }

    // Lint 8.13 incorrectly reports each of these three @IntDef members as not belonging to
    // the same three-member set. Keep the suppression on this exhaustive mapping only.
    @android.annotation.SuppressLint("WrongConstant")
    override suspend fun existingExercise(): ExistingExercise {
        val info = exerciseClient.getCurrentExerciseInfo()
        return when (info.exerciseTrackedStatus) {
            ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> ExistingExercise.NONE
            ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> ExistingExercise.OTHER_APP
            ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> {
                if (info.exerciseType == ExerciseType.BADMINTON) {
                    ExistingExercise.OWNED_BADMINTON
                } else {
                    ExistingExercise.OWNED_OTHER
                }
            }
            else -> ExistingExercise.OWNED_OTHER
        }
    }

    override fun register(listener: ExerciseHeartRateListener) {
        check(callback == null) { "Health Services callback already registered" }
        bootInstant = bootInstantNow()
        val registered = object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                // Updates may contain a batch. Preserve each optical point's sensor-clock time;
                // using callback arrival time would fold Bluetooth/CPU scheduling jitter into
                // the trace and defeat the core aggregator's exact deduplication.
                update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                    .sortedBy { it.timeDurationFromBoot }
                    .forEach { point ->
                        listener.onReading(
                            beatsPerMinute = point.value.toFloat(),
                            timestampMillis = point.getTimeInstant(bootInstant).toEpochMilli()
                        )
                    }
                if (update.exerciseStateInfo.state.isEnded) listener.onEnded()
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onRegistered() = Unit

            override fun onRegistrationFailed(throwable: Throwable) {
                listener.onFailure(throwable)
            }

            override fun onAvailabilityChanged(
                dataType: DataType<*, *>,
                availability: Availability
            ) {
                if (dataType == DataType.HEART_RATE_BPM &&
                    availability is DataTypeAvailability &&
                    availability != DataTypeAvailability.AVAILABLE
                ) {
                    listener.onUnavailable()
                }
            }
        }
        callback = registered
        try {
            exerciseClient.setUpdateCallback(registered)
        } catch (error: Throwable) {
            callback = null
            throw error
        }
    }

    override suspend fun startBadmintonExercise() {
        exerciseClient.startExercise(
            ExerciseConfig(
                exerciseType = ExerciseType.BADMINTON,
                dataTypes = setOf(DataType.HEART_RATE_BPM),
                isAutoPauseAndResumeEnabled = false,
                isGpsEnabled = false,
                exerciseGoals = emptyList()
            )
        )
    }

    override suspend fun endExercise() {
        exerciseClient.endExercise()
    }

    override suspend fun unregister() {
        val registered = callback ?: return
        try {
            exerciseClient.clearUpdateCallback(registered)
        } finally {
            callback = null
        }
    }

    private fun bootInstantNow(): Instant = Instant.ofEpochMilli(
        System.currentTimeMillis() - SystemClock.elapsedRealtime()
    )
}

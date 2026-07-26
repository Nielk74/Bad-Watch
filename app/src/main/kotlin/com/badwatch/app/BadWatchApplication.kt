package com.badwatch.app

import android.app.Application
import com.badwatch.app.complication.WeeklyHitsComplicationUpdates
import com.badwatch.app.data.ActiveSessionJournal
import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.MatchStore
import com.badwatch.app.data.SessionStore
import com.badwatch.app.data.ShadowRoutineStore
import com.badwatch.app.data.SettingsStore
import com.badwatch.app.domain.CaptureController
import com.badwatch.app.domain.StoredCaptureRuntimeSettings
import com.badwatch.app.domain.MatchController
import com.badwatch.app.domain.SessionController
import com.badwatch.app.domain.ShadowRoutineController
import com.badwatch.app.domain.StoredSessionRuntimeSettings
import com.badwatch.app.health.ExerciseHeartRateSession
import com.badwatch.app.health.HealthServicesExerciseBackend
import com.badwatch.app.sensors.FusedSensorCollector
import com.badwatch.app.sensors.SensorStream
import com.badwatch.app.sync.DashboardClient
import com.badwatch.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class BadWatchApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

/**
 * Hand-rolled dependency container. The graph is small enough that a DI framework would
 * cost more than it saves, and it keeps the whole thing readable in one screen.
 */
interface AppContainer {
    val heartRateSession: ExerciseHeartRateSession
    val sensorStream: SensorStream
    val sessionStore: SessionStore
    val activeSessionJournal: ActiveSessionJournal
    val captureStore: CaptureStore
    val matchStore: MatchStore
    val shadowRoutineStore: ShadowRoutineStore
    val settingsStore: SettingsStore
    val sessionController: SessionController
    val captureController: CaptureController
    val matchController: MatchController
    val shadowRoutineController: ShadowRoutineController
    val dashboardClient: DashboardClient
    fun enqueueSync()
}

private class DefaultAppContainer(
    private val application: Application
) : AppContainer {

    /**
     * Application-scoped, because a recording session must survive the Activity. Sessions
     * end on user action or process death, never on a screen timeout.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val heartRateSession: ExerciseHeartRateSession by lazy {
        ExerciseHeartRateSession(HealthServicesExerciseBackend(application))
    }

    override val sensorStream: SensorStream by lazy {
        FusedSensorCollector(
            context = application,
            heartRateProvider = heartRateSession
        )
    }

    override val sessionStore: SessionStore by lazy {
        SessionStore(
            directory = File(application.filesDir, "sessions"),
            onSessionsChanged = { WeeklyHitsComplicationUpdates.requestAll(application) }
        )
    }

    override val activeSessionJournal: ActiveSessionJournal by lazy {
        ActiveSessionJournal(File(application.filesDir, "active-session/journal.json"))
    }

    override val captureStore: CaptureStore by lazy {
        CaptureStore(File(application.filesDir, "captures"))
    }

    override val matchStore: MatchStore by lazy {
        MatchStore(File(application.filesDir, "match/active.json"))
    }

    override val shadowRoutineStore: ShadowRoutineStore by lazy {
        ShadowRoutineStore(File(application.filesDir, "training/active-shadow.json"))
    }

    override val settingsStore: SettingsStore by lazy { SettingsStore(application) }

    override val dashboardClient: DashboardClient by lazy { DashboardClient() }

    override val sessionController: SessionController by lazy {
        SessionController(
            sensorStream = sensorStream,
            sessionStore = sessionStore,
            runtimeSettings = StoredSessionRuntimeSettings(settingsStore),
            activeSessionJournal = activeSessionJournal,
            appVersion = BuildConfig.VERSION_NAME,
            scope = applicationScope
        )
    }

    override val captureController: CaptureController by lazy {
        CaptureController(
            sensorStream = sensorStream,
            captureStore = captureStore,
            runtimeSettings = StoredCaptureRuntimeSettings(settingsStore),
            appVersion = BuildConfig.VERSION_NAME,
            scope = applicationScope
        )
    }

    override val matchController: MatchController by lazy {
        MatchController(
            store = matchStore,
            scope = applicationScope
        )
    }

    override val shadowRoutineController: ShadowRoutineController by lazy {
        ShadowRoutineController(
            store = shadowRoutineStore,
            scope = applicationScope
        )
    }

    override fun enqueueSync() {
        SyncWorker.enqueue(application)
    }
}

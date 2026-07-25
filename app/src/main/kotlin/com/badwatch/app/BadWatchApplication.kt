package com.badwatch.app

import android.app.Application
import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.SessionStore
import com.badwatch.app.data.SettingsStore
import com.badwatch.app.domain.CaptureController
import com.badwatch.app.domain.SessionController
import com.badwatch.app.sensors.FusedSensorCollector
import com.badwatch.app.sensors.SensorStream
import com.badwatch.app.sync.DashboardClient
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
    val sensorStream: SensorStream
    val sessionStore: SessionStore
    val captureStore: CaptureStore
    val settingsStore: SettingsStore
    val sessionController: SessionController
    val captureController: CaptureController
    val dashboardClient: DashboardClient
}

private class DefaultAppContainer(
    private val application: Application
) : AppContainer {

    /**
     * Application-scoped, because a recording session must survive the Activity. Sessions
     * end on user action or process death, never on a screen timeout.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val sensorStream: SensorStream by lazy { FusedSensorCollector(application) }

    override val sessionStore: SessionStore by lazy {
        SessionStore(File(application.filesDir, "sessions"))
    }

    override val captureStore: CaptureStore by lazy {
        CaptureStore(File(application.filesDir, "captures"))
    }

    override val settingsStore: SettingsStore by lazy { SettingsStore(application) }

    override val dashboardClient: DashboardClient by lazy { DashboardClient() }

    override val sessionController: SessionController by lazy {
        SessionController(
            sensorStream = sensorStream,
            sessionStore = sessionStore,
            settingsStore = settingsStore,
            appVersion = BuildConfig.VERSION_NAME,
            scope = applicationScope
        )
    }

    override val captureController: CaptureController by lazy {
        CaptureController(
            sensorStream = sensorStream,
            captureStore = captureStore,
            settingsStore = settingsStore,
            appVersion = BuildConfig.VERSION_NAME,
            scope = applicationScope
        )
    }
}

package com.badwatch.app

import android.app.Application
import com.badwatch.app.data.SessionRepository
import com.badwatch.app.data.TrainingSessionRepository
import com.badwatch.app.data.TrainingSessionStore
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.app.sensors.SensorCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BadWatchApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

interface AppContainer {
    val sessionRepository: TrainingSessionRepository
    val sensorStreamProvider: SensorStreamProvider
}

private class DefaultAppContainer(
    private val application: Application
) : AppContainer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = TrainingSessionStore.create(application, scope)

    override val sessionRepository: TrainingSessionRepository = SessionRepository(dataStore)
    override val sensorStreamProvider: SensorStreamProvider = SensorCollector(application)
}

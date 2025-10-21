package com.badwatch.app

import android.app.Application
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.app.sensors.SensorCollector

class BadWatchApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

interface AppContainer {
    val sensorStreamProvider: SensorStreamProvider
}

private class DefaultAppContainer(
    private val application: Application
) : AppContainer {

    override val sensorStreamProvider: SensorStreamProvider = SensorCollector(application)
}

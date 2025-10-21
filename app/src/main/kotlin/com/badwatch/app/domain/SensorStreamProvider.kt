package com.badwatch.app.domain

import com.badwatch.app.model.GyroReading
import kotlinx.coroutines.flow.Flow

interface SensorStreamProvider {
    fun sensorStream(): Flow<GyroReading>
}

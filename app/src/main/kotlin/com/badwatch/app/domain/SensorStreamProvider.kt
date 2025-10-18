package com.badwatch.app.domain

import com.badwatch.core.model.SensorSample
import kotlinx.coroutines.flow.Flow

interface SensorStreamProvider {
    fun sensorStream(): Flow<SensorSample>
}

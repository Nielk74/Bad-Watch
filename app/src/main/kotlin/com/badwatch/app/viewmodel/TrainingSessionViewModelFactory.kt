package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.badwatch.app.data.TrainingSessionRepository
import com.badwatch.app.domain.SensorStreamProvider
import com.badwatch.core.classifier.ShotClassifier
import com.badwatch.core.pipeline.ShotDetectionPipeline
import com.badwatch.core.session.TrainingSessionAggregator

class TrainingSessionViewModelFactory(
    private val repository: TrainingSessionRepository,
    private val sensorStreamProvider: SensorStreamProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val aggregatorFactory: () -> TrainingSessionAggregator = { TrainingSessionAggregator() },
    private val pipelineFactory: () -> ShotDetectionPipeline = { ShotDetectionPipeline(ShotClassifier()) }
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrainingSessionViewModel::class.java)) {
            return TrainingSessionViewModel(
                repository = repository,
                sensorStreamProvider = sensorStreamProvider,
                clock = clock,
                aggregatorFactory = aggregatorFactory,
                pipelineFactory = pipelineFactory
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.simpleName}")
    }
}

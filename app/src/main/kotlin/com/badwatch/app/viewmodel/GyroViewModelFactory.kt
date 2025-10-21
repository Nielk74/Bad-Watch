package com.badwatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.badwatch.app.domain.SensorStreamProvider

class GyroViewModelFactory(
    private val sensorStreamProvider: SensorStreamProvider
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GyroViewModel::class.java)) {
            return GyroViewModel(sensorStreamProvider) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.simpleName}")
    }
}

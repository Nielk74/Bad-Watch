package com.badwatch.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.badwatch.app.ui.GyroRoute
import com.badwatch.app.viewmodel.GyroViewModel
import com.badwatch.app.viewmodel.GyroViewModelFactory

class MainActivity : ComponentActivity() {

    private val applicationContainer: AppContainer
        get() = (application as BadWatchApplication).container

    private val gyroViewModel: GyroViewModel by viewModels {
        GyroViewModelFactory(
            sensorStreamProvider = applicationContainer.sensorStreamProvider
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GyroRoute(
                viewModel = gyroViewModel,
                onStart = { gyroViewModel.start() },
                onStop = { gyroViewModel.stop() }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        gyroViewModel.start()
    }
}

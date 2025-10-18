package com.badwatch.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.badwatch.app.ui.TrainingRoute
import com.badwatch.app.viewmodel.TrainingSessionViewModel
import com.badwatch.app.viewmodel.TrainingSessionViewModelFactory

class MainActivity : ComponentActivity() {

    private val applicationContainer: AppContainer
        get() = (application as BadWatchApplication).container

    private val trainingViewModel: TrainingSessionViewModel by viewModels {
        TrainingSessionViewModelFactory(
            repository = applicationContainer.sessionRepository,
            sensorStreamProvider = applicationContainer.sensorStreamProvider
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            trainingViewModel.startSession()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrainingRoute(
                viewModel = trainingViewModel,
                onStartSession = { requestSensorPermissionAndStart() },
                onStopSession = { save -> trainingViewModel.stopSession(save) },
                onAbortSession = { trainingViewModel.abortSession() },
                onClearHistory = { trainingViewModel.clearHistory() }
            )
        }
    }

    private fun requestSensorPermissionAndStart() {
        if (hasBodySensorPermission()) {
            trainingViewModel.startSession()
        } else {
            permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }

    private fun hasBodySensorPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
}

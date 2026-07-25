package com.badwatch.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.badwatch.app.service.SessionService
import com.badwatch.app.sync.SyncWorker
import com.badwatch.app.ui.BadWatchApp
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.ShotType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as BadWatchApplication).container

    private val viewModel: BadWatchViewModel by viewModels {
        BadWatchViewModel.Factory(container)
    }

    /**
     * Ambient (always-on) state, pushed into Compose as a flow. The HUD renders a dim,
     * static face while ambient; the foreground service keeps recording regardless.
     */
    private val isAmbient = MutableStateFlow(false)

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
            }

            override fun onExitAmbient() {
                isAmbient.value = false
            }
        }
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Every requested permission is optional; recording works without them. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)

        setContent {
            BadWatchApp(
                viewModel = viewModel,
                onStartSession = ::startSession,
                onStopSession = ::stopSession,
                onStartCapture = ::startCapture,
                onFinishCapture = ::finishCapture,
                isAmbient = isAmbient
            )
        }

        maybeStartSessionFromTile(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        maybeStartSessionFromTile(intent)
    }

    /**
     * The tile's Start chip launches this activity with [EXTRA_START_SESSION]. The extra is
     * consumed so a recreate with the same intent does not start a second session.
     */
    private fun maybeStartSessionFromTile(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_START_SESSION, false)) {
            intent.removeExtra(EXTRA_START_SESSION)
            startSession()
        }
    }

    override fun onStart() {
        super.onStart()
        // Catch up on anything that could not reach the dashboard earlier.
        SyncWorker.enqueue(this)
    }

    /**
     * Starts recording via the foreground service rather than from the Activity, so the
     * session keeps running once the screen sleeps — which happens within seconds of the
     * player putting their wrist down.
     */
    private fun startSession() {
        requestSessionPermissions()
        SessionService.start(this)
    }

    private fun stopSession() {
        lifecycleScope.launch {
            container.sessionController.stopAndSave()
            SessionService.stop(this@MainActivity)
            SyncWorker.enqueue(this@MainActivity)
        }
    }

    /**
     * Drills run under the same foreground service as sessions. Without it, backgrounding
     * the app mid-drill lets the process be killed and silently discards every collected
     * swing — which is exactly what happened the first time this was tested on a device.
     */
    private fun startCapture(label: ShotType) {
        requestSessionPermissions()
        SessionService.startCapture(this, label)
    }

    private fun finishCapture() {
        SessionService.stopCapture(this)
    }

    private fun requestSessionPermissions() {
        val permissions = buildList {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    companion object {
        /** Intent extra set by the watch-face tile's Start chip to auto-start a session. */
        const val EXTRA_START_SESSION = "autostart_session"
    }
}

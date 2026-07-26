package com.badwatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.badwatch.app.service.SessionService
import com.badwatch.app.sync.SyncWorker
import com.badwatch.app.ui.BadWatchApp
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.ShotType
import kotlinx.coroutines.flow.MutableStateFlow

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
    private val ambientTimeMillis = MutableStateFlow(System.currentTimeMillis())

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambientTimeMillis.value = System.currentTimeMillis()
                isAmbient.value = true
            }

            override fun onUpdateAmbient() {
                // Wear invokes this at its ambient refresh cadence (normally once a minute).
                // It is the only clock tick the dim HUD needs; live telemetry cannot drive it.
                ambientTimeMillis.value = System.currentTimeMillis()
            }

            override fun onExitAmbient() {
                isAmbient.value = false
            }
        }
    )

    private var afterPermissionRequest: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Every requested permission is optional. Continue motion-only when heart rate or
        // notifications were denied; Health Services performs its own defensive check too.
        afterPermissionRequest?.also { continuation ->
            afterPermissionRequest = null
            continuation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)

        setContent {
            BadWatchApp(
                viewModel = viewModel,
                onStartSession = ::startSession,
                onStopSession = ::stopSession,
                onDiscardSession = ::discardSession,
                onStartCapture = ::startCapture,
                onFinishCapture = ::finishCapture,
                onCancelCapture = ::cancelCapture,
                isAmbient = isAmbient,
                ambientTimeMillis = ambientTimeMillis
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
        withSessionPermissions { SessionService.start(this) }
    }

    private fun stopSession() {
        SessionService.stop(this)
    }

    private fun discardSession() {
        SessionService.discard(this)
    }

    /**
     * Drills run under the same foreground service as sessions. Without it, backgrounding
     * the app mid-drill lets the process be killed and silently discards every collected
     * swing — which is exactly what happened the first time this was tested on a device.
     */
    private fun startCapture(label: ShotType) {
        withSessionPermissions { SessionService.startCapture(this, label) }
    }

    private fun finishCapture() {
        SessionService.stopCapture(this)
    }

    private fun cancelCapture() {
        SessionService.cancelCapture(this)
    }

    private fun withSessionPermissions(continuation: () -> Unit) {
        val missingPermissions = buildList {
            add(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    HealthPermissions.READ_HEART_RATE
                }
                else Manifest.permission.BODY_SENSORS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            continuation()
        } else {
            afterPermissionRequest = continuation
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    companion object {
        /** Intent extra set by the watch-face tile's Start chip to auto-start a session. */
        const val EXTRA_START_SESSION = "autostart_session"
    }
}

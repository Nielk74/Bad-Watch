package com.badwatch.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.os.Build
import android.os.Bundle
import android.util.Log
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
                Log.i(AMBIENT_LOG_TAG, "entered")
            }

            override fun onUpdateAmbient() {
                // Wear invokes this at its ambient refresh cadence (normally once a minute).
                // It is the only clock tick the dim HUD needs; live telemetry cannot drive it.
                ambientTimeMillis.value = System.currentTimeMillis()
                Log.d(AMBIENT_LOG_TAG, "updated")
            }

            override fun onExitAmbient() {
                isAmbient.value = false
                Log.i(AMBIENT_LOG_TAG, "exited")
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
     * consumed before starting so permission UI or a later recreation cannot start a second
     * session. `singleTop` in the manifest guarantees that an already-live activity receives
     * this payload through [onNewIntent] rather than Wear only bringing its task forward.
     */
    private fun maybeStartSessionFromTile(intent: Intent) {
        consumeTileStartSessionRequest(
            hasRequest = intent.getBooleanExtra(EXTRA_START_SESSION, false),
            consumeRequest = { intent.removeExtra(EXTRA_START_SESSION) },
            startSession = ::startSession
        )
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
        /** Stable diagnostic tag used by the physical-device always-on evidence probe. */
        const val AMBIENT_LOG_TAG = "BadWatchAmbient"

        /** Intent extra set by the watch-face tile's Start chip to auto-start a session. */
        const val EXTRA_START_SESSION = "autostart_session"
    }
}

/**
 * Consumes the Tile's one-shot command before crossing into permission and service code.
 * Keeping this tiny transition Android-free makes the concrete duplicate-start regression
 * deterministic in a local unit test; manifest coverage separately locks in intent delivery.
 */
internal fun consumeTileStartSessionRequest(
    hasRequest: Boolean,
    consumeRequest: () -> Unit,
    startSession: () -> Unit
): Boolean {
    if (!hasRequest) return false
    consumeRequest()
    startSession()
    return true
}

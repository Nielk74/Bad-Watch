package com.badwatch.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.badwatch.app.BadWatchApplication
import com.badwatch.app.MainActivity
import com.badwatch.app.R
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.domain.SessionState
import com.badwatch.core.model.ShotType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit

/**
 * Keeps a session recording while the watch screen is off.
 *
 * This is the single most important correctness fix in the app. Previously capture was
 * started from `MainActivity.onStart()` and bound to the Activity lifecycle, so tracking
 * stopped a few seconds after the wrist dropped — which is to say, it stopped the moment
 * the player started playing.
 */
class SessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container get() = (application as BadWatchApplication).container
    private val controller get() = container.sessionController
    private val captureController get() = container.captureController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    controller.stopAndSave()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                serviceScope.launch {
                    captureController.finish()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START_CAPTURE -> {
                val label = intent.getStringExtra(EXTRA_LABEL)
                    ?.let { runCatching { ShotType.valueOf(it) }.getOrNull() }
                    ?: return START_NOT_STICKY

                // A data-collection drill needs the same process protection as a session.
                // Without it, backgrounding the app mid-drill kills the process and silently
                // discards every swing the player has just collected.
                startAsForeground(buildCaptureNotification(label, swings = 0))
                captureController.start(label)

                captureController.state
                    .onEach { state ->
                        if (state is CaptureState.Capturing) {
                            updateNotification(buildCaptureNotification(state.label, state.keptCount))
                        }
                    }
                    .launchIn(serviceScope)

                return START_STICKY
            }
        }

        startAsForeground(buildSessionNotification(shotCount = 0, durationMillis = 0L))
        controller.start()

        // Keep the ongoing notification current so a glance at the notification shade (or
        // the ongoing-activity chip) shows live progress without opening the app.
        controller.state
            .onEach { state ->
                if (state is SessionState.Recording) {
                    updateNotification(
                        buildSessionNotification(
                            shotCount = state.snapshot.totalShots,
                            durationMillis = state.snapshot.durationMillis
                        )
                    )
                }
            }
            .launchIn(serviceScope)

        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildCaptureNotification(label: ShotType, swings: Int): Notification =
        baseNotification(stopAction = ACTION_STOP_CAPTURE)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(
                getString(
                    R.string.capture_notification_body,
                    resources.getQuantityString(R.plurals.session_notification_shots, swings, swings),
                    label.name
                )
            )
            .build()

    private fun buildSessionNotification(shotCount: Int, durationMillis: Long): Notification {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis).toInt()
        val shotsText = resources.getQuantityString(
            R.plurals.session_notification_shots, shotCount, shotCount
        )
        val minutesText = resources.getQuantityString(
            R.plurals.session_notification_minutes, minutes, minutes
        )
        return baseNotification(stopAction = ACTION_STOP)
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(
                getString(R.string.session_notification_body, shotsText, minutesText)
            )
            .build()
    }

    private fun baseNotification(stopAction: String): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            stopAction.hashCode(),
            Intent(this, SessionService::class.java).setAction(stopAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.session_notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.session_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.session_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "bad_watch_session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.badwatch.app.action.STOP_SESSION"
        const val ACTION_START_CAPTURE = "com.badwatch.app.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.badwatch.app.action.STOP_CAPTURE"
        const val EXTRA_LABEL = "label"

        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun startCapture(context: Context, label: ShotType) {
            val intent = Intent(context, SessionService::class.java)
                .setAction(ACTION_START_CAPTURE)
                .putExtra(EXTRA_LABEL, label.name)
            context.startForegroundService(intent)
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_STOP_CAPTURE)
            context.startService(intent)
        }
    }
}

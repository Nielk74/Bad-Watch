package com.badwatch.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.badwatch.app.BadWatchApplication
import com.badwatch.app.MainActivity
import com.badwatch.app.R
import com.badwatch.app.domain.CaptureState
import com.badwatch.app.domain.SessionStartResult
import com.badwatch.app.domain.SessionState
import com.badwatch.app.health.ExerciseHeartRateState
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.sync.SyncWorker
import com.badwatch.core.model.ShotType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val heartRateSession get() = container.heartRateSession
    private var sessionNotificationJob: Job? = null
    private var captureNotificationJob: Job? = null
    private var captureStartJob: Job? = null
    private var sessionStartJob: Job? = null
    // OngoingActivity.apply() extends this exact builder. Reusing it is intentional: building
    // notification updates from a fresh builder would silently drop the watch-face metadata.
    private var sessionNotificationBuilder: NotificationCompat.Builder? = null
    private var sessionOngoingActivity: OngoingActivity? = null
    private var sessionStopwatchStartElapsedRealtime: Long? = null
    private val sessionCommandMutex = Mutex()
    private val captureCommandMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    sessionCommandMutex.withLock {
                        sessionNotificationJob?.cancel()
                        val saved = controller.stopAndSave()
                        heartRateSession.stop()
                        if (saved != null) SyncWorker.enqueue(this@SessionService)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_DISCARD -> {
                serviceScope.launch {
                    sessionCommandMutex.withLock {
                        sessionNotificationJob?.cancel()
                        controller.discard()
                        heartRateSession.stop()
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_STOP_CAPTURE -> {
                serviceScope.launch {
                    captureCommandMutex.withLock {
                        captureStartJob?.cancel()
                        captureNotificationJob?.cancel()
                        val saved = captureController.finish()
                        if (saved != null) SyncWorker.enqueue(this@SessionService)
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_CANCEL_CAPTURE -> {
                serviceScope.launch {
                    captureCommandMutex.withLock {
                        captureStartJob?.cancel()
                        captureNotificationJob?.cancel()
                        captureController.cancel()
                        stopSelf()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_START_CAPTURE -> {
                val label = intent.getStringExtra(EXTRA_LABEL)
                    ?.let { runCatching { ShotType.valueOf(it) }.getOrNull() }
                    ?: return START_NOT_STICKY

                // One physical sensor stream owns the foreground service at a time. A Tile or
                // repeated deep link must not replace a live session notification or collector.
                if (controller.isRecording || sessionStartJob?.isActive == true ||
                    captureController.isCapturing || captureStartJob?.isActive == true
                ) {
                    return START_NOT_STICKY
                }

                // A data-collection drill needs the same process protection as a session.
                // Without it, backgrounding the app mid-drill kills the process and silently
                // discards every swing the player has just collected.
                startAsForeground(buildCaptureNotification(label, swings = 0))
                captureStartJob = serviceScope.launch {
                    captureCommandMutex.withLock {
                        if (controller.isRecording || sessionStartJob?.isActive == true) {
                            stopSelf()
                            return@withLock
                        }
                        if (!captureController.start(label)) {
                            if (!captureController.isCapturing) stopSelf()
                            return@withLock
                        }

                        captureNotificationJob?.cancel()
                        captureNotificationJob = captureController.state
                            .distinctUntilChangedBy { state ->
                                (state as? CaptureState.Capturing)?.keptCount
                            }
                            .onEach { state ->
                                if (state is CaptureState.Capturing) {
                                    updateNotification(
                                        buildCaptureNotification(state.label, state.keptCount)
                                    )
                                }
                            }
                            .launchIn(serviceScope)
                    }
                }

                // Capture has no recovery journal. Asking Android to recreate this service
                // with a null intent would turn an interrupted drill into a normal session.
                return START_NOT_STICKY
            }
        }

        // A normal-session intent must not take over the collector/notification of a labelled
        // capture already in progress.
        if (captureController.isCapturing || captureStartJob?.isActive == true) {
            return START_NOT_STICKY
        }

        startAsForeground(buildSessionNotification(shotCount = 0, durationMillis = 0L))

        // Keep the ongoing notification current so a glance at the notification shade (or
        // the ongoing-activity chip) shows live progress without opening the app.
        sessionNotificationJob?.cancel()
        sessionNotificationJob = controller.state
            // Sensor state arrives at 100 Hz. A notification is a one-second glance, not a
            // telemetry stream; updating it per sample overwhelmed NotificationManager and
            // wasted battery on every live session.
            .sample(NOTIFICATION_UPDATE_INTERVAL_MILLIS)
            .onEach { state ->
                when (state) {
                    is SessionState.Recording -> {
                        updateNotification(
                            buildSessionNotification(
                                shotCount = state.snapshot.totalShots,
                                durationMillis = state.snapshot.durationMillis
                            )
                        )
                    }
                    is SessionState.Failed -> {
                        // A motion-sensor failure ends the user-facing recording. Do not
                        // leave its optional Health Services exercise running in the dark.
                        sessionCommandMutex.withLock {
                            heartRateSession.stop()
                            stopSelf()
                        }
                    }
                    else -> Unit
                }
            }
            .launchIn(serviceScope)

        // START_STICKY can re-enter here with a null Intent after Android recreates the
        // process. The controller resolves its journal before Health Services starts, so a
        // crash-after-save is reconciled without accidentally opening a second exercise.
        if (!controller.isRecording && sessionStartJob?.isActive != true) {
            sessionStartJob = serviceScope.launch {
                sessionCommandMutex.withLock {
                    when (val result = controller.start()) {
                        is SessionStartResult.Started -> {
                            val heartRateState = heartRateSession.start()
                            if (heartRateState !is ExerciseHeartRateState.Active) {
                                Log.i(TAG, "Heart rate unavailable for this session: $heartRateState")
                            }
                        }
                        SessionStartResult.AlreadyRunning -> Unit
                        is SessionStartResult.AlreadySaved -> {
                            // The process may have died between the atomic save and enqueue.
                            SyncWorker.enqueue(this@SessionService)
                            stopSelf()
                        }
                        is SessionStartResult.Failed -> stopSelf()
                    }
                }
            }
        }

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
        baseNotification(
            channelId = CAPTURE_CHANNEL_ID,
            stopAction = ACTION_STOP_CAPTURE,
            stopLabel = getString(R.string.capture_save_drill),
            category = NotificationCompat.CATEGORY_SERVICE
        )
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(
                getString(
                    R.string.capture_notification_body,
                    resources.getQuantityString(R.plurals.capture_notification_swings, swings, swings),
                    getString(label.displayNameResource)
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    @Synchronized
    private fun buildSessionNotification(shotCount: Int, durationMillis: Long): Notification {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis).toInt()
        val hitsText = resources.getQuantityString(
            R.plurals.session_notification_hits, shotCount, shotCount
        )
        val minutesText = resources.getQuantityString(
            R.plurals.session_notification_minutes, minutes, minutes
        )
        val builder = sessionNotificationBuilder ?: baseNotification(
            channelId = SESSION_CHANNEL_ID,
            stopAction = ACTION_STOP,
            stopLabel = getString(R.string.session_notification_stop),
            category = NotificationCompat.CATEGORY_WORKOUT
        ).also { newBuilder ->
            sessionNotificationBuilder = newBuilder
        }
        builder
            .setContentTitle(getString(R.string.session_notification_title))
            .setContentText(getString(R.string.session_notification_body, hitsText, minutesText))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val candidateStart = stopwatchStartElapsedRealtime(
            nowElapsedRealtime = SystemClock.elapsedRealtime(),
            durationMillis = durationMillis
        )
        val previousStart = sessionStopwatchStartElapsedRealtime
        val shouldRebase = previousStart != null && needsStopwatchRebase(
            currentStartElapsedRealtime = previousStart,
            candidateStartElapsedRealtime = candidateStart,
            toleranceMillis = STOPWATCH_REBASE_TOLERANCE_MILLIS
        )
        val stopwatchStart = when {
            previousStart == null || shouldRebase -> candidateStart
            else -> previousStart
        }
        val status = sessionOngoingStatus(stopwatchStart)

        val existingOngoingActivity = sessionOngoingActivity
        if (existingOngoingActivity == null) {
            sessionStopwatchStartElapsedRealtime = stopwatchStart
            sessionOngoingActivity = OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_ongoing_badminton)
                .setTouchIntent(openAppPendingIntent())
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .setTitle(getString(R.string.session_notification_title))
                .setContentDescription(getString(R.string.session_ongoing_content_description))
                .setStatus(status)
                .build()
                .also { ongoingActivity -> ongoingActivity.apply(this) }
        } else if (
            shouldRebase && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                )
        ) {
            // A recovered recorder can be older than this Service instance. Rebase once to
            // the persisted duration; update() retains all icon, intent, category and a11y
            // metadata already attached to the existing foreground notification.
            try {
                existingOngoingActivity.update(this, status)
                sessionStopwatchStartElapsedRealtime = stopwatchStart
            } catch (denied: SecurityException) {
                // Permission can be revoked between the explicit check and notify(). The
                // foreground service remains valid. Keep the old origin so a later permitted
                // update still detects the recovery drift and retries the rebase.
                Log.i(TAG, "Ongoing activity update unavailable", denied)
            }
        }

        return builder.build()
    }

    private fun sessionOngoingStatus(stopwatchStartElapsedRealtime: Long): Status =
        Status.Builder()
            .addTemplate(getString(R.string.session_ongoing_status_template))
            .addPart(
                ONGOING_STATUS_ACTIVITY_PART,
                Status.TextPart(getString(R.string.session_ongoing_status_activity))
            )
            .addPart(
                ONGOING_STATUS_TIME_PART,
                Status.StopwatchPart(stopwatchStartElapsedRealtime)
            )
            .build()

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun baseNotification(
        channelId: String,
        stopAction: String,
        stopLabel: String,
        category: String
    ): NotificationCompat.Builder {
        val stopIntent = PendingIntent.getService(
            this,
            stopAction.hashCode(),
            Intent(this, SessionService::class.java).setAction(stopAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_ongoing_badminton)
            .setContentIntent(openAppPendingIntent())
            .addAction(0, stopLabel, stopIntent)
            .setOngoing(true)
            .setLocalOnly(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(category)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            SESSION_CHANNEL_ID,
            getString(R.string.session_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.session_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val captureChannel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            getString(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.capture_notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(captureChannel)
    }

    companion object {
        private const val SESSION_CHANNEL_ID = "bad_watch_session"
        private const val CAPTURE_CHANNEL_ID = "bad_watch_capture"
        private const val TAG = "BadWatchHealth"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_UPDATE_INTERVAL_MILLIS = 1_000L
        private const val STOPWATCH_REBASE_TOLERANCE_MILLIS = 1_500L
        private const val ONGOING_STATUS_ACTIVITY_PART = "activity"
        private const val ONGOING_STATUS_TIME_PART = "time"
        const val ACTION_STOP = "com.badwatch.app.action.STOP_SESSION"
        const val ACTION_DISCARD = "com.badwatch.app.action.DISCARD_SESSION"
        const val ACTION_START_CAPTURE = "com.badwatch.app.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.badwatch.app.action.STOP_CAPTURE"
        const val ACTION_CANCEL_CAPTURE = "com.badwatch.app.action.CANCEL_CAPTURE"
        const val EXTRA_LABEL = "label"

        fun start(context: Context) {
            val intent = Intent(context, SessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun discard(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_DISCARD)
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

        fun cancelCapture(context: Context) {
            val intent = Intent(context, SessionService::class.java).setAction(ACTION_CANCEL_CAPTURE)
            context.startService(intent)
        }
    }
}

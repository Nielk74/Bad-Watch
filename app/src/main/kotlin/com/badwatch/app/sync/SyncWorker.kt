package com.badwatch.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.badwatch.app.BadWatchApplication
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Pushes unsynced sessions to the dashboard.
 *
 * Sync is strictly best-effort and never blocks the player: sessions are durable on the
 * watch the moment they end, and this worker catches up whenever the watch has network.
 * A watch that is never configured with a dashboard URL remains fully functional.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as BadWatchApplication).container
        val baseUrl = container.settingsStore.dashboardUrl.first()
            ?: return Result.success() // No dashboard configured; nothing to do.
        val token = container.settingsStore.dashboardToken.first()

        // Captures upload separately and are strictly secondary: a failure here must not
        // hold up session sync, which is the thing the player actually sees.
        val pendingCaptures = container.captureStore.unsynced()
        if (pendingCaptures.isNotEmpty()) {
            container.dashboardClient.uploadCaptures(
                baseUrl = baseUrl,
                token = token,
                captures = pendingCaptures.map { it.export }
            ).fold(
                onSuccess = { container.captureStore.markSynced(it.accepted) },
                onFailure = { Log.w(TAG, "Capture upload failed; will retry", it) }
            )
        }

        val pending = container.sessionStore.unsynced()
        if (pending.isEmpty()) return Result.success()

        val outcome = container.dashboardClient.upload(
            baseUrl = baseUrl,
            token = token,
            sessions = pending.map { it.export }
        )

        return outcome.fold(
            onSuccess = { response ->
                container.sessionStore.markSynced(response.accepted)
                // A session the server explicitly rejected will never succeed on retry, so
                // only an empty acknowledgement (partial/failed delivery) warrants one.
                if (response.accepted.isEmpty() && response.rejected.isEmpty()) {
                    Result.retry()
                } else {
                    Result.success()
                }
            },
            onFailure = { cause ->
                // Without this the only symptom of a misconfigured dashboard is a silent
                // RETRY in the WorkManager log, which is close to undebuggable.
                Log.w(TAG, "Sync to $baseUrl failed (attempt $runAttemptCount)", cause)
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        )
    }

    companion object {
        private const val TAG = "BadWatchSync"
        private const val UNIQUE_WORK_NAME = "bad_watch_session_sync"
        private const val MAX_ATTEMPTS = 5

        /** Queues a sync. Safe to call often — duplicate requests collapse into one. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

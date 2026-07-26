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
import com.badwatch.app.data.CaptureStore
import com.badwatch.app.data.SessionStore
import com.badwatch.core.sync.CaptureExport
import com.badwatch.core.sync.SessionExport
import com.badwatch.core.sync.SyncResponse
import com.badwatch.core.sync.isEligibleForModelTrainingUpload
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

        val outcome = syncPendingRecords(
            captureStore = container.captureStore,
            sessionStore = container.sessionStore,
            uploadCaptures = { captures ->
                container.dashboardClient.uploadCaptures(baseUrl, token, captures)
            },
            uploadSessions = { sessions ->
                container.dashboardClient.upload(baseUrl, token, sessions)
            },
            onCaptureFailure = { cause ->
                // Captures are secondary: their transport/storage failure never blocks the
                // player's session diary from syncing in the same pass.
                Log.w(TAG, "Capture upload failed; remains pending", cause)
            }
        )

        return when (outcome) {
            PendingSyncOutcome.Complete -> Result.success()
            PendingSyncOutcome.EmptyAcknowledgement -> Result.retry()
            is PendingSyncOutcome.Failed -> {
                // Without this the only symptom of a misconfigured dashboard is a silent
                // RETRY in the WorkManager log, which is close to undebuggable.
                Log.w(TAG, "Sync to $baseUrl failed (attempt $runAttemptCount)", outcome.cause)
                if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
        }
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

internal sealed interface PendingSyncOutcome {
    data object Complete : PendingSyncOutcome
    data object EmptyAcknowledgement : PendingSyncOutcome
    data class Failed(val cause: Throwable) : PendingSyncOutcome
}

/**
 * Platform-free sync pass used by [SyncWorker] and its JVM regressions.
 *
 * Explicit rejections are persisted against the uploaded payload snapshot. They therefore
 * leave future [SessionStore.unsynced] and [CaptureStore.unsynced] batches until that exact
 * payload changes, rather than being posted again on every WorkManager enqueue.
 */
internal suspend fun syncPendingRecords(
    captureStore: CaptureStore,
    sessionStore: SessionStore,
    uploadCaptures: suspend (List<CaptureExport>) -> Result<SyncResponse>,
    uploadSessions: suspend (List<SessionExport>) -> Result<SyncResponse>,
    onCaptureFailure: (Throwable) -> Unit = {}
): PendingSyncOutcome {
    // Consent is immutable metadata on each capture. Enabling sharing today must never
    // retroactively send raw motion recorded under the local-only default.
    val pendingCaptures = captureStore.unsynced()
        .filter { it.export.isEligibleForModelTrainingUpload }
    if (pendingCaptures.isNotEmpty()) {
        uploadCaptures(pendingCaptures.map { it.export }).fold(
            onSuccess = { response ->
                try {
                    captureStore.applySyncResponse(pendingCaptures, response)
                } catch (cause: Throwable) {
                    onCaptureFailure(cause)
                }
            },
            onFailure = onCaptureFailure
        )
    }

    val pendingSessions = sessionStore.unsynced()
    if (pendingSessions.isEmpty()) return PendingSyncOutcome.Complete

    return uploadSessions(pendingSessions.map { it.export }).fold(
        onSuccess = { response ->
            runCatching { sessionStore.applySyncResponse(pendingSessions, response) }.fold(
                onSuccess = {
                    // Only a response which acknowledges no ID at all warrants a retry.
                    if (response.accepted.isEmpty() && response.rejected.isEmpty()) {
                        PendingSyncOutcome.EmptyAcknowledgement
                    } else {
                        PendingSyncOutcome.Complete
                    }
                },
                onFailure = PendingSyncOutcome::Failed
            )
        },
        onFailure = PendingSyncOutcome::Failed
    )
}

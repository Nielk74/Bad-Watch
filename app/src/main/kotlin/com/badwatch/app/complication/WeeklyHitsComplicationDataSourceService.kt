package com.badwatch.app.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.badwatch.app.MainActivity
import com.badwatch.app.R
import com.badwatch.app.data.SessionStore
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Config-free complication backed by the same durable session files as history and sync.
 * It reports one literal fact — corrected detector events in the rolling last seven days.
 */
class WeeklyHitsComplicationDataSourceService : SuspendingComplicationDataSourceService() {

    private val sessionStore by lazy { SessionStore(File(filesDir, "sessions")) }

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData {
        val sessions = try {
            sessionStore.refresh().map { it.export }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return getPreviewData(request.complicationType)
                ?.let(::NoDataComplicationData)
                ?: NoDataComplicationData()
        }
        val snapshot = WeeklyHitsComplicationModel.summarize(
            sessions = sessions,
            nowMillis = System.currentTimeMillis()
        )
        return dataFor(request.complicationType, snapshot, openAppPendingIntent())
            ?: NoDataComplicationData()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = dataFor(
        type = type,
        snapshot = WeeklyHitsSnapshot(detectedHits = 184, sessionCount = 3),
        tapAction = null
    )

    private fun dataFor(
        type: ComplicationType,
        snapshot: WeeklyHitsSnapshot,
        tapAction: PendingIntent?
    ): ComplicationData? {
        val description = text(localizedDescription(snapshot))
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = text(
                    if (snapshot.hasSessions) {
                        WeeklyHitsComplicationModel.shortValue(snapshot)
                    } else {
                        getString(R.string.complication_no_play)
                    }
                ),
                contentDescription = description
            )
                .setTitle(text(getString(R.string.complication_short_title)))
                .apply { if (tapAction != null) setTapAction(tapAction) }
                .build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = text(localizedLongText(snapshot)),
                contentDescription = description
            )
                .setTitle(text(getString(R.string.complication_long_title)))
                .apply { if (tapAction != null) setTapAction(tapAction) }
                .build()

            else -> null
        }
    }

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    private fun localizedLongText(snapshot: WeeklyHitsSnapshot): String {
        if (!snapshot.hasSessions) return getString(R.string.complication_no_sessions)
        return getString(
            R.string.complication_long_text,
            resources.getQuantityString(
                R.plurals.common_detected_hits_count,
                snapshot.detectedHits,
                snapshot.detectedHits
            ),
            resources.getQuantityString(
                R.plurals.common_sessions_count,
                snapshot.sessionCount,
                snapshot.sessionCount
            )
        )
    }

    private fun localizedDescription(snapshot: WeeklyHitsSnapshot): String {
        if (!snapshot.hasSessions) return getString(R.string.complication_empty_description)
        return getString(
            R.string.complication_description,
            resources.getQuantityString(
                R.plurals.complication_corrected_hits,
                snapshot.detectedHits,
                snapshot.detectedHits
            ),
            resources.getQuantityString(
                R.plurals.common_sessions_count,
                snapshot.sessionCount,
                snapshot.sessionCount
            )
        )
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val OPEN_APP_REQUEST_CODE = 7_001
    }
}

/** Requests fresh data for every watch-face slot using this provider. */
object WeeklyHitsComplicationUpdates {
    fun requestAll(context: Context) {
        ComplicationDataSourceUpdateRequester.create(
            context.applicationContext,
            ComponentName(context, WeeklyHitsComplicationDataSourceService::class.java)
        ).requestUpdateAll()
    }
}

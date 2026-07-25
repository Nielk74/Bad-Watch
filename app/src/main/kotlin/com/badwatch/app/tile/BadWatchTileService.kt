package com.badwatch.app.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.badwatch.app.MainActivity
import com.badwatch.app.data.SessionStore
import com.badwatch.app.data.StoredSession
import com.badwatch.app.ui.components.formatDuration
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watch-face tile: the last session's headline numbers, this week's load, and a Start chip
 * that deep-links into [MainActivity] with [MainActivity.EXTRA_START_SESSION].
 *
 * The tile reads [SessionStore] directly against the same `filesDir/sessions` directory that
 * `DefaultAppContainer` uses, rather than going through `BadWatchApplication.container`. The
 * system binds this service on its own schedule and the tile only ever lists sessions, so
 * pulling in the whole container (sensors, controllers, sync) would be dead weight; the store
 * itself is cheap — one small JSON file per session, parsed on the IO dispatcher. The
 * trade-off is a second, read-only store instance whose in-memory cache can lag the app's; the
 * files are the source of truth and are re-read on every request, so nothing goes stale that
 * the freshness interval would not already allow.
 *
 * Freshness: 30 minutes. Session stats only change when a session is saved, and the platform
 * generally re-requests the timeline when the tile is on screen or about to be, so a shorter
 * interval would mostly re-read unchanged files; a longer one risks showing a stale "this
 * week" count after a session ends while the tile sits in the carousel.
 */
class BadWatchTileService : TileService() {

    /**
     * Tiles 1.3.0's [TileService] is a plain `Service` — not a `LifecycleOwner` — so there is
     * no `lifecycleScope` to borrow. The request callbacks arrive on the main thread and must
     * return a future promptly; the store's file parsing suspends onto IO.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Same directory as `DefaultAppContainer.sessionStore`. */
    private val sessionStore by lazy { SessionStore(File(filesDir, "sessions")) }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            serviceScope.launch {
                try {
                    completer.set(buildTile(requestParams.deviceConfiguration))
                } catch (cancelled: CancellationException) {
                    completer.setCancelled()
                } catch (error: Throwable) {
                    completer.setException(error)
                }
            }
            "BadWatchTileService.onTileRequest"
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            // The tile is text and tinted chips only — no images — so the bundle is empty and
            // only the version handshake matters.
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "BadWatchTileService.onTileResourcesRequest"
        }

    private suspend fun buildTile(deviceParameters: DeviceParameters): TileBuilders.Tile {
        val sessions = sessionStore.refresh()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MILLIS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(layout(deviceParameters, sessions))
            )
            .build()
    }

    private fun layout(
        deviceParameters: DeviceParameters,
        sessions: List<StoredSession>
    ): LayoutElementBuilders.LayoutElement {
        // refresh() sorts newest first, matching what HomeScreen treats as "last session".
        val last = sessions.firstOrNull()
        val weekStart = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val week = sessions.filter { it.export.session.startedAtMillis >= weekStart }

        val content: LayoutElementBuilders.LayoutElement = if (last == null) {
            bodyText("No sessions yet — start one on the watch", color = COLOR_TEXT_DIM)
        } else {
            val summary = last.export.session.summary
            LayoutElementBuilders.Column.Builder()
                .addContent(
                    bodyText(
                        "Last: ${plural(summary.totalShots, "hit", "hits")} · " +
                            plural(last.export.rallyProfile.rallyCount, "burst", "bursts") +
                            " · ${formatDuration(summary.durationMillis)}"
                    )
                )
                .addContent(
                    bodyText(
                        "This week: ${plural(week.size, "session", "sessions")} · " +
                            plural(week.sumOf { it.export.session.summary.totalShots }, "hit", "hits"),
                        color = COLOR_TEXT_DIM
                    )
                )
                .build()
        }

        val primaryLayout = PrimaryLayout.Builder(deviceParameters)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "BAD WATCH")
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(ColorBuilders.argb(COLOR_MINT))
                    .build()
            )
            .setContent(content)
            .setPrimaryChipContent(
                CompactChip.Builder(this, "Start", startSessionClickable(), deviceParameters)
                    .setChipColors(ChipColors(COLOR_MINT, COLOR_ON_MINT))
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(COLOR_BACKGROUND))
                            .build()
                    )
                    .build()
            )
            .addContent(primaryLayout)
            .build()
    }

    private fun bodyText(text: String, color: Int = COLOR_TEXT): Text =
        Text.Builder(this, text)
            .setTypography(Typography.TYPOGRAPHY_BODY2)
            .setColor(ColorBuilders.argb(color))
            .setMaxLines(2)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
            .build()

    private fun startSessionClickable(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(CLICKABLE_ID_START_SESSION)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_START_SESSION,
                                ActionBuilders.AndroidBooleanExtra.Builder()
                                    .setValue(true)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    private fun plural(count: Int, singular: String, plural: String): String =
        "$count ${if (count == 1) singular else plural}"

    companion object {
        /**
         * Ties a tile to its resource bundle. Bump if the layout ever references resources so
         * the renderer re-fetches them; the version string itself is otherwise arbitrary.
         */
        private const val RESOURCES_VERSION = "1"

        private val FRESHNESS_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(30)

        private const val CLICKABLE_ID_START_SESSION = "start_session"

        // The palette from ui/theme/Color.kt as raw ARGB ints — protolayout takes @ColorInt,
        // not androidx.compose.ui.graphics.Color, so the Compose constants cannot be reused.
        private val COLOR_BACKGROUND = 0xFF05080B.toInt()
        private val COLOR_MINT = 0xFF3EF2BE.toInt()
        private val COLOR_ON_MINT = 0xFF00291F.toInt()
        private val COLOR_TEXT = 0xFFE2EAF2.toInt()
        private val COLOR_TEXT_DIM = 0xFFA7B4C2.toInt()
    }
}

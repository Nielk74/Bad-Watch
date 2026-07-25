package com.badwatch.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.badwatch.app.BadWatchApplication
import com.badwatch.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sets the dashboard URL and token from adb. **Debug builds only.**
 *
 * Typing a URL on a watch is miserable, so development and first-time setup go through:
 *
 * ```
 * adb shell am broadcast -a com.badwatch.app.SET_DASHBOARD \
 *   -n com.badwatch.badwatch/com.badwatch.app.debug.DashboardConfigReceiver \
 *   --es url "http://192.168.1.20:8080" --es token "<token>"
 * ```
 *
 * This receiver is declared only in `src/debug/AndroidManifest.xml`. It has to be exported
 * for `am broadcast` to reach it, and an exported receiver that rewrites the upload
 * destination is exactly the shape of an exfiltration hole — so it must never exist in a
 * release build. Release configuration will go through a proper settings flow.
 */
class DashboardConfigReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_DASHBOARD) return

        val url = intent.getStringExtra("url")
        val token = intent.getStringExtra("token")
        val container = (context.applicationContext as BadWatchApplication).container

        CoroutineScope(Dispatchers.Default).launch {
            container.settingsStore.setDashboard(url, token)
            Log.i(TAG, "Dashboard set to ${url ?: "<cleared>"} (token ${if (token.isNullOrBlank()) "unset" else "set"})")
            SyncWorker.enqueue(context.applicationContext)
        }
    }

    private companion object {
        const val TAG = "BadWatchDebug"
        const val ACTION_SET_DASHBOARD = "com.badwatch.app.SET_DASHBOARD"
    }
}

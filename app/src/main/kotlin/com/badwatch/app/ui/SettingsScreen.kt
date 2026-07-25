package com.badwatch.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.Handedness

/**
 * The little the player can change on the watch.
 *
 * Handedness is the one setting that changes the maths — the classifier mirrors its swing
 * features for left-handers, so it belongs on the wrist rather than the phone. The rest is
 * read-only context: the dashboard URL is pushed from the paired phone or adb because typing
 * on a watch is a punishment, and the wrist note answers "can I wear it on the other arm?"
 * before it becomes a bug report.
 */
@Composable
fun SettingsScreen(viewModel: BadWatchViewModel, onBack: () -> Unit) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val dashboardUrl by viewModel.dashboardUrl.collectAsStateWithLifecycle()

    WatchScreen {
        item { ScreenHeader("Settings") }

        item {
            SwitchButton(
                checked = profile.handedness == Handedness.Left,
                onCheckedChange = { left ->
                    viewModel.setHandedness(if (left) Handedness.Left else Handedness.Right)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Left handed") }
            )
        }

        item {
            InfoCard(title = "Dashboard") {
                Text(
                    text = dashboardUrl ?: "Not configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Set the server URL from the paired phone or with adb. " +
                        "Sessions stay on the watch until it is reachable.",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            InfoCard(title = "Wrist") {
                Text(
                    text = "Racket hand only. Bad Watch reads the swing, so the other wrist " +
                        "cannot produce shot data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CompactButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Back") }
            )
        }
    }
}

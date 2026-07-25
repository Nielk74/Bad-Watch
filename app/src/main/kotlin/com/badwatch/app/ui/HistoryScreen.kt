package com.badwatch.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.data.StoredSession
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.StatRow
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatDuration
import com.badwatch.app.ui.components.formatSessionDate
import com.badwatch.app.ui.theme.CourtColors
import com.badwatch.app.viewmodel.BadWatchViewModel

/**
 * The sessions stored on the watch, newest first.
 *
 * Delete is swipe-to-reveal rather than a button on the card: the whole row is a tap target
 * for opening the recap, so a destructive action has to hide behind a deliberate gesture —
 * a partial swipe exposes Delete, a full one fires it. Anything more reachable and a fumbled
 * scroll would eat a session there is no way to get back.
 */
@Composable
fun HistoryScreen(
    viewModel: BadWatchViewModel,
    onOpenSession: (StoredSession) -> Unit,
    onBack: () -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    WatchScreen {
        item { ScreenHeader("History") }

        if (history.isEmpty()) {
            item {
                InfoCard {
                    Text(
                        text = "Nothing recorded yet. Your last 30 sessions live here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(history, key = { it.export.session.id }) { stored ->
            val sessionId = stored.export.session.id
            SwipeToReveal(
                primaryAction = {
                    PrimaryActionButton(
                        onClick = { viewModel.deleteSession(sessionId) },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text("Delete") }
                    )
                },
                onSwipePrimaryAction = { viewModel.deleteSession(sessionId) }
            ) {
                TitleCard(
                    onClick = { onOpenSession(stored) },
                    title = { Text(formatSessionDate(stored.export.session.startedAtMillis)) },
                    time = {
                        Text(
                            text = if (stored.synced) "Synced" else "On watch",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stored.synced) {
                                CourtColors.Success
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                ) {
                    StatRow(
                        Stat("Shots", stored.export.session.summary.totalShots.toString()),
                        Stat("Rallies", stored.export.rallyProfile.rallyCount.toString()),
                        Stat("Time", formatDuration(stored.export.session.summary.durationMillis))
                    )
                }
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

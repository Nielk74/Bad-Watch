package com.badwatch.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToReveal
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.data.StoredSession
import com.badwatch.app.R
import com.badwatch.app.localization.classifySyncRejection
import com.badwatch.app.ui.components.DurationStatRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.Stat
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.ui.components.formatSessionDate
import com.badwatch.app.ui.theme.CourtColors
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.sync.reviewedAnalysis

/**
 * The sessions stored on the watch, newest first.
 *
 * Delete is swipe-to-reveal rather than a button on the card: the whole row is a tap target
 * for opening the recap, so a destructive action hides behind a deliberate gesture. Both a
 * reveal tap and a full swipe ask for confirmation before touching durable history.
 */
@Composable
fun HistoryScreen(
    viewModel: BadWatchViewModel,
    onOpenSession: (StoredSession) -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<StoredSession?>(null) }

    WatchScreen {
        item { ScreenHeader(stringResource(R.string.history_title)) }

        if (history.isEmpty()) {
            item {
                InfoCard {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(history, key = { it.export.session.id }) { stored ->
            val reviewed = stored.export.reviewedAnalysis()
            val rejection = stored.syncRejection
            SwipeToReveal(
                primaryAction = {
                    PrimaryActionButton(
                        onClick = { pendingDelete = stored },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_delete)) }
                    )
                },
                onSwipePrimaryAction = { pendingDelete = stored }
            ) {
                TitleCard(
                    onClick = { onOpenSession(stored) },
                    title = { Text(formatSessionDate(stored.export.session.startedAtMillis)) },
                    time = {
                        Text(
                            text = when {
                                stored.synced -> stringResource(R.string.history_synced)
                                rejection != null -> stringResource(R.string.history_action_needed)
                                else -> stringResource(R.string.history_on_watch)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                stored.synced -> CourtColors.Success
                                rejection != null -> CourtColors.Warning
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                ) {
                    DurationStatRow(
                        first = Stat(
                            stringResource(
                                if (reviewed.metrics.hasCorrections) {
                                    R.string.label_reviewed_hits
                                } else {
                                    R.string.label_detected_hits
                                }
                            ),
                            reviewed.metrics.correctedDetectedHitCount.toString()
                        ),
                        second = Stat(
                            stringResource(R.string.label_exchanges),
                            reviewed.rallyProfile.rallyCount.toString()
                        ),
                        durationLabel = stringResource(R.string.label_time),
                        durationMillis = reviewed.window.durationMillis
                    )
                    KnownUnobservedMarker(stored.export)
                    if (rejection != null) {
                        Text(
                            text = stringResource(
                                classifySyncRejection(rejection.reason).messageRes
                            ),
                            style = MaterialTheme.typography.bodyExtraSmall,
                            color = CourtColors.Warning
                        )
                    }
                }
            }
        }
    }

    val selected = pendingDelete
    AlertDialog(
        visible = selected != null,
        onDismissRequest = { pendingDelete = null },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    selected?.let { viewModel.deleteSession(it.export.session.id) }
                    pendingDelete = null
                }
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { pendingDelete = null })
        },
        title = { Text(stringResource(R.string.history_delete_question)) },
        text = {
            Text(
                stringResource(
                    R.string.history_delete_body,
                    selected?.let { formatSessionDate(it.export.session.startedAtMillis) }.orEmpty()
                )
            )
        }
    )
}

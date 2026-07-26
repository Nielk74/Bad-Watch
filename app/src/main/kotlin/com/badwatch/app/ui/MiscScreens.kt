package com.badwatch.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.badwatch.app.R
import com.badwatch.app.localization.localizedUiMessage
import com.badwatch.app.ui.components.WatchScreen

/**
 * Shown for the brief moment while settings hydrate and we don't yet know whether onboarding
 * is needed. It should read as a brand flash, not a stall: a spinner and the wordmark, nothing
 * else to animate or read.
 */
@Composable
fun LoadingScreen() {
    val loadingDescription = stringResource(R.string.loading_brand)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.semantics {
                    contentDescription = loadingDescription
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.brand_wordmark),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Recovery UI when a sensor-owned flow stops. The player may still have recoverable data, so
 * the screen is deliberately quiet: what happened, why, and one explicit safe next step.
 * Every destructive route is confirmed, whether it is a secondary session-journal action or
 * the only viable action after an unsaved Detection Lab stream fails.
 */
@Composable
fun ErrorScreen(
    message: String,
    onDismiss: () -> Unit,
    onDiscardRecovery: (() -> Unit)? = null,
    @StringRes titleResource: Int = R.string.error_session_stopped,
    @StringRes primaryActionResource: Int = R.string.action_dismiss,
    confirmPrimaryAction: Boolean = false,
    @StringRes discardQuestionResource: Int = R.string.error_session_discard_question,
    @StringRes discardBodyResource: Int = R.string.error_session_discard_body
) {
    var confirmDiscard by remember { mutableStateOf(false) }

    WatchScreen {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(titleResource),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = localizedUiMessage(message),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (onDiscardRecovery != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.error_session_failure_options),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Keep the safe/recoverable action before the destructive alternative. Edge actions
        // only appear at the end of a Wear list; at enlarged text that made Discard look like
        // the primary choice while Dismiss was still below the fold.
        item {
            CompactButton(
                onClick = {
                    if (confirmPrimaryAction) confirmDiscard = true else onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(primaryActionResource)) }
            )
        }

        if (onDiscardRecovery != null) {
            item {
                CompactButton(
                    onClick = { confirmDiscard = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    label = { Text(stringResource(R.string.action_discard)) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    AlertDialog(
        visible = confirmDiscard,
        onDismissRequest = { confirmDiscard = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmDiscard = false
                    if (confirmPrimaryAction) onDismiss() else onDiscardRecovery?.invoke()
                }
            )
        },
        title = { Text(stringResource(discardQuestionResource)) },
        text = { Text(stringResource(discardBodyResource)) }
    )
}

package com.badwatch.app.ui

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
import androidx.wear.compose.material3.EdgeButton
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
 * Recovery UI when a session dies mid-record — sensor dropped, service killed. The player may
 * still have a durable recovery checkpoint, so the screen is deliberately quiet: what
 * happened, why, and two explicit choices. Dismiss keeps recovery data for a later retry;
 * discard is destructive and therefore confirmed.
 */
@Composable
fun ErrorScreen(
    message: String,
    onDismiss: () -> Unit,
    onDiscardRecovery: (() -> Unit)? = null
) {
    var confirmDiscard by remember { mutableStateOf(false) }

    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    ) {
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
                    text = stringResource(R.string.error_session_stopped),
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

        item { Spacer(modifier = Modifier.height(48.dp)) }
    }

    AlertDialog(
        visible = confirmDiscard,
        onDismissRequest = { confirmDiscard = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    confirmDiscard = false
                    onDiscardRecovery?.invoke()
                }
            )
        },
        title = { Text(stringResource(R.string.error_session_discard_question)) },
        text = { Text(stringResource(R.string.error_session_discard_body)) }
    )
}

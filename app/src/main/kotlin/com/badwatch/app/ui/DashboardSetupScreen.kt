package com.badwatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.R
import com.badwatch.app.localization.localizedUiMessage
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.app.viewmodel.DashboardConnectionState
import com.badwatch.app.ui.theme.CourtColors

/** Release-safe self-hosted dashboard setup using the watch's normal keyboard/voice input. */
@Composable
fun DashboardSetupScreen(viewModel: BadWatchViewModel) {
    val savedUrl by viewModel.dashboardUrl.collectAsStateWithLifecycle()
    val connection by viewModel.dashboardConnection.collectAsStateWithLifecycle()
    var url by remember(savedUrl) { mutableStateOf(savedUrl.orEmpty()) }
    var replacementToken by remember { mutableStateOf("") }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    WatchScreen {
        item { ScreenHeader(stringResource(R.string.dashboard_setup_title)) }

        item {
            InfoCard {
                Text(
                    text = stringResource(R.string.dashboard_setup_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            LabeledInput(
                label = stringResource(R.string.dashboard_server_url),
                value = url,
                placeholder = stringResource(R.string.dashboard_server_placeholder),
                keyboardType = KeyboardType.Uri,
                onValueChange = { url = it }
            )
        }

        if (url.trim().startsWith("http://", ignoreCase = true)) {
            item {
                InfoCard {
                    Text(
                        text = stringResource(R.string.dashboard_http_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            LabeledInput(
                label = stringResource(R.string.dashboard_bearer_token),
                value = replacementToken,
                placeholder = if (savedUrl == null) {
                    stringResource(R.string.dashboard_optional)
                } else {
                    stringResource(R.string.dashboard_keep_token)
                },
                keyboardType = KeyboardType.Password,
                password = true,
                onValueChange = { replacementToken = it }
            )
        }

        item {
            InfoCard(title = stringResource(R.string.dashboard_connection)) {
                val (text, color) = when (val state = connection) {
                    DashboardConnectionState.NotChecked -> stringResource(R.string.dashboard_not_checked) to
                        MaterialTheme.colorScheme.onSurfaceVariant
                    DashboardConnectionState.Checking -> stringResource(R.string.dashboard_checking) to
                        MaterialTheme.colorScheme.secondary
                    DashboardConnectionState.Connected -> stringResource(R.string.dashboard_connected) to
                        CourtColors.Success
                    is DashboardConnectionState.Failed -> {
                        val localized = localizedUiMessage(state.message)
                        val presentation = if (localized == state.message) {
                            stringResource(R.string.error_dashboard_connection)
                        } else {
                            localized
                        }
                        presentation to MaterialTheme.colorScheme.error
                    }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
            }
        }

        item {
            Button(
                onClick = { viewModel.saveAndCheckDashboard(url, replacementToken) },
                enabled = connection !is DashboardConnectionState.Checking,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dashboard_save_test)) },
                secondaryLabel = { Text(stringResource(R.string.dashboard_save_test_subtitle)) }
            )
        }

        if (savedUrl != null) {
            item {
                CompactButton(
                    onClick = { showRemoveConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    label = { Text(stringResource(R.string.dashboard_remove_server)) }
                )
            }
        }
    }

    AlertDialog(
        visible = showRemoveConfirm,
        onDismissRequest = { showRemoveConfirm = false },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = {
                    showRemoveConfirm = false
                    viewModel.clearDashboard()
                    url = ""
                    replacementToken = ""
                }
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(onClick = { showRemoveConfirm = false })
        },
        title = { Text(stringResource(R.string.dashboard_remove_question)) },
        text = { Text(stringResource(R.string.dashboard_remove_body)) }
    )
}

@Composable
private fun LabeledInput(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(java.util.Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics { contentDescription = label }
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            decorationBox = { inner ->
                Box {
                    if (value.isBlank()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inner()
                }
            }
        )
    }
}

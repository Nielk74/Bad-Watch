package com.badwatch.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonDefaults
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Text
import com.badwatch.app.R
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.core.model.Handedness

/**
 * The one-time first-run screen.
 *
 * Two things must be true before the first session, and this screen settles both before the
 * player ever sees Home. First, the hard requirement, stated up front: shots are detected from
 * the swing itself, so the watch has to sit on the *racket* wrist — worn anywhere else it sees
 * nothing. Second, which hand that is: the sensor sign conventions are mirrored for left-handed
 * players, so the choice is persisted and feeds the classifier from the first rally. The
 * confirmation lives in the edge button, where Wear users expect the primary action.
 */
@Composable
fun OnboardingScreen(onConfirm: (Handedness) -> Unit) {
    var handedness by remember { mutableStateOf(Handedness.Right) }

    WatchScreen(
        edgeButton = {
            EdgeButton(onClick = { onConfirm(handedness) }, buttonSize = EdgeButtonSize.Medium) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(EdgeButtonDefaults.SmallIconSize)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(stringResource(R.string.onboarding_confirm))
            }
        }
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.brand_wordmark),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.onboarding_tagline),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            InfoCard {
                Text(
                    text = stringResource(R.string.onboarding_wrist_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.onboarding_wrist_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            RadioButton(
                selected = handedness == Handedness.Right,
                onSelect = { handedness = Handedness.Right },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.handedness_right)) }
            )
        }
        item {
            RadioButton(
                selected = handedness == Handedness.Left,
                onSelect = { handedness = Handedness.Left },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.handedness_left)) }
            )
        }

        item { Spacer(modifier = Modifier.height(48.dp)) }
    }
}

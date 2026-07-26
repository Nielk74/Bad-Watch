package com.badwatch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import com.badwatch.app.R
import com.badwatch.app.localization.displayNameResource
import com.badwatch.app.ui.components.DetailRow
import com.badwatch.app.ui.components.InfoCard
import com.badwatch.app.ui.components.ScreenHeader
import com.badwatch.app.ui.components.WatchScreen
import com.badwatch.app.viewmodel.BadWatchViewModel
import com.badwatch.core.model.Handedness
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SelfReportedExperience

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
fun SettingsScreen(
    viewModel: BadWatchViewModel,
    onOpenDashboard: () -> Unit,
    onBack: () -> Unit
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val dashboardUrl by viewModel.dashboardUrl.collectAsStateWithLifecycle()
    val shareDetectionLabData by viewModel.shareDetectionLabData.collectAsStateWithLifecycle()
    val detectedHitHaptics by viewModel.detectedHitHaptics.collectAsStateWithLifecycle()
    val ageYears by viewModel.ageYears.collectAsStateWithLifecycle()
    val restingHeartRate by viewModel.configuredRestingHeartRate.collectAsStateWithLifecycle()
    val exactMaxHeartRate by viewModel.configuredMaxHeartRate.collectAsStateWithLifecycle()

    WatchScreen {
        item { ScreenHeader(stringResource(R.string.settings_title)) }

        item {
            SwitchButton(
                checked = profile.handedness == Handedness.Left,
                onCheckedChange = { left ->
                    viewModel.setHandedness(if (left) Handedness.Left else Handedness.Right)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_left_handed)) }
            )
        }

        item {
            SwitchButton(
                checked = detectedHitHaptics,
                onCheckedChange = viewModel::setDetectedHitHaptics,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_hit_haptics)) },
                secondaryLabel = {
                    Text(
                        stringResource(
                            if (detectedHitHaptics) {
                                R.string.settings_hit_haptics_on
                            } else {
                                R.string.settings_hit_haptics_off
                            }
                        )
                    )
                }
            )
        }

        item {
            InfoCard(title = stringResource(R.string.settings_playing_background)) {
                Text(
                    text = stringResource(R.string.settings_experience_caveat),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(SelfReportedExperience.entries.size) { index ->
            val experience = SelfReportedExperience.entries[index]
            RadioButton(
                selected = profile.experience == experience,
                onSelect = { viewModel.setExperience(experience) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(experience.displayNameResource)) }
            )
        }

        item {
            InfoCard(title = stringResource(R.string.settings_hr_profile)) {
                Text(
                    text = stringResource(R.string.settings_hr_explanation),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HeartRateEndpoint(
                    label = stringResource(R.string.settings_age_endpoint),
                    configuredValue = ageYears?.let { age ->
                        if (exactMaxHeartRate == null) {
                            pluralStringResource(
                                R.plurals.settings_age_configured,
                                age,
                                age,
                                PlayerProfile.maxHeartRateForAge(age).toInt()
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.settings_age_overridden,
                                age,
                                age
                            )
                        }
                    },
                    stepperValue = ageYears?.let {
                        pluralStringResource(R.plurals.settings_age_value, it, it)
                    },
                    setDefaultLabel = stringResource(R.string.settings_set_age_default),
                    onSetDefault = { viewModel.setAgeYears(30) },
                    onDecrease = ageYears?.let { age ->
                        { viewModel.setAgeYears((age - 1).coerceAtLeast(18)) }
                    },
                    onIncrease = ageYears?.let { age ->
                        { viewModel.setAgeYears((age + 1).coerceAtMost(100)) }
                    }
                )
                HeartRateEndpoint(
                    label = stringResource(R.string.settings_resting_endpoint),
                    configuredValue = restingHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    stepperValue = restingHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    setDefaultLabel = stringResource(R.string.settings_set_resting_default),
                    onSetDefault = { viewModel.setRestingHeartRate(60) },
                    onDecrease = restingHeartRate?.let { bpm ->
                        { viewModel.setRestingHeartRate((bpm.toInt() - 1).coerceAtLeast(35)) }
                    },
                    onIncrease = restingHeartRate?.let { bpm ->
                        {
                            viewModel.setRestingHeartRate(
                                (bpm.toInt() + 1).coerceAtMost(
                                    minOf(120, exactMaxHeartRate?.toInt()?.minus(1) ?: 120)
                                )
                            )
                        }
                    }
                )
                HeartRateEndpoint(
                    label = stringResource(R.string.settings_max_endpoint),
                    configuredValue = exactMaxHeartRate?.let {
                        stringResource(R.string.settings_max_value, it.toInt())
                    },
                    stepperValue = exactMaxHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    setDefaultLabel = stringResource(R.string.settings_set_max_default),
                    onSetDefault = { viewModel.setMaxHeartRate(187) },
                    onDecrease = exactMaxHeartRate?.let { bpm ->
                        {
                            viewModel.setMaxHeartRate(
                                (bpm.toInt() - 1).coerceAtLeast(
                                    maxOf(100, restingHeartRate?.toInt()?.plus(1) ?: 100)
                                )
                            )
                        }
                    },
                    onIncrease = exactMaxHeartRate?.let { bpm ->
                        { viewModel.setMaxHeartRate((bpm.toInt() + 1).coerceAtMost(240)) }
                    }
                )
                if (ageYears != null || restingHeartRate != null || exactMaxHeartRate != null) {
                    CompactButton(
                        onClick = viewModel::clearHeartRateProfile,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_clear_hr_profile)) }
                    )
                }
            }
        }

        item {
            TitleCard(
                onClick = onOpenDashboard,
                title = { Text(stringResource(R.string.settings_dashboard)) }
            ) {
                Text(
                    text = dashboardUrl ?: stringResource(R.string.settings_dashboard_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_dashboard_intro),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SwitchButton(
                checked = shareDetectionLabData,
                onCheckedChange = viewModel::setShareDetectionLabData,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_share_drills)) },
                secondaryLabel = {
                    Text(
                        if (shareDetectionLabData) {
                            stringResource(R.string.settings_share_drills_on)
                        } else {
                            stringResource(R.string.settings_share_drills_off)
                        }
                    )
                }
            )
        }

        item {
            InfoCard(title = stringResource(R.string.settings_raw_motion)) {
                Text(
                    text = stringResource(R.string.settings_raw_motion_caveat),
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            InfoCard(title = stringResource(R.string.settings_wrist)) {
                Text(
                    text = stringResource(R.string.settings_wrist_caveat),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CompactButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.action_back)) }
            )
        }
    }
}

@Composable
private fun HeartRateEndpoint(
    label: String,
    configuredValue: String?,
    stepperValue: String?,
    setDefaultLabel: String,
    onSetDefault: () -> Unit,
    onDecrease: (() -> Unit)?,
    onIncrease: (() -> Unit)?
) {
    DetailRow(
        label = label,
        value = configuredValue ?: stringResource(R.string.settings_not_configured)
    )
    if (stepperValue == null || onDecrease == null || onIncrease == null) {
        CompactButton(
            onClick = onSetDefault,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(setDefaultLabel) }
        )
    } else {
        val decreaseDescription = stringResource(R.string.a11y_decrease, label)
        val increaseDescription = stringResource(R.string.a11y_increase, label)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactButton(
                onClick = onDecrease,
                modifier = Modifier.semantics { contentDescription = decreaseDescription },
                label = { Text("−") }
            )
            Text(
                text = stepperValue,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            CompactButton(
                onClick = onIncrease,
                modifier = Modifier.semantics { contentDescription = increaseDescription },
                label = { Text("+") }
            )
        }
    }
}

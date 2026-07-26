package com.badwatch.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CompactButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Stepper
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
    onOpenDashboard: () -> Unit
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
                    stepperValue = ageYears,
                    stepperValueLabel = ageYears?.let {
                        pluralStringResource(R.plurals.settings_age_value, it, it)
                    },
                    stepperProgression = 18..100,
                    setDefaultLabel = stringResource(R.string.settings_set_age_default),
                    onSetDefault = { viewModel.setAgeYears(30) },
                    onStepperValueChange = viewModel::setAgeYears
                )
                HeartRateEndpoint(
                    label = stringResource(R.string.settings_resting_endpoint),
                    configuredValue = restingHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    stepperValue = restingHeartRate?.toInt(),
                    stepperValueLabel = restingHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    stepperProgression =
                        35..minOf(120, exactMaxHeartRate?.toInt()?.minus(1) ?: 120),
                    setDefaultLabel = stringResource(R.string.settings_set_resting_default),
                    onSetDefault = { viewModel.setRestingHeartRate(60) },
                    onStepperValueChange = viewModel::setRestingHeartRate
                )
                HeartRateEndpoint(
                    label = stringResource(R.string.settings_max_endpoint),
                    configuredValue = exactMaxHeartRate?.let {
                        stringResource(R.string.settings_max_value, it.toInt())
                    },
                    stepperValue = exactMaxHeartRate?.toInt(),
                    stepperValueLabel = exactMaxHeartRate?.let {
                        stringResource(R.string.settings_resting_value, it.toInt())
                    },
                    stepperProgression =
                        maxOf(100, restingHeartRate?.toInt()?.plus(1) ?: 100)..240,
                    setDefaultLabel = stringResource(R.string.settings_set_max_default),
                    onSetDefault = { viewModel.setMaxHeartRate(187) },
                    onStepperValueChange = viewModel::setMaxHeartRate
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
    }
}

@Composable
private fun HeartRateEndpoint(
    label: String,
    configuredValue: String?,
    stepperValue: Int?,
    stepperValueLabel: String?,
    stepperProgression: IntProgression,
    setDefaultLabel: String,
    onSetDefault: () -> Unit,
    onStepperValueChange: (Int) -> Unit
) {
    DetailRow(
        label = label,
        value = configuredValue ?: stringResource(R.string.settings_not_configured)
    )
    if (stepperValue == null || stepperValueLabel == null) {
        CompactButton(
            onClick = onSetDefault,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(setDefaultLabel) }
        )
    } else {
        Stepper(
            value = stepperValue,
            onValueChange = onStepperValueChange,
            valueProgression = stepperProgression,
            decreaseIcon = {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.a11y_decrease, label)
                )
            },
            increaseIcon = {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.a11y_increase, label)
                )
            }
        ) {
            Text(
                text = stepperValueLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

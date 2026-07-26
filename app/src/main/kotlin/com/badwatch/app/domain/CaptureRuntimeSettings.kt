package com.badwatch.app.domain

import android.os.Build
import com.badwatch.app.data.SettingsStore
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.sync.CaptureDataUse
import com.badwatch.core.sync.CaptureWatch
import kotlinx.coroutines.flow.first

/** Immutable identity, consent and device metadata frozen before raw capture begins. */
data class CaptureRuntimeMetadata(
    val deviceId: String,
    val participantId: String,
    val profile: PlayerProfile,
    val dataUse: CaptureDataUse,
    val watch: CaptureWatch
)

/** Narrow seam that keeps DataStore and Android build fields out of controller JVM tests. */
interface CaptureRuntimeSettings {
    suspend fun snapshot(): CaptureRuntimeMetadata
}

class StoredCaptureRuntimeSettings(
    private val settingsStore: SettingsStore
) : CaptureRuntimeSettings {
    override suspend fun snapshot(): CaptureRuntimeMetadata = CaptureRuntimeMetadata(
        deviceId = settingsStore.ensureDeviceId(),
        participantId = settingsStore.ensureParticipantId(),
        profile = settingsStore.profile.first(),
        dataUse = if (settingsStore.shareDetectionLabData.first()) {
            CaptureDataUse.SelfHostedModelTraining
        } else {
            CaptureDataUse.LocalOnly
        },
        watch = CaptureWatch(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT
        )
    )
}

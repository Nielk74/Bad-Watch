package com.badwatch.app.domain

import com.badwatch.app.data.SettingsStore
import com.badwatch.core.model.PlayerProfile
import kotlinx.coroutines.flow.first

/** The small settings seam required to start or restore a recording. */
interface SessionRuntimeSettings {
    suspend fun currentProfile(): PlayerProfile
    suspend fun stableDeviceId(): String
}

/** Keeps DataStore out of the recovery coordinator and its plain JVM tests. */
class StoredSessionRuntimeSettings(
    private val settingsStore: SettingsStore
) : SessionRuntimeSettings {
    override suspend fun currentProfile(): PlayerProfile = settingsStore.profile.first()

    override suspend fun stableDeviceId(): String = settingsStore.ensureDeviceId()
}

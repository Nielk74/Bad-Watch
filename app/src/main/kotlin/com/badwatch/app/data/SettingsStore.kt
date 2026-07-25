package com.badwatch.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.badwatch.core.model.Handedness
import com.badwatch.core.model.PlayerProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "bad_watch_settings")

/**
 * User settings and the player profile.
 *
 * [deviceId] is generated once per install and is the only identity the dashboard needs —
 * there is deliberately no account system. Sessions are yours; the server just stores what
 * your watch pushes to it.
 */
class SettingsStore(private val context: Context) {

    val profile: Flow<PlayerProfile> = context.preferences.data.map { prefs ->
        PlayerProfile(
            handedness = if (prefs[KEY_LEFT_HANDED] == true) Handedness.Left else Handedness.Right,
            restingHeartRate = prefs[KEY_RESTING_HR] ?: 60f,
            maxHeartRate = prefs[KEY_MAX_HR]
                ?: PlayerProfile.maxHeartRateForAge(prefs[KEY_AGE] ?: 30)
        )
    }

    val dashboardUrl: Flow<String?> = context.preferences.data.map { it[KEY_DASHBOARD_URL] }

    val dashboardToken: Flow<String?> = context.preferences.data.map { it[KEY_DASHBOARD_TOKEN] }

    val onboardingComplete: Flow<Boolean> =
        context.preferences.data.map { it[KEY_ONBOARDED] ?: false }

    val deviceId: Flow<String> = context.preferences.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    /** Creates the install identifier on first read. Idempotent. */
    suspend fun ensureDeviceId(): String {
        var id = ""
        context.preferences.edit { prefs ->
            id = prefs[KEY_DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[KEY_DEVICE_ID] = it }
        }
        return id
    }

    suspend fun setHandedness(handedness: Handedness) {
        context.preferences.edit { it[KEY_LEFT_HANDED] = handedness == Handedness.Left }
    }

    suspend fun setAge(ageYears: Int) {
        context.preferences.edit { it[KEY_AGE] = ageYears }
    }

    suspend fun setRestingHeartRate(bpm: Float) {
        context.preferences.edit { it[KEY_RESTING_HR] = bpm }
    }

    suspend fun setMaxHeartRate(bpm: Float?) {
        context.preferences.edit { prefs ->
            if (bpm == null) prefs.remove(KEY_MAX_HR) else prefs[KEY_MAX_HR] = bpm
        }
    }

    suspend fun setDashboard(url: String?, token: String?) {
        context.preferences.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_DASHBOARD_URL) else prefs[KEY_DASHBOARD_URL] = url.trim()
            if (token.isNullOrBlank()) prefs.remove(KEY_DASHBOARD_TOKEN) else prefs[KEY_DASHBOARD_TOKEN] = token.trim()
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.preferences.edit { it[KEY_ONBOARDED] = complete }
    }

    private companion object {
        val KEY_LEFT_HANDED = booleanPreferencesKey("left_handed")
        val KEY_AGE = intPreferencesKey("age_years")
        val KEY_RESTING_HR = floatPreferencesKey("resting_hr")
        val KEY_MAX_HR = floatPreferencesKey("max_hr")
        val KEY_DASHBOARD_URL = stringPreferencesKey("dashboard_url")
        val KEY_DASHBOARD_TOKEN = stringPreferencesKey("dashboard_token")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }
}

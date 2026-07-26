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
import com.badwatch.core.model.HeartRateValueSource
import com.badwatch.core.model.PlayerProfile
import com.badwatch.core.model.SelfReportedExperience
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.preferences: DataStore<Preferences> by preferencesDataStore(name = "bad_watch_settings")

/**
 * User settings and the player profile.
 *
 * [deviceId] is generated once per install for sync. Labelled motion captures additionally
 * carry a pseudonymous participant id, because a hardware id is not a defensible stand-in
 * for a person in model evaluation. There is deliberately no account system.
 */
class SettingsStore(private val context: Context) {

    val profile: Flow<PlayerProfile> = context.preferences.data.map { prefs ->
        val configuredRestingHeartRate = prefs[KEY_RESTING_HR]
            ?.takeIf { it.isFinite() && it in 35f..120f }
        val configuredMaxHeartRate = prefs[KEY_MAX_HR]
            ?.takeIf { it.isFinite() && it in 100f..240f }
        val configuredAge = prefs[KEY_AGE]?.takeIf { it in 18..100 }
        val restingHeartRate = configuredRestingHeartRate ?: 60f
        val candidateMaxHeartRate = configuredMaxHeartRate
            ?: configuredAge?.let(PlayerProfile::maxHeartRateForAge)
        val hasValidMaximum = candidateMaxHeartRate != null &&
            candidateMaxHeartRate > restingHeartRate
        PlayerProfile(
            handedness = if (prefs[KEY_LEFT_HANDED] == true) Handedness.Left else Handedness.Right,
            restingHeartRate = restingHeartRate,
            maxHeartRate = candidateMaxHeartRate
                ?.takeIf { hasValidMaximum }
                ?: maxOf(PlayerProfile.maxHeartRateForAge(30), restingHeartRate + 1f),
            experience = prefs[KEY_EXPERIENCE]
                ?.let { stored -> SelfReportedExperience.entries.firstOrNull { it.name == stored } }
                ?: SelfReportedExperience.Unspecified,
            restingHeartRateSource = if (configuredRestingHeartRate != null) {
                HeartRateValueSource.UserEntered
            } else {
                HeartRateValueSource.Unconfigured
            },
            maxHeartRateSource = when {
                !hasValidMaximum -> HeartRateValueSource.Unconfigured
                configuredMaxHeartRate != null -> HeartRateValueSource.UserEntered
                configuredAge != null -> HeartRateValueSource.AgeEstimated
                else -> HeartRateValueSource.Unconfigured
            }
        )
    }

    /** Null means the player has not configured an age-based maximum-HR estimate. */
    val ageYears: Flow<Int?> = context.preferences.data.map { preferences ->
        preferences[KEY_AGE]?.takeIf { it in 18..100 }
    }

    /** Null means HR-reserve calculations must remain disabled. */
    val configuredRestingHeartRate: Flow<Float?> =
        context.preferences.data.map { preferences ->
            preferences[KEY_RESTING_HR]?.takeIf { it.isFinite() && it in 35f..120f }
        }

    /** Null means no exact tested maximum; a configured value overrides the age estimate. */
    val configuredMaxHeartRate: Flow<Float?> =
        context.preferences.data.map { preferences ->
            preferences[KEY_MAX_HR]?.takeIf { it.isFinite() && it in 100f..240f }
        }

    val dashboardUrl: Flow<String?> = context.preferences.data.map { it[KEY_DASHBOARD_URL] }

    val dashboardToken: Flow<String?> = context.preferences.data.map { it[KEY_DASHBOARD_TOKEN] }

    val onboardingComplete: Flow<Boolean> =
        context.preferences.data.map { it[KEY_ONBOARDED] ?: false }

    val deviceId: Flow<String> = context.preferences.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: ""
    }

    val participantId: Flow<String> = context.preferences.data.map { prefs ->
        prefs[KEY_PARTICIPANT_ID] ?: ""
    }

    /** Raw labelled motion stays local unless the player explicitly enables this. */
    val shareDetectionLabData: Flow<Boolean> =
        context.preferences.data.map { it[KEY_SHARE_DETECTION_LAB_DATA] ?: false }

    /** Mid-rally feedback is opt-in because some players find any cue distracting. */
    val detectedHitHaptics: Flow<Boolean> =
        context.preferences.data.map { it[KEY_DETECTED_HIT_HAPTICS] ?: false }

    val weeklySessionGoal: Flow<Int> =
        context.preferences.data.map { (it[KEY_WEEKLY_SESSION_GOAL] ?: 3).coerceIn(1, 7) }

    val weeklyRecordedMinutesGoal: Flow<Int> =
        context.preferences.data.map {
            (it[KEY_WEEKLY_RECORDED_MINUTES_GOAL] ?: 120).coerceIn(30, 600)
        }

    /** Creates the install identifier on first read. Idempotent. */
    suspend fun ensureDeviceId(): String {
        var id = ""
        context.preferences.edit { prefs ->
            id = prefs[KEY_DEVICE_ID] ?: UUID.randomUUID().toString().also { prefs[KEY_DEVICE_ID] = it }
        }
        return id
    }

    /**
     * Creates a pseudonymous contributor id independently from the install/device id.
     * It contains no name, email, hardware serial, or Android identifier.
     */
    suspend fun ensureParticipantId(): String {
        var id = ""
        context.preferences.edit { prefs ->
            id = prefs[KEY_PARTICIPANT_ID]
                ?: UUID.randomUUID().toString().also { prefs[KEY_PARTICIPANT_ID] = it }
        }
        return id
    }

    suspend fun setHandedness(handedness: Handedness) {
        context.preferences.edit { it[KEY_LEFT_HANDED] = handedness == Handedness.Left }
    }

    suspend fun setAge(ageYears: Int) {
        context.preferences.edit { it[KEY_AGE] = ageYears.coerceIn(18, 100) }
    }

    suspend fun setRestingHeartRate(bpm: Float) {
        context.preferences.edit { preferences ->
            val upperBound = preferences[KEY_MAX_HR]
                ?.minus(1f)
                ?.coerceAtMost(120f)
                ?: 120f
            preferences[KEY_RESTING_HR] = bpm.coerceIn(35f, upperBound.coerceAtLeast(35f))
        }
    }

    suspend fun setMaxHeartRate(bpm: Float?) {
        context.preferences.edit { prefs ->
            if (bpm == null) {
                prefs.remove(KEY_MAX_HR)
            } else {
                val lowerBound = maxOf(100f, (prefs[KEY_RESTING_HR] ?: 35f) + 1f)
                prefs[KEY_MAX_HR] = bpm.coerceIn(lowerBound, 240f)
            }
        }
    }

    /** Clears every personalized HR endpoint; measured BPM remains available. */
    suspend fun clearHeartRateProfile() {
        context.preferences.edit { prefs ->
            prefs.remove(KEY_AGE)
            prefs.remove(KEY_RESTING_HR)
            prefs.remove(KEY_MAX_HR)
        }
    }

    suspend fun setExperience(experience: SelfReportedExperience) {
        context.preferences.edit { it[KEY_EXPERIENCE] = experience.name }
    }

    suspend fun setDashboard(url: String?, token: String?) {
        context.preferences.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(KEY_DASHBOARD_URL) else prefs[KEY_DASHBOARD_URL] = url.trim()
            if (token.isNullOrBlank()) prefs.remove(KEY_DASHBOARD_TOKEN) else prefs[KEY_DASHBOARD_TOKEN] = token.trim()
        }
    }

    /** Saves a URL while retaining an existing token when the token field was left blank. */
    suspend fun updateDashboard(url: String?, replacementToken: String?) {
        context.preferences.edit { prefs ->
            if (url.isNullOrBlank()) {
                prefs.remove(KEY_DASHBOARD_URL)
            } else {
                prefs[KEY_DASHBOARD_URL] = url.trim()
            }
            replacementToken?.takeIf { it.isNotBlank() }?.let {
                prefs[KEY_DASHBOARD_TOKEN] = it.trim()
            }
        }
    }

    suspend fun clearDashboard() {
        context.preferences.edit { prefs ->
            prefs.remove(KEY_DASHBOARD_URL)
            prefs.remove(KEY_DASHBOARD_TOKEN)
        }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        context.preferences.edit { it[KEY_ONBOARDED] = complete }
    }

    suspend fun setShareDetectionLabData(enabled: Boolean) {
        context.preferences.edit { it[KEY_SHARE_DETECTION_LAB_DATA] = enabled }
    }

    suspend fun setDetectedHitHaptics(enabled: Boolean) {
        context.preferences.edit { it[KEY_DETECTED_HIT_HAPTICS] = enabled }
    }

    suspend fun setWeeklyGoals(sessions: Int, recordedMinutes: Int) {
        context.preferences.edit { prefs ->
            prefs[KEY_WEEKLY_SESSION_GOAL] = sessions.coerceIn(1, 7)
            prefs[KEY_WEEKLY_RECORDED_MINUTES_GOAL] = recordedMinutes.coerceIn(30, 600)
        }
    }

    private companion object {
        val KEY_LEFT_HANDED = booleanPreferencesKey("left_handed")
        val KEY_AGE = intPreferencesKey("age_years")
        val KEY_RESTING_HR = floatPreferencesKey("resting_hr")
        val KEY_MAX_HR = floatPreferencesKey("max_hr")
        val KEY_EXPERIENCE = stringPreferencesKey("self_reported_experience")
        val KEY_DASHBOARD_URL = stringPreferencesKey("dashboard_url")
        val KEY_DASHBOARD_TOKEN = stringPreferencesKey("dashboard_token")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_PARTICIPANT_ID = stringPreferencesKey("participant_id")
        val KEY_SHARE_DETECTION_LAB_DATA = booleanPreferencesKey("share_detection_lab_data")
        val KEY_DETECTED_HIT_HAPTICS = booleanPreferencesKey("detected_hit_haptics")
        val KEY_WEEKLY_SESSION_GOAL = intPreferencesKey("weekly_session_goal")
        val KEY_WEEKLY_RECORDED_MINUTES_GOAL = intPreferencesKey("weekly_recorded_minutes_goal")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }
}

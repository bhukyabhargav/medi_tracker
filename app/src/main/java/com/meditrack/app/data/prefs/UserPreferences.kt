package com.meditrack.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val KEY_IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val KEY_USER_ID = stringPreferencesKey("current_user_id")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_BATTERY_GUIDANCE_SEEN = booleanPreferencesKey("battery_guidance_seen")
        val KEY_FCM_TOKEN = stringPreferencesKey("fcm_token")

        // Stage 12: Privacy, Settings & Notifications
        val KEY_PRIVACY_CONSENT_GIVEN = booleanPreferencesKey("privacy_consent_given")
        val KEY_DOSE_WINDOW_MINUTES = intPreferencesKey("dose_window_minutes")
        val KEY_DEFAULT_SNOOZE_MINUTES = intPreferencesKey("default_snooze_minutes")
        val KEY_NOTIF_MISSED_ENABLED = booleanPreferencesKey("notif_missed_enabled")
        val KEY_NOTIF_STOCK_ENABLED = booleanPreferencesKey("notif_stock_enabled")
        val KEY_NOTIF_UNVERIFIED_ENABLED = booleanPreferencesKey("notif_unverified_enabled")
        val KEY_NOTIF_LATE_ENABLED = booleanPreferencesKey("notif_late_enabled")
        val KEY_FLAG_SECURE_ENABLED = booleanPreferencesKey("flag_secure_enabled")
    }

    val fcmToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_FCM_TOKEN]
    }

    suspend fun saveFcmToken(token: String) {
        dataStore.edit { prefs ->
            prefs[KEY_FCM_TOKEN] = token
        }
    }

    val isLoggedIn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_IS_LOGGED_IN] ?: false
    }

    val currentUserId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_USER_ID]
    }

    val isOnboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val isBatteryGuidanceSeen: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BATTERY_GUIDANCE_SEEN] ?: false
    }

    // Stage 12: Privacy & Consent
    val isPrivacyConsentGiven: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_PRIVACY_CONSENT_GIVEN] ?: false
    }

    suspend fun setPrivacyConsentGiven(given: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[KEY_PRIVACY_CONSENT_GIVEN] = given
        }
    }

    // Stage 12: Reminder settings
    val doseWindowMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_DOSE_WINDOW_MINUTES] ?: 60
    }

    suspend fun setDoseWindowMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_DOSE_WINDOW_MINUTES] = minutes.coerceIn(30, 180)
        }
    }

    val defaultSnoozeMinutes: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_SNOOZE_MINUTES] ?: 15
    }

    suspend fun setDefaultSnoozeMinutes(minutes: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_SNOOZE_MINUTES] = minutes.coerceIn(1, 240)
        }
    }

    // Stage 12: Notification settings
    val notifMissedEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIF_MISSED_ENABLED] ?: true
    }

    suspend fun setNotifMissedEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_MISSED_ENABLED] = enabled
        }
    }

    val notifStockEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIF_STOCK_ENABLED] ?: true
    }

    suspend fun setNotifStockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_STOCK_ENABLED] = enabled
        }
    }

    val notifUnverifiedEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIF_UNVERIFIED_ENABLED] ?: true
    }

    suspend fun setNotifUnverifiedEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_UNVERIFIED_ENABLED] = enabled
        }
    }

    val notifLateEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_NOTIF_LATE_ENABLED] ?: true
    }

    suspend fun setNotifLateEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIF_LATE_ENABLED] = enabled
        }
    }

    // Stage 12: Screenshot protection
    val isFlagSecureEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_FLAG_SECURE_ENABLED] ?: true
    }

    suspend fun setFlagSecureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_FLAG_SECURE_ENABLED] = enabled
        }
    }

    /**
     * Stored password digest for demo-only fake local authentication.
     * Not for production authentication.
     */
    fun getUserPasswordDigest(userId: String): Flow<String?> = dataStore.data.map { prefs ->
        prefs[stringPreferencesKey("cred_digest_$userId")]
    }

    suspend fun saveUserPasswordDigest(userId: String, passwordDigest: String) {
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("cred_digest_$userId")] = passwordDigest
        }
    }

    suspend fun setLoggedIn(userId: String) {
        dataStore.edit { prefs ->
            prefs[KEY_IS_LOGGED_IN] = true
            prefs[KEY_USER_ID] = userId
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setBatteryGuidanceSeen(seen: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[KEY_BATTERY_GUIDANCE_SEEN] = seen
        }
    }

    /**
     * Clears current user session without resetting device-level onboarding or battery guidance flags.
     */
    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs[KEY_IS_LOGGED_IN] = false
            prefs.remove(KEY_USER_ID)
        }
    }

    /**
     * Complete wipe of all user preferences and device flags (for full account deletion).
     */
    suspend fun resetAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

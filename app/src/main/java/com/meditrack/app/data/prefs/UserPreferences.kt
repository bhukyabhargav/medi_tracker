package com.meditrack.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
}

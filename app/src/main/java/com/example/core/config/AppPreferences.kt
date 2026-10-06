package com.example.core.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

data class UserSettings(
    val assistantName: String = "JARVIS",
    val userName: String = "Sir",
    val voiceName: String = "Puck",
    val language: String = "English",
    val themeHue: Float = 187f,
    val wakeWordEnabled: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val overlayBubbleEnabled: Boolean = false,
    val preferredAudioInput: String = "Default Microphone",
    val preferredAudioOutput: String = "Default Speaker",
    val hasCompletedOnboarding: Boolean = false
)

private fun buildSettings(prefs: Preferences): UserSettings = UserSettings(
    assistantName = prefs[Keys.ASSISTANT_NAME]?.takeIf { it.isNotBlank() } ?: "JARVIS",
    userName = prefs[Keys.USER_NAME]?.takeIf { it.isNotBlank() } ?: "Sir",
    voiceName = prefs[Keys.VOICE_NAME]?.takeIf { it.isNotBlank() } ?: "Puck",
    language = prefs[Keys.LANGUAGE]?.takeIf { it.isNotBlank() } ?: "English",
    themeHue = (prefs[Keys.THEME_HUE] ?: 187f).coerceIn(0f, 360f),
    wakeWordEnabled = prefs[Keys.WAKE_WORD_ENABLED] ?: false,
    autoStartOnBoot = prefs[Keys.AUTO_START_ON_BOOT] ?: false,
    overlayBubbleEnabled = prefs[Keys.OVERLAY_BUBBLE] ?: false,
    preferredAudioInput = prefs[Keys.PREFERRED_AUDIO_INPUT] ?: "Default Microphone",
    preferredAudioOutput = prefs[Keys.PREFERRED_AUDIO_OUTPUT] ?: "Default Speaker",
    hasCompletedOnboarding = prefs[Keys.HAS_COMPLETED_ONBOARDING] ?: false
)

private object Keys {
    val ASSISTANT_NAME = stringPreferencesKey("assistant_name")
    val USER_NAME = stringPreferencesKey("user_name")
    val VOICE_NAME = stringPreferencesKey("voice_name")
    val LANGUAGE = stringPreferencesKey("language")
    val THEME_HUE = floatPreferencesKey("theme_hue")
    val WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
    val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
    val OVERLAY_BUBBLE = booleanPreferencesKey("overlay_bubble")
    val PREFERRED_AUDIO_INPUT = stringPreferencesKey("pref_audio_input")
    val PREFERRED_AUDIO_OUTPUT = stringPreferencesKey("pref_audio_output")
    val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
}

class AppPreferences(private val context: Context) {

    val settingsFlow: Flow<UserSettings> = context.dataStore.data
        // Without this catch, a single IOException (corrupt file, storage error) terminates the
        // flow permanently and every collector dies with it.
        .catch { e ->
            if (e is IOException) {
                android.util.Log.e("AppPreferences", "Failed to read settings, using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> buildSettings(prefs) }

    /** One-shot read for callers outside a coroutine flow (e.g. BroadcastReceiver). */
    suspend fun settingsFlowOnce(): UserSettings = settingsFlow.first()

    suspend fun updateAssistantName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { it[Keys.ASSISTANT_NAME] = clean }
    }

    suspend fun updateUserName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        context.dataStore.edit { it[Keys.USER_NAME] = clean }
    }

    suspend fun updateVoiceName(voice: String) {
        context.dataStore.edit { it[Keys.VOICE_NAME] = voice }
    }

    suspend fun updateLanguage(lang: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = lang }
    }

    suspend fun updateThemeHue(hue: Float) {
        context.dataStore.edit { it[Keys.THEME_HUE] = hue.coerceIn(0f, 360f) }
    }

    suspend fun setOverlayBubbleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OVERLAY_BUBBLE] = enabled }
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WAKE_WORD_ENABLED] = enabled }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START_ON_BOOT] = enabled }
    }

    suspend fun setAudioInput(name: String) {
        context.dataStore.edit { it[Keys.PREFERRED_AUDIO_INPUT] = name }
    }

    suspend fun setAudioOutput(name: String) {
        context.dataStore.edit { it[Keys.PREFERRED_AUDIO_OUTPUT] = name }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.HAS_COMPLETED_ONBOARDING] = completed }
    }
}

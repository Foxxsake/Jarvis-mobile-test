package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(val context: Context) {
    companion object {
        val CONFIRMATION_REQUIRED = booleanPreferencesKey("confirmation_required")
        val LOCAL_PROCESSING = booleanPreferencesKey("local_processing")
        val DISABLED_TOOL_IDS = stringSetPreferencesKey("disabled_tool_ids")
        val AI_MODE = stringPreferencesKey("ai_mode")
        val SPOKEN_RESPONSES = booleanPreferencesKey("spoken_responses")
        val HANDS_FREE = booleanPreferencesKey("hands_free")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    }

    val spokenResponsesFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[SPOKEN_RESPONSES] ?: true
        }

    val handsFreeFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[HANDS_FREE] ?: false
        }

    val confirmationRequiredFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[CONFIRMATION_REQUIRED] ?: true
        }

    val localProcessingFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[LOCAL_PROCESSING] ?: true
        }

    val disabledToolIdsFlow: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[DISABLED_TOOL_IDS] ?: emptySet()
        }

    val aiModeFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[AI_MODE] ?: "FREE_FIRST"
        }

    val geminiApiKeyFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[GEMINI_API_KEY] ?: ""
        }

    suspend fun setConfirmationRequired(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CONFIRMATION_REQUIRED] = enabled
        }
    }

    suspend fun setLocalProcessing(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[LOCAL_PROCESSING] = enabled
        }
    }

    suspend fun setSpokenResponses(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SPOKEN_RESPONSES] = enabled
        }
    }

    suspend fun setHandsFree(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HANDS_FREE] = enabled
        }
    }

    suspend fun setToolEnabled(toolId: String, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            val current = preferences[DISABLED_TOOL_IDS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) {
                current.remove(toolId)
            } else {
                current.add(toolId)
            }
            preferences[DISABLED_TOOL_IDS] = current
        }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[GEMINI_API_KEY] = apiKey
        }
    }
}

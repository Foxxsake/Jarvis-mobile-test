package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val CONFIRMATION_REQUIRED = booleanPreferencesKey("confirmation_required")
        val LOCAL_PROCESSING = booleanPreferencesKey("local_processing")
        val DISABLED_TOOL_IDS = stringSetPreferencesKey("disabled_tool_ids")
        val AI_MODE = stringPreferencesKey("ai_mode")
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
}

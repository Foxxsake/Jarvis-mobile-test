package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    companion object {
        val CONFIRMATION_REQUIRED = booleanPreferencesKey("confirmation_required")
        val LOCAL_PROCESSING = booleanPreferencesKey("local_processing")
    }

    val confirmationRequiredFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[CONFIRMATION_REQUIRED] ?: true
        }

    val localProcessingFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[LOCAL_PROCESSING] ?: true
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
}

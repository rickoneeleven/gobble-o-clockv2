package com.example.gobble_o_clockv2.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Context extension property to provide access to the DataStore instance
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/**
 * Manages application settings and state persisted via Jetpack DataStore.
 * Provides reactive Flows for observing preference changes and suspend functions for updates.
 */
class PreferencesRepository(private val context: Context) {

    private val logTag: String = this::class.java.simpleName

    // --- Default Values ---
    private object Defaults {
        val APP_STATE = AppState.MONITORING
        const val CONSECUTIVE_COUNT = 0
        const val TARGET_HEART_RATE = 70
        const val LAST_PROCESSED_TIMESTAMP = 0L
        const val LAST_DISPLAYED_HR = 0
        const val TARGET_HOURS = 6 // Default target hours
        const val MONITORING_START_TIMESTAMP = 0L // Default to 0, indicating not set or session not started
    }

    // --- Flows for Reading Preferences ---

    val appStateFlow: Flow<AppState> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.APP_STATE.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            val stateName = preferences[DataStoreKeys.APP_STATE] ?: Defaults.APP_STATE.name
            try {
                AppState.valueOf(stateName)
            } catch (e: IllegalArgumentException) {
                Log.w(logTag, "Invalid AppState '$stateName' found in DataStore, defaulting to ${Defaults.APP_STATE}.")
                Defaults.APP_STATE
            }
        }

    val consecutiveCountFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.CONSECUTIVE_COUNT] ?: Defaults.CONSECUTIVE_COUNT
        }

    val targetHeartRateFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.TARGET_HEART_RATE.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.TARGET_HEART_RATE] ?: Defaults.TARGET_HEART_RATE
        }

    val lastProcessedTimestampFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.LAST_PROCESSED_TIMESTAMP] ?: Defaults.LAST_PROCESSED_TIMESTAMP
        }

    val lastDisplayedHrFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.LAST_DISPLAYED_HR] ?: Defaults.LAST_DISPLAYED_HR
        }

    val targetHoursFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.TARGET_HOURS.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.TARGET_HOURS] ?: Defaults.TARGET_HOURS
        }

    val monitoringStartTimeFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.MONITORING_START_TIMESTAMP.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.MONITORING_START_TIMESTAMP] ?: Defaults.MONITORING_START_TIMESTAMP
        }

    // --- Functions for Writing Preferences ---

    suspend fun updateAppState(newState: AppState) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.APP_STATE] = newState.name
            }
            Log.i(logTag, "AppState updated to: $newState")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.APP_STATE.name, newState.name)
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.APP_STATE.name, newState.name)
        }
    }

    suspend fun updateConsecutiveCount(newCount: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.CONSECUTIVE_COUNT] = newCount
            }
            Log.d(logTag, "ConsecutiveCount updated to: $newCount") // Changed to debug for frequent updates
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name, newCount.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name, newCount.toString())
        }
    }

    suspend fun updateTargetHeartRate(newRate: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.TARGET_HEART_RATE] = newRate
            }
            Log.i(logTag, "TargetHeartRate updated to: $newRate")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.TARGET_HEART_RATE.name, newRate.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.TARGET_HEART_RATE.name, newRate.toString())
        }
    }

    suspend fun updateLastProcessedTimestamp(newTimestamp: Long) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.LAST_PROCESSED_TIMESTAMP] = newTimestamp
            }
            Log.d(logTag, "LastProcessedTimestamp updated to: $newTimestamp")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name, newTimestamp.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name, newTimestamp.toString())
        }
    }

    suspend fun updateLastDisplayedHr(newHr: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.LAST_DISPLAYED_HR] = newHr
            }
            Log.d(logTag, "LastDisplayedHr updated to: $newHr")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name, newHr.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name, newHr.toString())
        }
    }

    suspend fun updateTargetHours(newHours: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.TARGET_HOURS] = newHours
            }
            Log.i(logTag, "TargetHours updated to: $newHours")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.TARGET_HOURS.name, newHours.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.TARGET_HOURS.name, newHours.toString())
        }
    }

    suspend fun updateMonitoringStartTime(newTimestamp: Long) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.MONITORING_START_TIMESTAMP] = newTimestamp
            }
            Log.i(logTag, "MonitoringStartTime updated to: $newTimestamp")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.MONITORING_START_TIMESTAMP.name, newTimestamp.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.MONITORING_START_TIMESTAMP.name, newTimestamp.toString())
        }
    }

    // --- Private Helper Functions ---

    private fun handleReadException(exception: Throwable, keyName: String) {
        Log.e(logTag, "Error reading preference key '$keyName'. Default value will be used.", exception)
    }

    private fun handleWriteException(exception: Throwable, keyName: String, value: String) {
        Log.e(logTag, "Error writing preference key '$keyName' with value '$value'. Data may not be saved.", exception)
    }
}
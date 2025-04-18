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
 * Adheres to simplicity by taking Context directly for instantiation in this phase.
 */
class PreferencesRepository(private val context: Context) {

    private val logTag: String = this::class.java.simpleName // Use class name for Log Tag

    // --- Default Values ---
    // Encapsulated defaults for consistency and easier modification.
    private object Defaults {
        val APP_STATE = AppState.MONITORING
        const val CONSECUTIVE_COUNT = 0
        const val TARGET_HEART_RATE = 70
        const val LAST_PROCESSED_TIMESTAMP = 0L
        const val LAST_DISPLAYED_HR = 0 // Or perhaps -1 to indicate no valid initial reading? Using 0 for now.
    }

    // --- Flows for Reading Preferences ---

    /** Flow representing the current application state (MONITORING or GOBBLE_TIME). */
    val appStateFlow: Flow<AppState> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.APP_STATE.name)
            emit(emptyPreferences()) // Provide empty preferences to allow map to use default
        }
        .map { preferences ->
            val stateName = preferences[DataStoreKeys.APP_STATE] ?: Defaults.APP_STATE.name
            try {
                AppState.valueOf(stateName)
            } catch (e: IllegalArgumentException) {
                Log.w(logTag, "Invalid AppState '$stateName' found in DataStore, defaulting to ${Defaults.APP_STATE}.")
                Defaults.APP_STATE // Return default if stored value is not a valid enum name
            }
        }

    /** Flow representing the count of consecutive heart rate readings below the target. */
    val consecutiveCountFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.CONSECUTIVE_COUNT] ?: Defaults.CONSECUTIVE_COUNT
        }

    /** Flow representing the user-defined target heart rate. */
    val targetHeartRateFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.TARGET_HEART_RATE.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.TARGET_HEART_RATE] ?: Defaults.TARGET_HEART_RATE
        }

    /** Flow representing the timestamp of the last processed heart rate reading. */
    val lastProcessedTimestampFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.LAST_PROCESSED_TIMESTAMP] ?: Defaults.LAST_PROCESSED_TIMESTAMP
        }

    /** Flow representing the last heart rate value shown in the UI. */
    val lastDisplayedHrFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            handleReadException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name)
            emit(emptyPreferences())
        }
        .map { preferences ->
            preferences[DataStoreKeys.LAST_DISPLAYED_HR] ?: Defaults.LAST_DISPLAYED_HR
        }

    // --- Functions for Writing Preferences ---

    /** Updates the application state in DataStore. */
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

    /** Updates the consecutive count in DataStore. */
    suspend fun updateConsecutiveCount(newCount: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.CONSECUTIVE_COUNT] = newCount
            }
            // Logging potentially too verbose for every count change, uncomment if needed for debugging
            // Log.d(logTag, "ConsecutiveCount updated to: $newCount")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name, newCount.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.CONSECUTIVE_COUNT.name, newCount.toString())
        }
    }

    /** Updates the target heart rate in DataStore. */
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

    /** Updates the last processed timestamp in DataStore. */
    suspend fun updateLastProcessedTimestamp(newTimestamp: Long) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.LAST_PROCESSED_TIMESTAMP] = newTimestamp
            }
            // Verbose logging, uncomment if needed
            // Log.d(logTag, "LastProcessedTimestamp updated to: $newTimestamp")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name, newTimestamp.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.LAST_PROCESSED_TIMESTAMP.name, newTimestamp.toString())
        }
    }

    /** Updates the last displayed heart rate in DataStore. */
    suspend fun updateLastDisplayedHr(newHr: Int) {
        try {
            context.dataStore.edit { preferences ->
                preferences[DataStoreKeys.LAST_DISPLAYED_HR] = newHr
            }
            // Verbose logging, uncomment if needed
            // Log.d(logTag, "LastDisplayedHr updated to: $newHr")
        } catch (exception: IOException) {
            handleWriteException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name, newHr.toString())
        } catch (exception: Exception) {
            handleWriteException(exception, DataStoreKeys.LAST_DISPLAYED_HR.name, newHr.toString())
        }
    }

    // --- Private Helper Functions ---

    /** Logs details of exceptions encountered during DataStore read operations. */
    private fun handleReadException(exception: Throwable, keyName: String) {
        // Log all exceptions encountered during reads for debugging
        Log.e(logTag, "Error reading preference key '$keyName'. Default value will be used.", exception)
        // Potential TODO: Implement more specific error handling or reporting if needed
    }

    /** Logs details of exceptions encountered during DataStore write operations. */
    private fun handleWriteException(exception: Throwable, keyName: String, value: String) {
        // Log all exceptions encountered during writes for debugging
        Log.e(logTag, "Error writing preference key '$keyName' with value '$value'. Data may not be saved.", exception)
        // Potential TODO: Implement retry logic or notify user for critical failures
    }
}
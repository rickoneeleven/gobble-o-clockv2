package com.example.gobble_o_clockv2.data

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Defines the keys used for accessing preferences in Jetpack DataStore.
 * Ensures consistent naming throughout the application.
 */
object DataStoreKeys {
    // Represents the current operational state of the application.
    // See AppState for possible values.
    val APP_STATE = stringPreferencesKey("app_state")

    // Tracks the number of consecutive heart rate readings below the target.
    val CONSECUTIVE_COUNT = intPreferencesKey("consecutive_count")

    // User-defined target heart rate threshold in beats per minute (BPM).
    val TARGET_HEART_RATE = intPreferencesKey("target_heart_rate")

    // Stores the timestamp (in milliseconds since epoch) of the last processed heart rate reading.
    val LAST_PROCESSED_TIMESTAMP = longPreferencesKey("last_processed_timestamp")

    // Stores the last heart rate value displayed in the UI (optional, for smoother updates).
    val LAST_DISPLAYED_HR = intPreferencesKey("last_displayed_hr")
}
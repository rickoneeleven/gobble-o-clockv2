package com.example.gobble_o_clockv2.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.gobble_o_clockv2.MainApplication
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import kotlinx.coroutines.flow.* // Import necessary Flow operators
import kotlinx.coroutines.launch // Import launch
import java.io.IOException

/**
 * Represents the consolidated state for the main UI screen.
 */
data class MainUiState(
    val appState: AppState = AppState.MONITORING,
    val consecutiveCount: Int = 0,
    val lastDisplayedHr: Int = 0,
    val targetHeartRate: Int = 70,
    val isBodySensorsPermissionGranted: Boolean = false,
    val isNotificationsPermissionGranted: Boolean = false
)

/**
 * ViewModel for the main application screen.
 */
class MainViewModel(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val logTag: String = "MainViewModel"

    // Internal state flows for permission status
    private val _bodySensorsPermissionGranted = MutableStateFlow(false)
    private val _notificationsPermissionGranted = MutableStateFlow(false)

    init {
        Log.i(logTag, "MainViewModel initializing.")
    }

    /**
     * Updates the internal state reflecting the BODY_SENSORS permission status.
     */
    fun updateBodySensorsPermissionStatus(isGranted: Boolean) {
        if (_bodySensorsPermissionGranted.value != isGranted) {
            Log.d(logTag, "Updating BODY_SENSORS permission status to: $isGranted")
            _bodySensorsPermissionGranted.value = isGranted
        }
    }

    /**
     * Updates the internal state reflecting the POST_NOTIFICATIONS permission status.
     */
    fun updateNotificationsPermissionStatus(isGranted: Boolean) {
        if (_notificationsPermissionGranted.value != isGranted) {
            Log.d(logTag, "Updating POST_NOTIFICATIONS permission status to: $isGranted")
            _notificationsPermissionGranted.value = isGranted
        }
    }

    // Combine repository flows AND BOTH permission state flows
    val uiState: StateFlow<MainUiState> = combine(
        // Provide the flows as arguments
        preferencesRepository.appStateFlow,
        preferencesRepository.consecutiveCountFlow,
        preferencesRepository.lastDisplayedHrFlow,
        preferencesRepository.targetHeartRateFlow,
        _bodySensorsPermissionGranted,
        _notificationsPermissionGranted
    ) { values: Array<*> -> // Lambda now accepts a single Array parameter

        // --- Destructure and cast values from the array ---
        // Order MUST match the order of flows passed to combine above
        val appStateValue = values[0] as AppState       // Cast element 0 to AppState
        val consecutiveCountValue = values[1] as Int    // Cast element 1 to Int
        val lastDisplayedHrValue = values[2] as Int     // Cast element 2 to Int
        val targetHeartRateValue = values[3] as Int     // Cast element 3 to Int
        val sensorsGrantedValue = values[4] as Boolean  // Cast element 4 to Boolean
        val notificationsGrantedValue = values[5] as Boolean // Cast element 5 to Boolean
        // --- End of destructuring ---

        // Now construct the MainUiState using the destructured values
        MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            isBodySensorsPermissionGranted = sensorsGrantedValue,
            isNotificationsPermissionGranted = notificationsGrantedValue
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        // Initial state assumes permissions are not granted until checked
        initialValue = MainUiState(isBodySensorsPermissionGranted = false, isNotificationsPermissionGranted = false)
    )


    // --- UI Interaction Functions ---

    /**
     * Resets the application state from GOBBLE_TIME back to MONITORING
     * and clears the consecutive count. Requires Body Sensors permission.
     */
    fun resetMonitoring() {
        Log.i(logTag, "Reset monitoring requested by UI.")
        viewModelScope.launch {
            try {
                // Guard: Only allow reset if Body Sensors permission is granted
                if (!_bodySensorsPermissionGranted.value) {
                    Log.w(logTag, "Reset monitoring blocked: Body Sensors permission is not granted.")
                    return@launch
                }

                val currentState = preferencesRepository.appStateFlow.first()
                if (currentState == AppState.GOBBLE_TIME) {
                    Log.d(logTag, "Current state is GOBBLE_TIME, proceeding with reset.")
                    // Update state first, then count. Processor handles timestamps.
                    preferencesRepository.updateAppState(AppState.MONITORING)
                    preferencesRepository.updateConsecutiveCount(0)
                    Log.i(logTag, "App state reset to MONITORING and consecutive count cleared.")
                } else {
                    Log.w(logTag, "Reset requested, but current state is $currentState (not GOBBLE_TIME). No action taken.")
                }
            } catch (e: IOException) {
                Log.e(logTag, "IOException during resetMonitoring data store operation.", e)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to reset monitoring state.", e)
            }
        }
    }

    /**
     * Updates the target heart rate preference.
     * Performs basic validation.
     */
    fun updateTargetHeartRate(newRate: Int) {
        Log.i(logTag, "Update target heart rate requested by UI: $newRate")
        val minTargetHr = 30
        val maxTargetHr = 200
        if (newRate < minTargetHr || newRate > maxTargetHr) {
            Log.w(logTag, "Invalid target heart rate proposed: $newRate (Range: $minTargetHr-$maxTargetHr). Update rejected.")
            // TODO: Provide feedback to the user?
            return
        }
        viewModelScope.launch {
            try {
                preferencesRepository.updateTargetHeartRate(newRate)
                Log.i(logTag, "Target heart rate updated successfully to $newRate.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during updateTargetHeartRate data store operation.", e)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to update target heart rate.", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(logTag, "MainViewModel cleared.")
    }

    // --- ViewModel Factory ---
    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                    val mainApplication = application as? MainApplication
                        ?: throw IllegalStateException("Application instance must be MainApplication for Factory")
                    val preferencesRepository = mainApplication.preferencesRepository
                    // Log.d("MainViewModelFactory", "Creating MainViewModel instance.") // Less verbose
                    return MainViewModel(preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
}
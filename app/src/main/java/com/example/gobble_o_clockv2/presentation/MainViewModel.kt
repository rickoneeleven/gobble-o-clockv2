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
    val isPermissionGranted: Boolean = false // Now reflects actual checked status
)

/**
 * ViewModel for the main application screen.
 */
class MainViewModel(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val logTag: String = "MainViewModel"

    // Internal state flow to hold the current permission status
    private val _permissionGranted = MutableStateFlow(false)

    init {
        Log.i(logTag, "MainViewModel initializing.")
    }

    /**
     * Updates the internal state reflecting the BODY_SENSORS permission status.
     */
    fun updatePermissionStatus(isGranted: Boolean) {
        if (_permissionGranted.value != isGranted) { // Only update if changed
            Log.d(logTag, "Updating permission status to: $isGranted")
            _permissionGranted.value = isGranted
        }
    }

    // Combine repository flows AND the internal permission state flow
    val uiState: StateFlow<MainUiState> = combine(
        preferencesRepository.appStateFlow,
        preferencesRepository.consecutiveCountFlow,
        preferencesRepository.lastDisplayedHrFlow,
        preferencesRepository.targetHeartRateFlow,
        _permissionGranted
    ) { appStateValue, consecutiveCountValue, lastDisplayedHrValue, targetHeartRateValue, permissionGrantedValue ->
        MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            isPermissionGranted = permissionGrantedValue
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )


    // --- UI Interaction Functions ---

    /**
     * Resets the application state from GOBBLE_TIME back to MONITORING
     * and clears the consecutive count.
     */
    fun resetMonitoring() {
        Log.i(logTag, "Reset monitoring requested by UI.")
        viewModelScope.launch {
            try {
                val currentState = preferencesRepository.appStateFlow.first()
                if (currentState == AppState.GOBBLE_TIME) {
                    Log.d(logTag, "Current state is GOBBLE_TIME, proceeding with reset.")
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
     * Performs basic validation (e.g., positive value).
     */
    fun updateTargetHeartRate(newRate: Int) {
        Log.i(logTag, "Update target heart rate requested by UI: $newRate")
        // Basic validation: Ensure rate is somewhat reasonable (e.g., > 30)
        if (newRate < 30 || newRate > 200) { // Example range
            Log.w(logTag, "Invalid target heart rate proposed: $newRate. Update rejected.")
            // TODO: Provide feedback to the user? (e.g., via a temporary state or event)
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
                    Log.d("MainViewModelFactory", "Creating MainViewModel instance.")
                    return MainViewModel(preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
}
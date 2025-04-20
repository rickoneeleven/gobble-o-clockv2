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
    // Expose permission status potentially, though it's mainly used internally for uiState
    // val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    init {
        Log.i(logTag, "MainViewModel initializing.")
        // Initial permission check will happen in the Activity's LaunchedEffect/onResume
    }

    /**
     * Updates the internal state reflecting the BODY_SENSORS permission status.
     * Called by the Activity after checking or receiving the permission request result.
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
        _permissionGranted // Add the permission state flow to the combine
    ) { appStateValue, consecutiveCountValue, lastDisplayedHrValue, targetHeartRateValue, permissionGrantedValue -> // Add parameter for permission

        // Log the combined values (optional, for debugging)
        // Log.d(logTag, "Combining flows: State=$appStateValue, Count=$consecutiveCountValue, HR=$lastDisplayedHrValue, Target=$targetHeartRateValue, Perm=$permissionGrantedValue")

        // Construct the MainUiState object using the received values
        MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            isPermissionGranted = permissionGrantedValue // Use the value from the permission state flow
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState() // Initial state will have isPermissionGranted = false
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
                // Verify current state before resetting (optional, but good practice)
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
                // Consider showing error to user if critical
            } catch (e: Exception) {
                Log.e(logTag, "Failed to reset monitoring state.", e)
                // Consider showing error to user if critical
            }
        }
    }

    // fun updateTargetHeartRate(newRate: Int) { ... } // Placeholder for next task

    override fun onCleared() {
        super.onCleared()
        Log.i(logTag, "MainViewModel cleared.")
    }

    // --- ViewModel Factory (Remains the same) ---
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
package com.example.gobble_o_clockv2.presentation

import android.app.Application // Import Application for ViewModel Factory context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.gobble_o_clockv2.MainApplication // Ensure MainApplication is imported
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository // Ensure PreferencesRepository is imported
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine // Make sure combine is imported
import kotlinx.coroutines.flow.stateIn // Make sure stateIn is imported

/**
 * Represents the consolidated state for the main UI screen.
 * Defined before MainViewModel which uses it.
 */
data class MainUiState(
    val appState: AppState = AppState.MONITORING,
    val consecutiveCount: Int = 0,
    val lastDisplayedHr: Int = 0,
    val targetHeartRate: Int = 70, // Consider sourcing default from repo/constants
    val isPermissionGranted: Boolean = false // Default to false until checked
)

/**
 * ViewModel for the main application screen.
 */
class MainViewModel(
    private val preferencesRepository: PreferencesRepository
    // TODO (Batch 3): Inject context or permission checker for isPermissionGranted
) : ViewModel() {

    // Define logTag *inside* the class body
    private val logTag: String = "MainViewModel" // Use a simple string tag

    init {
        Log.i(logTag, "MainViewModel initializing and starting state flow collection.")
        // TODO (Batch 3): Add permission check logic here later
    }

    // Combine individual flows from the repository into a single UI state flow
    // Carefully check this 'combine' block for syntax errors
    val uiState: StateFlow<MainUiState> = combine(
        // List the flows to combine:
        preferencesRepository.appStateFlow,             // Flow 1: AppState
        preferencesRepository.consecutiveCountFlow,     // Flow 2: Int
        preferencesRepository.lastDisplayedHrFlow,      // Flow 3: Int
        preferencesRepository.targetHeartRateFlow       // Flow 4: Int
        // TODO (Batch 3): Add permission state flow here later
    ) {
        // Define lambda parameters corresponding to the flows above, in the SAME order
            appStateValue, consecutiveCountValue, lastDisplayedHrValue, targetHeartRateValue -> // Use distinct names for clarity

        // Log the received values (optional, for debugging)
        Log.d(logTag, "Combining flows: State=$appStateValue, Count=$consecutiveCountValue, HR=$lastDisplayedHrValue, Target=$targetHeartRateValue")

        // Construct the MainUiState object using the received values
        // Ensure parameter names match the MainUiState constructor fields
        MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            isPermissionGranted = false // TODO (Batch 3): Replace with actual permission status
        )
    }.stateIn( // Convert the combined flow to a StateFlow
        scope = viewModelScope, // Tie collection to ViewModel lifecycle
        started = SharingStarted.WhileSubscribed(5000), // Keep active briefly after UI stops observing
        initialValue = MainUiState() // Provide a default initial state before flows emit
    )


    // --- UI Interaction Functions (To be added in Batch 3) ---
    // fun resetMonitoring() { ... }
    // fun updateTargetHeartRate(newRate: Int) { ... }

    // Override onCleared for cleanup logging
    override fun onCleared() {
        super.onCleared()
        Log.i(logTag, "MainViewModel cleared.")
    }

    // --- ViewModel Factory ---
    // Use companion object for the Factory
    companion object {
        /**
         * Factory for creating MainViewModel instances, providing the necessary PreferencesRepository dependency.
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras // Provides access to Application context
            ): T {
                // Check if the requested modelClass is MainViewModel
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    // Get the Application object from extras
                    val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])

                    // Cast Application to MainApplication to access the repository safely
                    val mainApplication = application as? MainApplication
                        ?: throw IllegalStateException("Application instance must be MainApplication for Factory")

                    // Get the PreferencesRepository from the Application instance
                    val preferencesRepository = mainApplication.preferencesRepository

                    // Create and return the MainViewModel
                    Log.d("MainViewModelFactory", "Creating MainViewModel instance.")
                    return MainViewModel(preferencesRepository) as T
                }
                // If modelClass is not MainViewModel, throw an exception
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
} // End of MainViewModel class
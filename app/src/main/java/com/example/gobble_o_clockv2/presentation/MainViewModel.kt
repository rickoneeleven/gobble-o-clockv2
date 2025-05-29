package com.example.gobble_o_clockv2.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.gobble_o_clockv2.MainApplication
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Represents the consolidated state for the main UI screen.
 */
data class MainUiState(
    val appState: AppState = AppState.MONITORING,
    val consecutiveCount: Int = 0,
    val lastDisplayedHr: Int = 0,
    val targetHeartRate: Int = 70, // Default from PreferencesRepository.Defaults
    val targetHours: Int = 6,    // Default from PreferencesRepository.Defaults
    val monitoringStartTime: Long = 0L, // Default from PreferencesRepository.Defaults
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

    private val _bodySensorsPermissionGranted = MutableStateFlow(false)
    private val _notificationsPermissionGranted = MutableStateFlow(false)

    init {
        Log.i(logTag, "MainViewModel initializing.")
        // Log initial preference values from the already injected preferencesRepository
        viewModelScope.launch {
            Log.d(logTag, "Initial Prefs from repo: AppState=${preferencesRepository.appStateFlow.first()}, TargetHR=${preferencesRepository.targetHeartRateFlow.first()}, TargetHours=${preferencesRepository.targetHoursFlow.first()}, StartTime=${preferencesRepository.monitoringStartTimeFlow.first()}")
        }
    }

    fun updateBodySensorsPermissionStatus(isGranted: Boolean) {
        if (_bodySensorsPermissionGranted.value != isGranted) {
            Log.d(logTag, "Updating BODY_SENSORS permission status to: $isGranted")
            _bodySensorsPermissionGranted.value = isGranted
        }
    }

    fun updateNotificationsPermissionStatus(isGranted: Boolean) {
        if (_notificationsPermissionGranted.value != isGranted) {
            Log.d(logTag, "Updating POST_NOTIFICATIONS permission status to: $isGranted")
            _notificationsPermissionGranted.value = isGranted
        }
    }

    val uiState: StateFlow<MainUiState> = combine(
        preferencesRepository.appStateFlow,
        preferencesRepository.consecutiveCountFlow,
        preferencesRepository.lastDisplayedHrFlow,
        preferencesRepository.targetHeartRateFlow,
        preferencesRepository.targetHoursFlow,
        preferencesRepository.monitoringStartTimeFlow,
        _bodySensorsPermissionGranted,
        _notificationsPermissionGranted
    ) { values: Array<*> ->
        val appStateValue = values[0] as AppState
        val consecutiveCountValue = values[1] as Int
        val lastDisplayedHrValue = values[2] as Int
        val targetHeartRateValue = values[3] as Int
        val targetHoursValue = values[4] as Int
        val monitoringStartTimeValue = values[5] as Long
        val sensorsGrantedValue = values[6] as Boolean
        val notificationsGrantedValue = values[7] as Boolean

        val newState = MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            targetHours = targetHoursValue,
            monitoringStartTime = monitoringStartTimeValue,
            isBodySensorsPermissionGranted = sensorsGrantedValue,
            isNotificationsPermissionGranted = notificationsGrantedValue
        )
        // Log.d(logTag, "UI State Updated: $newState") // Can be verbose
        newState
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        // Use simple, static defaults for initialValue. The combine flow will quickly update it.
        initialValue = MainUiState()
    )


    fun resetMonitoring() {
        Log.i(logTag, "Reset monitoring requested by UI.")
        viewModelScope.launch {
            try {
                if (!_bodySensorsPermissionGranted.value) {
                    Log.w(logTag, "Reset monitoring blocked: Body Sensors permission is not granted.")
                    return@launch
                }

                val currentMonitoringStartTime = System.currentTimeMillis()
                Log.i(logTag, "Setting new MonitoringStartTime: $currentMonitoringStartTime")
                preferencesRepository.updateMonitoringStartTime(currentMonitoringStartTime)
                preferencesRepository.updateAppState(AppState.MONITORING)
                preferencesRepository.updateConsecutiveCount(0)
                Log.i(logTag, "App state reset to MONITORING, consecutive count cleared, and monitoring start time updated.")

            } catch (e: IOException) {
                Log.e(logTag, "IOException during resetMonitoring data store operation.", e)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to reset monitoring state.", e)
            }
        }
    }

    fun updateTargetHeartRate(newRate: Int) {
        Log.i(logTag, "Update target heart rate requested by UI: $newRate")
        val minTargetHr = 30
        val maxTargetHr = 200
        if (newRate < minTargetHr || newRate > maxTargetHr) {
            Log.w(logTag, "Invalid target heart rate proposed: $newRate (Range: $minTargetHr-$maxTargetHr). Update rejected.")
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

    fun updateTargetHours(newHours: Int) {
        Log.i(logTag, "Update target hours requested by UI: $newHours")
        val minTargetHours = 1
        val maxTargetHours = 99
        if (newHours < minTargetHours || newHours > maxTargetHours) {
            Log.w(logTag, "Invalid target hours proposed: $newHours (Range: $minTargetHours-$maxTargetHours). Update rejected.")
            return
        }
        viewModelScope.launch {
            try {
                preferencesRepository.updateTargetHours(newHours)
                Log.i(logTag, "Target hours updated successfully to $newHours.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during updateTargetHours data store operation.", e)
            } catch (e: Exception) {
                Log.e(logTag, "Failed to update target hours.", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(logTag, "MainViewModel cleared.")
    }

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
                    return MainViewModel(preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
}
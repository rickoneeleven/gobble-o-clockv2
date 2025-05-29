package com.example.gobble_o_clockv2.presentation

import android.app.AlarmManager
import android.content.Context
import android.os.Build
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
    val isNotificationsPermissionGranted: Boolean = false,
    val canScheduleExactAlarms: Boolean = true // Default to true, will be updated by ViewModel
)

/**
 * ViewModel for the main application screen.
 */
class MainViewModel(
    private val application: MainApplication, // Use MainApplication to get context for AlarmManager
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    private val logTag: String = "MainViewModel"

    private val _bodySensorsPermissionGranted = MutableStateFlow(false)
    private val _notificationsPermissionGranted = MutableStateFlow(false)
    private val _canScheduleExactAlarms = MutableStateFlow(true) // Initial value, will be updated

    // Lazily initialize AlarmManager
    private val alarmManager: AlarmManager by lazy {
        application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    init {
        Log.i(logTag, "MainViewModel initializing.")
        updateExactAlarmPermissionStatus() // Check and set initial status

        // Log initial preference values from the already injected preferencesRepository
        viewModelScope.launch {
            Log.d(logTag, "Initial Prefs from repo: AppState=${preferencesRepository.appStateFlow.first()}, TargetHR=${preferencesRepository.targetHeartRateFlow.first()}, TargetHours=${preferencesRepository.targetHoursFlow.first()}, StartTime=${preferencesRepository.monitoringStartTimeFlow.first()}")
            Log.d(logTag, "Initial exact alarm schedulable status from flow: ${_canScheduleExactAlarms.value}")
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

    // Call this from Activity's onResume to refresh status after user might have changed settings
    fun updateExactAlarmPermissionStatus() {
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(logTag, "Checking alarmManager.canScheduleExactAlarms() on SDK ${Build.VERSION.SDK_INT}")
            alarmManager.canScheduleExactAlarms()
        } else {
            Log.d(logTag, "SDK < S (${Build.VERSION.SDK_INT}), assuming exact alarms can be scheduled.")
            true // On versions before S, this permission isn't an issue / check doesn't exist
        }

        if (_canScheduleExactAlarms.value != canSchedule) {
            Log.i(logTag, "Updating canScheduleExactAlarms status to: $canSchedule")
            _canScheduleExactAlarms.value = canSchedule
        } else {
            Log.d(logTag, "canScheduleExactAlarms status unchanged: $canSchedule")
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
        _notificationsPermissionGranted,
        _canScheduleExactAlarms // Added the new flow
    ) { values ->
        // Type casting for safety, though combine should provide correct types
        val appStateValue = values[0] as AppState
        val consecutiveCountValue = values[1] as Int
        val lastDisplayedHrValue = values[2] as Int
        val targetHeartRateValue = values[3] as Int
        val targetHoursValue = values[4] as Int
        val monitoringStartTimeValue = values[5] as Long
        val sensorsGrantedValue = values[6] as Boolean
        val notificationsGrantedValue = values[7] as Boolean
        val canScheduleExactAlarmsValue = values[8] as Boolean // Get the new value

        val newState = MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            targetHours = targetHoursValue,
            monitoringStartTime = monitoringStartTimeValue,
            isBodySensorsPermissionGranted = sensorsGrantedValue,
            isNotificationsPermissionGranted = notificationsGrantedValue,
            canScheduleExactAlarms = canScheduleExactAlarmsValue // Populate in UI state
        )
        // Log detailed state changes for debugging, can be made less verbose later
        // Log.d(logTag, "UI State Combined: $newState")
        newState
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState() // Default initial state
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
                        ?: throw IllegalStateException("Application instance must be MainApplication for Factory. Found: ${application.javaClass.name}")
                    val preferencesRepository = mainApplication.preferencesRepository
                    // Pass the MainApplication instance to the ViewModel constructor
                    return MainViewModel(mainApplication, preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
}
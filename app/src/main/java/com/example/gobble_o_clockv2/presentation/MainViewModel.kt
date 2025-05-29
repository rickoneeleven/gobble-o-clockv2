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
        Log.d(logTag, "Initializing AlarmManager.")
        application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    init {
        Log.i(logTag, "MainViewModel initializing.")
        updateExactAlarmPermissionStatus() // Check and set initial status

        viewModelScope.launch {
            Log.d(logTag, "Initial Prefs from repo: AppState=${preferencesRepository.appStateFlow.firstOrNull() ?: "N/A"}, TargetHR=${preferencesRepository.targetHeartRateFlow.firstOrNull() ?: "N/A"}, TargetHours=${preferencesRepository.targetHoursFlow.firstOrNull() ?: "N/A"}, StartTime=${preferencesRepository.monitoringStartTimeFlow.firstOrNull() ?: "N/A"}")
            Log.d(logTag, "Initial exact alarm schedulable status from flow: ${_canScheduleExactAlarms.value}")
        }
    }

    fun updateBodySensorsPermissionStatus(isGranted: Boolean) {
        if (_bodySensorsPermissionGranted.value != isGranted) {
            Log.i(logTag, "Updating BODY_SENSORS permission status. Current: ${_bodySensorsPermissionGranted.value}, New: $isGranted")
            _bodySensorsPermissionGranted.value = isGranted
        } else {
            Log.d(logTag, "BODY_SENSORS permission status unchanged: $isGranted")
        }
    }

    fun updateNotificationsPermissionStatus(isGranted: Boolean) {
        if (_notificationsPermissionGranted.value != isGranted) {
            Log.i(logTag, "Updating POST_NOTIFICATIONS permission status. Current: ${_notificationsPermissionGranted.value}, New: $isGranted")
            _notificationsPermissionGranted.value = isGranted
        } else {
            Log.d(logTag, "POST_NOTIFICATIONS permission status unchanged: $isGranted")
        }
    }

    fun updateExactAlarmPermissionStatus() {
        Log.d(logTag, "Attempting to update exact alarm permission status.")
        val canSchedule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Log.d(logTag, "Checking alarmManager.canScheduleExactAlarms() on SDK ${Build.VERSION.SDK_INT}")
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                Log.e(logTag, "Error checking canScheduleExactAlarms. Defaulting to false.", e)
                false
            }
        } else {
            Log.d(logTag, "SDK < S (${Build.VERSION.SDK_INT}), assuming exact alarms can be scheduled.")
            true
        }

        if (_canScheduleExactAlarms.value != canSchedule) {
            Log.i(logTag, "Updating canScheduleExactAlarms status. Current: ${_canScheduleExactAlarms.value}, New: $canSchedule")
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
        _canScheduleExactAlarms
    ) { values ->
        val appStateValue = values[0] as AppState
        val consecutiveCountValue = values[1] as Int
        val lastDisplayedHrValue = values[2] as Int
        val targetHeartRateValue = values[3] as Int
        val targetHoursValue = values[4] as Int
        val monitoringStartTimeValue = values[5] as Long
        val sensorsGrantedValue = values[6] as Boolean
        val notificationsGrantedValue = values[7] as Boolean
        val canScheduleExactAlarmsValue = values[8] as Boolean

        val newState = MainUiState(
            appState = appStateValue,
            consecutiveCount = consecutiveCountValue,
            lastDisplayedHr = lastDisplayedHrValue,
            targetHeartRate = targetHeartRateValue,
            targetHours = targetHoursValue,
            monitoringStartTime = monitoringStartTimeValue,
            isBodySensorsPermissionGranted = sensorsGrantedValue,
            isNotificationsPermissionGranted = notificationsGrantedValue,
            canScheduleExactAlarms = canScheduleExactAlarmsValue
        )
        // Log.v(logTag, "UI State Combined. New state: $newState") // Verbose, enable if needed
        newState
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )


    fun resetMonitoring() {
        Log.i(logTag, "Action: resetMonitoring requested.")
        viewModelScope.launch {
            if (!_bodySensorsPermissionGranted.value) {
                Log.w(logTag, "Reset monitoring SKIPPED: Body Sensors permission is not granted.")
                return@launch
            }

            val currentMonitoringStartTime = System.currentTimeMillis()
            Log.i(logTag, "Preparing to update preferences for reset: New MonitoringStartTime=$currentMonitoringStartTime, New AppState=MONITORING, New ConsecutiveCount=0")
            try {
                preferencesRepository.updateMonitoringStartTime(currentMonitoringStartTime)
                preferencesRepository.updateAppState(AppState.MONITORING)
                preferencesRepository.updateConsecutiveCount(0)
                Log.i(logTag, "Preferences updated successfully for resetMonitoring.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during resetMonitoring data store operation. StartTime: $currentMonitoringStartTime", e)
            } catch (e: Exception) {
                Log.e(logTag, "General exception during resetMonitoring data store operation. StartTime: $currentMonitoringStartTime", e)
            }
        }
    }

    fun updateTargetHeartRate(newRate: Int) {
        Log.i(logTag, "Action: updateTargetHeartRate requested. NewRate: $newRate")
        val minTargetHr = 30
        val maxTargetHr = 200
        if (newRate < minTargetHr || newRate > maxTargetHr) {
            Log.w(logTag, "Invalid target heart rate proposed: $newRate. Valid range: $minTargetHr-$maxTargetHr. Update REJECTED.")
            return
        }
        viewModelScope.launch {
            Log.d(logTag, "Preparing to update target heart rate preference to: $newRate")
            try {
                preferencesRepository.updateTargetHeartRate(newRate)
                Log.i(logTag, "Target heart rate preference updated successfully to $newRate.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during updateTargetHeartRate data store operation. NewRate: $newRate", e)
            } catch (e: Exception) {
                Log.e(logTag, "General exception during updateTargetHeartRate data store operation. NewRate: $newRate", e)
            }
        }
    }

    fun updateTargetHours(newHours: Int) {
        Log.i(logTag, "Action: updateTargetHours requested. NewHours: $newHours")
        val minTargetHours = 1
        val maxTargetHours = 99
        if (newHours < minTargetHours || newHours > maxTargetHours) {
            Log.w(logTag, "Invalid target hours proposed: $newHours. Valid range: $minTargetHours-$maxTargetHours. Update REJECTED.")
            return
        }
        viewModelScope.launch {
            Log.d(logTag, "Preparing to update target hours preference to: $newHours")
            try {
                preferencesRepository.updateTargetHours(newHours)
                Log.i(logTag, "Target hours preference updated successfully to $newHours.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during updateTargetHours data store operation. NewHours: $newHours", e)
            } catch (e: Exception) {
                Log.e(logTag, "General exception during updateTargetHours data store operation. NewHours: $newHours", e)
            }
        }
    }

    fun stopMonitoringByUser() {
        Log.i(logTag, "Action: stopMonitoringByUser requested.")
        viewModelScope.launch {
            Log.i(logTag, "Preparing to update AppState to GOBBLE_TIME due to user stop request. Current AppState: ${uiState.value.appState}")
            try {
                preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                Log.i(logTag, "AppState successfully updated to GOBBLE_TIME by user request.")
            } catch (e: IOException) {
                Log.e(logTag, "IOException during stopMonitoringByUser (updating AppState to GOBBLE_TIME).", e)
            } catch (e: Exception) {
                Log.e(logTag, "General exception during stopMonitoringByUser (updating AppState to GOBBLE_TIME).", e)
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
                    Log.d("MainViewModel.Factory", "Creating MainViewModel instance with MainApplication: ${mainApplication.packageName} and PreferencesRepository: $preferencesRepository")
                    return MainViewModel(mainApplication, preferencesRepository) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class requested: ${modelClass.name}")
            }
        }
    }
}
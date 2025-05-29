package com.example.gobble_o_clockv2.service

// Android Core Imports
import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log

// AndroidX Core & Lifecycle Imports
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope

// Health Services Imports
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig

// Coroutines Imports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// App Specific Imports
import com.example.gobble_o_clockv2.MainApplication
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import com.example.gobble_o_clockv2.logic.HeartRateProcessor
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    companion object {
        private val logTag: String = HeartRateMonitorService::class.java.simpleName
        private const val FOREGROUND_NOTIFICATION_ID = 101
        private const val GOBBLE_TIME_ALERT_NOTIFICATION_ID = 102
        private const val FOREGROUND_CHANNEL_ID = "gobble_o_clock_foreground_channel_01"
        private const val ALERT_CHANNEL_ID = "gobble_o_clock_alert_channel_01"
        private const val FOREGROUND_CHANNEL_NAME = "Heart Rate Monitoring Status"
        private const val ALERT_CHANNEL_NAME = "Gobble Time Alerts"

        const val ACTION_TARGET_HOURS_EXPIRED = "com.example.gobble_o_clockv2.service.ACTION_TARGET_HOURS_EXPIRED"
        private const val TARGET_HOURS_ALARM_REQUEST_CODE = 123
    }

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private lateinit var heartRateProcessor: HeartRateProcessor
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager

    @Volatile
    private var isListenerRegistered = false
    private var stateObserverJob: Job? = null

    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            if (!::heartRateProcessor.isInitialized) {
                Log.e(logTag, "[Callback] CRITICAL: HeartRateProcessor accessed before initialization. Aborting processing.")
                return
            }
            lifecycleScope.launch {
                heartRateProcessor.processHeartRateData(dataPoints, callbackTimeMillis)
            }
        }

        override fun onPermissionLost() {
            Log.e(logTag, "[Callback] BODY_SENSORS permission lost!")
            cancelTargetHoursAlarm()
            // safelyUnregisterListener is now non-suspend, can be called directly before or inside launch
            safelyUnregisterListener()
            lifecycleScope.launch { // stopSelf can remain here or be outside if no other suspend calls needed
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "[Lifecycle] onCreate - Service creating...")
        try {
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            preferencesRepository = (application as MainApplication).preferencesRepository
            Log.i(logTag, "[Lifecycle] NotificationManager, AlarmManager, PreferencesRepository obtained.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to obtain system services or PreferencesRepository. Stopping service.", e)
            stopSelf(); return
        }

        try {
            heartRateProcessor = HeartRateProcessor(preferencesRepository)
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "[Lifecycle] HeartRateProcessor and PassiveMonitoringClient initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Error initializing core components. Stopping service.", e)
            stopSelf(); return
        }

        createNotificationChannels()
        Log.i(logTag, "[Lifecycle] Service created and dependencies initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "NO_ACTION"
        Log.i(logTag, "[Lifecycle] onStartCommand - Action: $action, StartId: $startId, Flags: $flags")

        if (!::preferencesRepository.isInitialized || !::heartRateProcessor.isInitialized || !::passiveMonitoringClient.isInitialized || !::notificationManager.isInitialized || !::alarmManager.isInitialized) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Service started but dependencies not initialized. Stopping.")
            stopSelf(); return START_NOT_STICKY
        }

        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            Log.e(logTag, "[Lifecycle] CRITICAL: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(logTag, "[Lifecycle] WARNING: Cannot schedule exact alarms. Target Hours feature may not work reliably.")
            } else {
                Log.i(logTag, "[Lifecycle] Exact alarm permission is granted.")
            }
        }

        if (ACTION_TARGET_HOURS_EXPIRED == action) {
            Log.i(logTag, "[Alarm] Target Hours Alarm Expired. Processing...")
            lifecycleScope.launch {
                handleTargetHoursAlarmExpiry()
            }
        }

        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            Log.i(logTag, "[Lifecycle] Foreground service started successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to start foreground service. Stopping.", e)
            stopSelf(); return START_NOT_STICKY
        }

        if (stateObserverJob == null || !stateObserverJob!!.isActive) {
            Log.i(logTag, "[Observer] State observer job not active. Launching.")
            stateObserverJob = lifecycleScope.launch {
                observeAppStateAndTimers()
            }
        } else {
            Log.i(logTag, "[Observer] State observer job already active.")
            lifecycleScope.launch {
                checkAndAlignListenerAndAlarmState()
            }
        }

        Log.i(logTag,"[Lifecycle] onStartCommand returning START_STICKY for action: $action")
        return START_STICKY
    }

    private suspend fun observeAppStateAndTimers() {
        Log.i(logTag, "[Observer] Starting to observe AppState, MonitoringStartTime, and TargetHours.")
        combine(
            preferencesRepository.appStateFlow,
            preferencesRepository.monitoringStartTimeFlow,
            preferencesRepository.targetHoursFlow
        ) { appState, monitoringStartTime, targetHours ->
            Triple(appState, monitoringStartTime, targetHours)
        }.distinctUntilChanged().collect { (appState, monitoringStartTime, targetHours) ->
            Log.i(logTag, "[Observer] State change detected: AppState=$appState, MonitoringStart=$monitoringStartTime, TargetHours=$targetHours. ListenerRegistered=$isListenerRegistered")

            when (appState) {
                AppState.MONITORING -> {
                    if (!isListenerRegistered) {
                        Log.i(logTag, "[Observer] State is MONITORING, listener not registered. Attempting registration.")
                        registerPassiveListener()
                    }
                    if (monitoringStartTime > 0L && targetHours > 0) {
                        Log.i(logTag, "[Observer] AppState is MONITORING. Scheduling/Re-scheduling Target Hours Alarm.")
                        scheduleTargetHoursAlarm(monitoringStartTime, targetHours)
                    } else {
                        Log.w(logTag, "[Observer] AppState is MONITORING, but MonitoringStartTime ($monitoringStartTime) or TargetHours ($targetHours) is invalid. Cancelling any existing alarm.")
                        cancelTargetHoursAlarm()
                    }
                }
                AppState.GOBBLE_TIME -> {
                    if (isListenerRegistered) {
                        Log.i(logTag, "[Observer] State is GOBBLE_TIME, listener registered. Attempting unregistration.")
                        safelyUnregisterListener()
                    }
                    Log.i(logTag, "[Observer] AppState is GOBBLE_TIME. Cancelling Target Hours Alarm.")
                    cancelTargetHoursAlarm()
                    showGobbleTimeAlertNotification()
                }
            }
        }
    }

    private suspend fun checkAndAlignListenerAndAlarmState() {
        val appState = preferencesRepository.appStateFlow.first()
        val monitoringStartTime = preferencesRepository.monitoringStartTimeFlow.first()
        val targetHours = preferencesRepository.targetHoursFlow.first()
        Log.i(logTag, "[AlignState] Checking: AppState=$appState, MonitoringStart=$monitoringStartTime, TargetHours=$targetHours. ListenerRegistered=$isListenerRegistered")

        if (appState == AppState.MONITORING) {
            if (!isListenerRegistered) registerPassiveListener()
            if (monitoringStartTime > 0L && targetHours > 0) {
                scheduleTargetHoursAlarm(monitoringStartTime, targetHours)
            } else {
                cancelTargetHoursAlarm()
            }
        } else {
            if (isListenerRegistered) safelyUnregisterListener()
            cancelTargetHoursAlarm()
        }
    }

    private suspend fun handleTargetHoursAlarmExpiry() {
        Log.i(logTag, "[Alarm] Handling Target Hours Alarm Expiry.")
        val currentAppState = preferencesRepository.appStateFlow.first()
        if (currentAppState == AppState.MONITORING) {
            Log.w(logTag, "[Alarm] Target Hours EXPIRED while still MONITORING. Transitioning to GOBBLE_TIME.")
            preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
        } else {
            Log.i(logTag, "[Alarm] Target Hours Alarm Expired, but AppState is already $currentAppState. No action needed from alarm.")
        }
    }

    private fun scheduleTargetHoursAlarm(monitoringStartTimeMillis: Long, targetHours: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.e(logTag, "[Alarm] Cannot schedule exact alarm: Permission not granted. Target Hours alert will not be reliable.")
        }

        val triggerAtMillis = monitoringStartTimeMillis + (targetHours * 60 * 60 * 1000L)
        val currentTimeMillis = System.currentTimeMillis()

        if (triggerAtMillis <= currentTimeMillis) {
            Log.w(logTag, "[Alarm] Calculated trigger time ($triggerAtMillis) is in the past or now. Not scheduling. Start: $monitoringStartTimeMillis, Hours: $targetHours")
            return
        }

        val alarmIntent = Intent(this, HeartRateMonitorService::class.java).apply {
            action = ACTION_TARGET_HOURS_EXPIRED
        }
        val pendingIntent = PendingIntent.getService(
            this,
            TARGET_HOURS_ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            Log.i(logTag, "[Alarm] Target Hours Alarm SCHEDULED for ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(triggerAtMillis))}. StartTime: $monitoringStartTimeMillis, TargetHours: $targetHours")
        } catch (se: SecurityException) {
            Log.e(logTag, "[Alarm] SecurityException while scheduling exact alarm.", se)
        } catch (e: Exception) {
            Log.e(logTag, "[Alarm] Failed to schedule Target Hours Alarm.", e)
        }
    }

    private fun cancelTargetHoursAlarm() {
        Log.i(logTag, "[Alarm] Attempting to CANCEL Target Hours Alarm.")
        val alarmIntent = Intent(this, HeartRateMonitorService::class.java).apply {
            action = ACTION_TARGET_HOURS_EXPIRED
        }
        val pendingIntent = PendingIntent.getService(
            this,
            TARGET_HOURS_ALARM_REQUEST_CODE,
            alarmIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.i(logTag, "[Alarm] Target Hours Alarm CANCELLED successfully.")
            } catch (e: Exception) {
                Log.e(logTag, "[Alarm] Exception while cancelling alarm.", e)
            }
        } else {
            Log.d(logTag, "[Alarm] No Target Hours Alarm was pending to cancel (PendingIntent was null).")
        }
    }

    override fun onDestroy() {
        Log.i(logTag, "[Lifecycle] onDestroy - Service destroying...")
        stateObserverJob?.cancel(CancellationException("Service is being destroyed"))
        cancelTargetHoursAlarm()
        safelyUnregisterListener() // Now non-suspend
        try {
            notificationManager.cancel(GOBBLE_TIME_ALERT_NOTIFICATION_ID)
            Log.d(logTag, "[Lifecycle] Cancelled Gobble Time alert notification (if present).")
        } catch (e: Exception) {
            Log.w(logTag, "[Lifecycle] Failed to cancel Gobble Time alert notification during destroy.", e)
        }
        super.onDestroy()
        Log.i(logTag, "[Lifecycle] Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun registerPassiveListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to register passive listener...")
        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            Log.w(logTag, "[ListenerMgmt] Registration SKIPPED: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return
        }
        if (isListenerRegistered) {
            Log.d(logTag, "[ListenerMgmt] Registration SKIPPED: Listener already reported as registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: PassiveMonitoringClient not initialized. Stopping service.")
            stopSelf(); return
        }
        val currentState = try { preferencesRepository.appStateFlow.first() } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: Could not read app state.", e); return
        }
        if (currentState != AppState.MONITORING) {
            Log.w(logTag, "[ListenerMgmt] Registration ABORTED: AppState changed to $currentState before registration.")
            return
        }

        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()
        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            isListenerRegistered = true
            Log.i(logTag, "[ListenerMgmt] Passive listener registration submitted. isListenerRegistered optimistically set to true.")
        } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration submission FAILED.", e)
            isListenerRegistered = false
        }
    }

    // Removed suspend modifier as it contains no suspend calls.
    private fun safelyUnregisterListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to unregister passive listener...")
        // Corrected check for client initialization
        if (!isListenerRegistered && (!this::passiveMonitoringClient.isInitialized)) {
            Log.d(logTag, "[ListenerMgmt] Unregistration SKIPPED: Listener not registered or client not initialized.")
            isListenerRegistered = false
            return
        }
        if (!isListenerRegistered) {
            Log.d(logTag, "[ListenerMgmt] Unregistration SKIPPED: Listener not currently reported as registered (flag was false).")
            return
        }
        // Check client initialization again just before use
        if (!this::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "[ListenerMgmt] Unregistration WARNING: Client became uninitialized before use. Setting flag false.")
            isListenerRegistered = false
            return
        }

        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync()
            isListenerRegistered = false
            Log.i(logTag, "[ListenerMgmt] Passive listener unregistration submitted. isListenerRegistered set to false.")
        } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Unregistration submission FAILED.", e)
            isListenerRegistered = false
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID, FOREGROUND_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Ongoing heart rate monitoring status." }
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID, ALERT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for Gobble Time condition."
                enableVibration(true)
            }
            try {
                notificationManager.createNotificationChannel(foregroundChannel)
                notificationManager.createNotificationChannel(alertChannel)
                Log.i(logTag, "[Notification] Channels created/verified.")
            } catch (e: Exception) {
                Log.e(logTag, "[Notification] Failed to create channels.", e)
            }
        }
    }

    private fun createForegroundNotification(): Notification {
        val launchActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = createPendingIntentForActivity(launchActivityIntent, 0)
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring heart rate...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(activityPendingIntent)
            .build()
    }

    private fun showGobbleTimeAlertNotification() {
        Log.i(logTag, "[Notification] Attempting to show Gobble Time alert notification.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.w(logTag, "[Notification] Gobble Time alert SKIPPED: POST_NOTIFICATIONS permission not granted.")
            return
        }
        val launchActivityIntent = Intent(this, MainActivity::class.java).putExtra("source", "gobble_time_alert")
        val activityPendingIntent = createPendingIntentForActivity(launchActivityIntent, 1)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("Gobble Time!")
            .setContentText("Heart rate condition met or max time reached. Monitoring paused.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(activityPendingIntent).setAutoCancel(true)
            .build()
        try {
            if (!::notificationManager.isInitialized) {
                Log.e(logTag, "[Notification] Cannot show Gobble Time alert: NotificationManager not initialized.")
                return
            }
            notificationManager.notify(GOBBLE_TIME_ALERT_NOTIFICATION_ID, notification)
            Log.i(logTag, "[Notification] Gobble Time alert notification successfully posted.")
        } catch (e: Exception) {
            Log.e(logTag, "[Notification] Failed to show Gobble Time alert notification.", e)
        }
    }

    private fun createPendingIntentForActivity(intent: Intent, requestCode: Int): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }
}
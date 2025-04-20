package com.example.gobble_o_clockv2.service

// Android Core Imports
import android.Manifest // <<< Added
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build // Import Build explicitly
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
import kotlinx.coroutines.Job // Import Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect // Import collect explicitly
import kotlinx.coroutines.flow.scan // <<< Added for tracking previous state
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

// App Specific Imports
import com.example.gobble_o_clockv2.MainApplication // Import MainApplication
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import com.example.gobble_o_clockv2.logic.HeartRateProcessor // Import the new Processor
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    // Use companion object for Log Tag for consistency even in instance methods
    companion object {
        private val logTag: String = HeartRateMonitorService::class.java.simpleName
        private const val FOREGROUND_NOTIFICATION_ID = 101
        private const val GOBBLE_TIME_ALERT_NOTIFICATION_ID = 102 // <<< New ID for alert
        private const val FOREGROUND_CHANNEL_ID = "gobble_o_clock_foreground_channel_01" // <<< Renamed for clarity
        private const val ALERT_CHANNEL_ID = "gobble_o_clock_alert_channel_01" // <<< New Channel ID
        private const val FOREGROUND_CHANNEL_NAME = "Heart Rate Monitoring Status" // <<< Renamed for clarity
        private const val ALERT_CHANNEL_NAME = "Gobble Time Alerts" // <<< New Channel Name
    }

    // --- Dependencies ---
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private lateinit var heartRateProcessor: HeartRateProcessor
    private lateinit var notificationManager: NotificationManager // <<< Added manager instance

    // --- State ---
    @Volatile // Ensure visibility across threads, although primary access is main/lifecycleScope
    private var isListenerRegistered = false
    private var stateObserverJob: Job? = null // Keep track of the observer job

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            // Log less verbosely here, Processor handles detailed logging
            // Log.d(logTag, "[Callback] onNewDataPointsReceived at $callbackTimeMillis. Listener registered: $isListenerRegistered")

            // Ensure processor is initialized before processing
            if (!::heartRateProcessor.isInitialized) {
                Log.e(logTag, "[Callback] CRITICAL: HeartRateProcessor accessed before initialization. Aborting processing.")
                return
            }

            // Log.d(logTag, "[Callback] Launching coroutine to process data points.") // Less verbose
            // Delegate processing to the dedicated processor class
            lifecycleScope.launch {
                heartRateProcessor.processHeartRateData(dataPoints, callbackTimeMillis)
            }
        }

        override fun onPermissionLost() {
            Log.e(logTag, "[Callback] BODY_SENSORS permission lost!")
            Log.w(logTag, "[Callback] Unregistering listener and stopping service due to permission loss.")
            lifecycleScope.launch {
                safelyUnregisterListener() // Attempt cleanup
                stopSelf() // Service cannot function without permission
            }
        }
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "[Lifecycle] onCreate - Service creating...")

        // --- Dependency Initialization ---
        Log.d(logTag, "[Lifecycle] Initializing dependencies...")
        try {
            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager // Initialize manager
            preferencesRepository = (application as MainApplication).preferencesRepository
            Log.i(logTag, "[Lifecycle] PreferencesRepository obtained successfully.")
        } catch (e: ClassCastException) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Application context could not be cast to MainApplication. Stopping service.", e)
            stopSelf(); return
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to obtain PreferencesRepository or NotificationManager. Stopping service.", e)
            stopSelf(); return
        }

        try {
            heartRateProcessor = HeartRateProcessor(preferencesRepository)
            Log.i(logTag, "[Lifecycle] HeartRateProcessor initialized successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to initialize HeartRateProcessor. Stopping service.", e)
            stopSelf(); return
        }

        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "[Lifecycle] PassiveMonitoringClient initialized successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Error initializing PassiveMonitoringClient. Stopping service.", e)
            stopSelf(); return
        }

        createNotificationChannels() // Create both channels
        Log.i(logTag, "[Lifecycle] Service created and dependencies initialized.")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.i(logTag, "[Lifecycle] onStartCommand - Action: $action, StartId: $startId, Flags: $flags")

        // --- Pre-computation / Dependency Checks ---
        Log.d(logTag, "[Lifecycle] Checking prerequisites...")
        if (!::preferencesRepository.isInitialized || !::heartRateProcessor.isInitialized || !::passiveMonitoringClient.isInitialized || !::notificationManager.isInitialized) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Service started but dependencies not initialized correctly. Stopping.")
            stopSelf(); return START_NOT_STICKY // Don't restart if fundamental setup failed
        }
        Log.d(logTag, "[Lifecycle] Dependencies confirmed (Repo, Processor, Client, NotifManager).")

        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            Log.e(logTag, "[Lifecycle] CRITICAL: BODY_SENSORS permission not granted at start. Stopping service.")
            stopSelf(); return START_NOT_STICKY // Don't restart if permissions are missing
        }
        Log.d(logTag, "[Lifecycle] BODY_SENSORS permission confirmed.")
        // Note: We don't stop if POST_NOTIFICATIONS isn't granted, alerts will just fail silently.


        Log.i(logTag, "[Lifecycle] Starting foreground service...")
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
            Log.i(logTag, "[Lifecycle] Foreground service started successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to start foreground service. Stopping.", e)
            stopSelf(); return START_NOT_STICKY
        }

        // --- Initial State Handling and Continuous Observation ---
        if (stateObserverJob == null || !stateObserverJob!!.isActive) {
            Log.i(logTag, "[Observer] State observer job not active. Launching initial state check and continuous observer.")
            stateObserverJob = lifecycleScope.launch {
                handleInitialStateAndObserveChanges()
            }
        } else {
            Log.i(logTag, "[Observer] State observer job already active. Skipping launch.")
            lifecycleScope.launch {
                checkAndAlignListenerState()
            }
        }

        Log.i(logTag,"[Lifecycle] onStartCommand returning START_STICKY")
        return START_STICKY
    }

    // --- Helper function to consolidate initial state check and continuous observation ---
    private suspend fun handleInitialStateAndObserveChanges() {
        Log.d(logTag, "[Observer] Coroutine: Starting initial state check.")
        checkAndAlignListenerState() // Perform the initial check

        // --- Continuous State Observation ---
        Log.i(logTag, "[Observer] Coroutine: Starting continuous observer for appStateFlow changes.")
        try {
            // Use scan to keep track of the previous state along with the current state
            preferencesRepository.appStateFlow
                .scan(null as Pair<AppState?, AppState>?) { previousPair, currentState ->
                    // The first emission will have null as previousPair
                    val previousState = previousPair?.second
                    Pair(previousState, currentState)
                }
                .collect { statePair ->
                    if (statePair == null) return@collect // Skip initial null from scan

                    val previousState = statePair.first
                    val currentState = statePair.second

                    Log.i(logTag, "[Observer] Coroutine: Observed AppState change: Prev=$previousState, Curr=$currentState. Listener registered: $isListenerRegistered")

                    // --- State Transition Logic ---
                    if (previousState == AppState.MONITORING && currentState == AppState.GOBBLE_TIME) {
                        Log.w(logTag, "[Observer] !!! GOBBLE TIME DETECTED (Transition from MONITORING) !!!")
                        showGobbleTimeAlertNotification() // Trigger the alert notification
                        // Unregister listener will happen based on current state check below
                    }

                    // --- Listener Alignment Logic ---
                    when (currentState) {
                        AppState.MONITORING -> {
                            if (!isListenerRegistered) {
                                Log.i(logTag, "[Observer] State is MONITORING, listener not registered. Attempting registration.")
                                registerPassiveListener() // Checks permission internally
                            } else {
                                Log.d(logTag, "[Observer] State is MONITORING, listener already registered. No action needed.")
                            }
                        }
                        AppState.GOBBLE_TIME -> {
                            if (isListenerRegistered) {
                                Log.i(logTag, "[Observer] State is GOBBLE_TIME, listener registered. Attempting unregistration.")
                                safelyUnregisterListener()
                            } else {
                                Log.d(logTag, "[Observer] State is GOBBLE_TIME, listener already unregistered. No action needed.")
                            }
                        }
                    }
                }
        } catch (e: CancellationException) {
            Log.i(logTag, "[Observer] Coroutine: appStateFlow collection cancelled (likely service stopping or job cancellation).")
        } catch (e: Exception) {
            Log.e(logTag, "[Observer] Coroutine: Error collecting appStateFlow. Service may not react to external state changes.", e)
        } finally {
            Log.i(logTag, "[Observer] Coroutine: Exiting appStateFlow collection block.")
        }
    }

    // --- Helper function for checking current state and aligning listener ---
    private suspend fun checkAndAlignListenerState() {
        Log.d(logTag, "[AlignState] Checking current AppState and listener status.")
        val currentState = try {
            preferencesRepository.appStateFlow.first() // Read current state
        } catch (e: Exception) {
            Log.e(logTag, "[AlignState] Failed to read app state during alignment check.", e)
            Log.w(logTag,"[AlignState] Cannot determine state, potentially leaving listener misaligned.")
            return
        }

        Log.i(logTag, "[AlignState] Current AppState: $currentState. Listener registered: $isListenerRegistered")
        if (currentState == AppState.MONITORING && !isListenerRegistered) {
            Log.i(logTag, "[AlignState] Action Required: State is MONITORING, listener not registered. Attempting registration.")
            registerPassiveListener()
        } else if (currentState == AppState.GOBBLE_TIME && isListenerRegistered) {
            Log.i(logTag, "[AlignState] Action Required: State is GOBBLE_TIME, listener registered. Attempting unregistration.")
            safelyUnregisterListener()
        } else {
            Log.i(logTag, "[AlignState] No immediate action required. State ($currentState) and listener ($isListenerRegistered) seem aligned.")
        }
    }


    override fun onDestroy() {
        Log.i(logTag, "[Lifecycle] onDestroy - Service destroying...")
        stateObserverJob?.cancel(CancellationException("Service is being destroyed"))
        Log.d(logTag, "[Lifecycle] State observer job cancellation requested.")

        lifecycleScope.launch {
            Log.d(logTag, "[Lifecycle] Attempting final listener unregistration in onDestroy.")
            safelyUnregisterListener()
            // Dismiss any lingering Gobble Time alert notification
            try {
                notificationManager.cancel(GOBBLE_TIME_ALERT_NOTIFICATION_ID)
                Log.d(logTag, "[Lifecycle] Cancelled Gobble Time alert notification (if present).")
            } catch (e: Exception) {
                Log.w(logTag, "[Lifecycle] Failed to cancel Gobble Time alert notification during destroy.", e)
            }
        }

        super.onDestroy() // Call super.onDestroy() last
        Log.i(logTag, "[Lifecycle] Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        Log.d(logTag, "[Lifecycle] onBind - Binding attempted (not supported). Returning null.")
        return null // This service does not support binding
    }

    // --- Permission Handling ---
    private fun hasPermission(permission: String): Boolean { // <<< Generic permission check
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        // Log less verbosely inside this helper
        // Log.d(logTag, "[Permission] Checking $permission: Granted = $granted")
        if (!granted) {
            Log.w(logTag, "[Permission] Permission check returned false for: $permission")
        }
        return granted
    }

    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to register passive listener...")
        Log.d(logTag, "[ListenerMgmt] Current state before registration attempt: isListenerRegistered=$isListenerRegistered")

        // Gate 1: Permission Check
        if (!hasPermission(Manifest.permission.BODY_SENSORS)) {
            Log.w(logTag, "[ListenerMgmt] Registration SKIPPED: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return
        }
        // Gate 2: Already Registered Check
        if (isListenerRegistered) {
            Log.d(logTag, "[ListenerMgmt] Registration SKIPPED: Listener already reported as registered.")
            return
        }
        // Gate 3: Client Initialization Check
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: PassiveMonitoringClient not initialized. Stopping service.")
            stopSelf(); return
        }
        // Gate 4: AppState Check
        val currentState = try { preferencesRepository.appStateFlow.first() } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: Could not read app state immediately before registering.", e); return
        }
        if (currentState != AppState.MONITORING) {
            Log.w(logTag, "[ListenerMgmt] Registration ABORTED: AppState changed to $currentState just before registration call.")
            if (isListenerRegistered) safelyUnregisterListener()
            return
        }

        Log.d(logTag, "[ListenerMgmt] Prerequisites met. Configuring and submitting registration request...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            isListenerRegistered = true
            Log.i(logTag, "[ListenerMgmt] Passive listener registration SUCCESSFUL. isListenerRegistered set to true.")
        } catch (e: SecurityException) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: SecurityException (Permissions revoked?). Setting flag false and stopping service.", e)
            isListenerRegistered = false
            stopSelf()
        } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: General exception. Setting flag false.", e)
            isListenerRegistered = false
        }
    }

    private suspend fun safelyUnregisterListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to unregister passive listener...")
        Log.d(logTag, "[ListenerMgmt] Current state before unregistration attempt: isListenerRegistered=$isListenerRegistered")

        if (!isListenerRegistered) {
            Log.d(logTag, "[ListenerMgmt] Unregistration SKIPPED: Listener not currently reported as registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "[ListenerMgmt] Unregistration WARNING: Client not initialized. Cannot actively unregister, but setting flag false.")
            isListenerRegistered = false
            return
        }

        Log.d(logTag, "[ListenerMgmt] Prerequisites met. Submitting unregistration request...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isListenerRegistered = false
            Log.i(logTag, "[ListenerMgmt] Passive listener unregistration SUCCESSFUL. isListenerRegistered set to false.")
        } catch (e: CancellationException) {
            Log.i(logTag, "[ListenerMgmt] Unregistration task cancelled (coroutine scope likely ending). Setting flag false.", e)
            isListenerRegistered = false
        } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Unregistration FAILED with exception. Setting flag to false optimistically.", e)
            isListenerRegistered = false
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannels() {
        Log.d(logTag, "[Notification] Attempting to create/verify notification channels...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Foreground Service Channel (Low Importance)
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                FOREGROUND_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance for ongoing status
            ).apply {
                description = "Displays the ongoing status of heart rate monitoring."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }

            // Alert Channel (High Importance)
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                ALERT_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // High importance for alerts
            ).apply {
                description = "Alerts when the Gobble Time condition is met."
                setShowBadge(true) // Maybe show a badge for alerts
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC // Show alert on lock screen
                // Enable vibration and lights (using defaults)
                enableVibration(true)
                enableLights(true)
                // Sound is default, can customize with setSound()
            }

            try {
                notificationManager.createNotificationChannel(foregroundChannel)
                Log.i(logTag, "[Notification] Foreground channel '$FOREGROUND_CHANNEL_ID' created or verified.")
                notificationManager.createNotificationChannel(alertChannel)
                Log.i(logTag, "[Notification] Alert channel '$ALERT_CHANNEL_ID' created or verified.")
            } catch (e: Exception) {
                Log.e(logTag, "[Notification] Failed to create one or more notification channels.", e)
            }
        } else {
            Log.d(logTag, "[Notification] Notification channel creation skipped (SDK < 26).")
        }
    }

    private fun createForegroundNotification(): Notification {
        Log.d(logTag, "[Notification] Creating foreground service notification...")
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val activityPendingIntent = createPendingIntent(launchActivityIntent, 0)

        val notificationBuilder = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring heart rate...")
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setOngoing(true)
            .setSilent(true) // No sound for foreground status
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(activityPendingIntent)

        val notification = notificationBuilder.build()
        Log.d(logTag, "[Notification] Foreground service notification constructed.")
        return notification
    }

    private fun showGobbleTimeAlertNotification() {
        Log.i(logTag, "[Notification] Attempting to show Gobble Time alert notification.")

        // --- Permission Check ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Check only on Android 13+
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                Log.w(logTag, "[Notification] Gobble Time alert SKIPPED: POST_NOTIFICATIONS permission not granted.")
                // TODO: Consider alternative feedback? Maybe log to DataStore? For now, just log warning.
                return
            }
            Log.d(logTag, "[Notification] POST_NOTIFICATIONS permission confirmed (or not required).")
        }

        // --- Build Notification ---
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // Optional: Add extra data to indicate it came from the alert
            putExtra("source", "gobble_time_alert")
        }
        val activityPendingIntent = createPendingIntent(launchActivityIntent, 1) // Use different request code


        val notificationBuilder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID) // Use ALERT channel
            .setContentTitle("Gobble Time!") // Clearer Title
            .setContentText("Heart rate threshold met. Monitoring paused.")
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon, could be different
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Ensure high priority
            .setCategory(NotificationCompat.CATEGORY_ALARM) // Use ALARM category for high visibility
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show content on lock screen
            .setContentIntent(activityPendingIntent)
            .setAutoCancel(true) // Dismiss notification when tapped
        // Vibration/Sound/Lights are handled by the channel settings on O+

        // --- Display Notification ---
        try {
            if (!::notificationManager.isInitialized) {
                Log.e(logTag, "[Notification] Cannot show Gobble Time alert: NotificationManager not initialized.")
                return
            }
            notificationManager.notify(GOBBLE_TIME_ALERT_NOTIFICATION_ID, notificationBuilder.build())
            Log.i(logTag, "[Notification] Gobble Time alert notification successfully posted.")
        } catch (e: SecurityException) {
            Log.e(logTag, "[Notification] Failed to show Gobble Time alert due to SecurityException (permissions?).", e)
        } catch (e: Exception) {
            Log.e(logTag, "[Notification] Failed to show Gobble Time alert notification.", e)
        }
    }

    // Helper to create PendingIntents consistently
    private fun createPendingIntent(intent: Intent, requestCode: Int): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT // Mutable is implicitly allowed pre-M
        }
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }
}
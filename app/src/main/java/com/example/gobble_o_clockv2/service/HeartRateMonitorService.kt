package com.example.gobble_o_clockv2.service

// Android Core Imports
import android.Manifest
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
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.SampleDataPoint

// Coroutines Imports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect // Import collect explicitly
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

// App Specific Imports
import com.example.gobble_o_clockv2.MainApplication // Import MainApplication
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    private val logTag: String = HeartRateMonitorService::class.java.simpleName

    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var isListenerRegistered = false // Tracks registration state

    // Reference to the singleton PreferencesRepository from MainApplication
    private lateinit var preferencesRepository: PreferencesRepository

    // --- Constants ---
    companion object {
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
        private const val TIME_GATE_MILLISECONDS = 55000L // Approx 55 seconds gate
        private const val CONSECUTIVE_COUNT_THRESHOLD = 5
    }

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            Log.d(logTag, "Callback: Received DataPointContainer at $callbackTimeMillis")
            lifecycleScope.launch {
                // Ensure repository is initialized before processing
                if (!::preferencesRepository.isInitialized) {
                    Log.e(logTag, "CRITICAL: PreferencesRepository accessed before initialization in callback. Aborting.")
                    return@launch
                }
                processDataPoints(dataPoints, callbackTimeMillis)
            }
        }

        override fun onPermissionLost() {
            Log.e(logTag, "Callback: BODY_SENSORS permission lost. Unregistering listener and stopping service.")
            lifecycleScope.launch {
                safelyUnregisterListener()
                stopSelf() // Service cannot function without permission
            }
        }
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "Creating service...")

        // --- Dependency Initialization ---
        try {
            preferencesRepository = (application as MainApplication).preferencesRepository
            Log.i(logTag, "PreferencesRepository obtained from MainApplication.")
        } catch (e: ClassCastException) {
            Log.e(logTag, "CRITICAL: Application context could not be cast to MainApplication. Check AndroidManifest.xml. Stopping service.", e)
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(logTag, "CRITICAL: Failed to obtain PreferencesRepository. Stopping service.", e)
            stopSelf()
            return
        }

        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "PassiveMonitoringClient initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "Fatal: Error initializing PassiveMonitoringClient during onCreate. Stopping service.", e)
            stopSelf()
            return
        }

        createNotificationChannel()
        Log.i(logTag, "Service created and dependencies initialized.")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.i(logTag, "Service starting command. Action: $action, StartId: $startId")

        // --- Pre-computation / Dependency Checks ---
        if (!::preferencesRepository.isInitialized) {
            Log.e(logTag, "CRITICAL: PreferencesRepository not initialized by onStartCommand. Service likely failed onCreate. Stopping.")
            stopSelf(); return START_NOT_STICKY
        }
        Log.d(logTag, "PreferencesRepository dependency confirmed.")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "CRITICAL: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return START_NOT_STICKY
        }
        Log.d(logTag, "BODY_SENSORS permission confirmed.")

        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "CRITICAL: PassiveMonitoringClient not initialized. Stopping service.")
            stopSelf(); return START_NOT_STICKY
        }
        Log.d(logTag, "PassiveMonitoringClient confirmed.")


        Log.i(logTag, "Starting foreground service and preparing listener registration.")
        startForeground(NOTIFICATION_ID, createNotification())

        // --- Initial State Handling ---
        // Launch a coroutine to handle the initial state check and potential registration
        lifecycleScope.launch {
            val currentState = try {
                preferencesRepository.appStateFlow.first() // Read initial state
            } catch (e: Exception) {
                Log.e(logTag, "Failed to read initial app state before starting service command processing. Stopping.", e)
                stopSelf()
                return@launch
            }

            Log.i(logTag, "Initial AppState check: $currentState. Current listener state: $isListenerRegistered")
            if (currentState == AppState.MONITORING && !isListenerRegistered) {
                Log.i(logTag, "Initial state is MONITORING. Attempting listener registration.")
                registerPassiveListener()
            } else if (currentState == AppState.GOBBLE_TIME && isListenerRegistered) {
                Log.i(logTag, "Initial state is GOBBLE_TIME. Ensuring listener is unregistered.")
                safelyUnregisterListener()
            } else {
                Log.i(logTag, "Initial state ($currentState) and listener state ($isListenerRegistered) require no immediate action.")
            }
        }

        // --- Continuous State Observation ---
        // Launch a separate, long-running coroutine to observe subsequent state changes
        lifecycleScope.launch {
            Log.i(logTag, "Starting observer for appStateFlow changes.")
            try {
                // Collect emits values indefinitely until the scope is cancelled
                preferencesRepository.appStateFlow.collect { state ->
                    Log.d(logTag, "Observed AppState change via collect: $state. Current listener state: $isListenerRegistered")
                    when (state) {
                        AppState.MONITORING -> {
                            if (!isListenerRegistered) {
                                Log.i(logTag, "AppState changed to MONITORING and listener is not registered. Attempting registration.")
                                registerPassiveListener() // Checks permission internally
                            } else {
                                Log.d(logTag, "AppState is MONITORING, but listener is already registered. No action needed.")
                            }
                        }
                        AppState.GOBBLE_TIME -> {
                            if (isListenerRegistered) {
                                Log.i(logTag, "AppState changed to GOBBLE_TIME and listener is registered. Attempting unregistration.")
                                safelyUnregisterListener()
                            } else {
                                Log.d(logTag, "AppState is GOBBLE_TIME, but listener is already unregistered. No action needed.")
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.i(logTag, "appStateFlow collection cancelled (likely service stopping).")
            } catch (e: Exception) {
                Log.e(logTag, "Error collecting appStateFlow. Service may not react to external state changes.", e)
                // Depending on the severity, we might want to stop the service here.
                // For now, logging the error allows the service to potentially continue other operations.
            }
        }

        // Use START_STICKY to request the system restart the service if it's killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(logTag, "Destroying service...")
        // Cancel all coroutines launched in lifecycleScope and attempt cleanup
        lifecycleScope.launch {
            safelyUnregisterListener()
        }
        // Important: Call super.onDestroy() last
        super.onDestroy()
        Log.i(logTag, "Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        Log.d(logTag, "Binding attempted (not supported). Returning null.")
        return null // This service does not support binding
    }

    // --- Permission Handling ---
    private fun hasBodySensorsPermission(): Boolean {
        val permission = Manifest.permission.BODY_SENSORS
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(logTag, "Permission check failed: $permission not granted.")
        }
        return granted
    }

    // --- Data Processing ---
    private suspend fun processDataPoints(container: DataPointContainer, callbackTimeMillis: Long) {
        val hrDataPoints: List<DataPoint<Double>> = container.getData(DataType.HEART_RATE_BPM)
        if (hrDataPoints.isEmpty()) {
            Log.d(logTag, "No HEART_RATE_BPM data points in this container.")
            return
        }

        // Get the most recent HR data point from the container
        val latestDataPoint: SampleDataPoint<Double>? = hrDataPoints
            .filterIsInstance<SampleDataPoint<Double>>()
            .lastOrNull()

        if (latestDataPoint == null) {
            Log.w(logTag, "Could not find a SampleDataPoint for HR in the container.")
            return
        }

        val hrValue: Double = latestDataPoint.value
        val hrIntValue: Int = hrValue.toInt()

        try {
            // Fetch current preferences required for processing logic
            val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
            val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
            var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
            val currentAppState = preferencesRepository.appStateFlow.first() // Check state *before* processing

            // --- Gate 1: Check AppState ---
            if (currentAppState != AppState.MONITORING) {
                Log.d(logTag, "Skipping HR processing: AppState is $currentAppState (not MONITORING).")
                // No need to unregister here; the state observer should handle that.
                return
            }

            // --- Gate 2: Check Time Interval ---
            if (callbackTimeMillis >= lastProcessedTimestamp + TIME_GATE_MILLISECONDS) {
                Log.i(logTag, "Time gate passed (Callback: $callbackTimeMillis >= Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS). Processing HR: $hrIntValue (Target: $targetHeartRate)")

                // --- Core Logic: Compare HR and Update Count ---
                if (hrIntValue <= targetHeartRate) {
                    consecutiveCount++
                    Log.i(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate). Consecutive count incremented to: $consecutiveCount")
                } else {
                    if (consecutiveCount > 0) {
                        Log.i(logTag, "HR above target ($hrIntValue > $targetHeartRate). Resetting consecutive count from $consecutiveCount to 0.")
                        consecutiveCount = 0
                    } else {
                        // No log needed if count is already 0 and HR is above target
                    }
                }

                // --- Persist Changes ---
                preferencesRepository.updateConsecutiveCount(consecutiveCount)
                preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                preferencesRepository.updateLastDisplayedHr(hrIntValue) // Update UI value

                // --- Check for Gobble Time Condition ---
                if (consecutiveCount >= CONSECUTIVE_COUNT_THRESHOLD) {
                    Log.w(logTag, "GOBBLE TIME DETECTED! Consecutive count reached $consecutiveCount.")
                    preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                    // The appStateFlow collector will now observe GOBBLE_TIME and trigger unregistration.
                    // No need to call safelyUnregisterListener() directly here anymore.
                    // TODO: Trigger alert/notification (Batch 4 task)
                }

            } else {
                Log.d(logTag, "Skipping HR processing (HR: $hrIntValue): Time gate NOT passed. (Callback Time: $callbackTimeMillis < Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS)")
                // Also update the displayed HR even if not processed for core logic, provides smoother UI
                preferencesRepository.updateLastDisplayedHr(hrIntValue)
            }

        } catch (e: Exception) {
            Log.e(logTag, "Error during core HR processing logic", e)
            // Avoid crashing the service if possible, log the error.
        }
    }


    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        // Gate 1: Permission Check
        if (!hasBodySensorsPermission()) {
            Log.w(logTag, "Registration skipped: BODY_SENSORS permission not granted.")
            // Attempting to register without permission would cause a crash. Stop service?
            // For now, just log and return. The service startup logic should prevent this state.
            stopSelf() // Stop if permission is missing when trying to register
            return
        }
        // Gate 2: Already Registered Check
        if (isListenerRegistered) {
            Log.d(logTag, "Registration skipped: Listener already registered.")
            return
        }
        // Gate 3: Client Initialization Check
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "Registration failed: Client not initialized. Stopping service.")
            stopSelf() // Cannot proceed without the client
            return
        }
        // Gate 4: AppState Check (Safety check, should be handled by caller logic)
        val currentState = try {
            preferencesRepository.appStateFlow.first()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to read app state immediately before registering listener", e)
            return // Don't register if we can't confirm state
        }
        if (currentState != AppState.MONITORING) {
            Log.w(logTag, "Registration aborted: AppState is $currentState, not MONITORING (checked just before registration).")
            return
        }


        Log.i(logTag, "Attempting to register passive listener for HEART_RATE_BPM...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            // Use the callback defined in this class
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            isListenerRegistered = true // Set flag only on success
            Log.i(logTag, "Passive listener registration submitted successfully.")
        } catch (e: SecurityException) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed: SecurityException (Permissions revoked?). Stopping service.", e)
            stopSelf() // Stop service if permissions fail registration
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed with general exception.", e)
            // Consider stopping the service depending on the exception type
        }
    }

    private suspend fun safelyUnregisterListener() {
        // Gate 1: Already Unregistered Check
        if (!isListenerRegistered) {
            Log.d(logTag, "Unregistration skipped: Listener not currently registered.")
            return
        }
        // Gate 2: Client Initialization Check
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "Unregistration warning: Client not initialized. Cannot unregister, but setting flag false.")
            isListenerRegistered = false // Assume unregistered if client is gone
            return
        }

        Log.i(logTag, "Attempting to unregister passive listener...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isListenerRegistered = false // Set flag only on success
            Log.i(logTag, "Passive listener unregistration successful.")
        } catch (e: CancellationException) {
            // If the coroutine is cancelled, the listener might still be active.
            // However, the service scope is ending, so practically it might not matter.
            // Setting the flag false reflects the intent/state upon cancellation.
            isListenerRegistered = false
            Log.i(logTag, "Listener unregistration task cancelled (coroutine scope likely ending).", e)
            // Rethrow might be appropriate depending on context, but here we just log.
        } catch (e: Exception) {
            // If unregistration fails, the listener *might* still be active.
            // Setting the flag to false is optimistic but prevents repeated failed attempts.
            isListenerRegistered = false
            Log.e(logTag, "Listener unregistration failed. Setting flag to false anyway.", e)
            // Depending on the error, we might need more robust handling.
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        // Check if SDK version is Oreo (API 26) or higher, as notification channels were introduced then.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance to be less intrusive
            ).apply {
                description = "Provides status updates for background heart rate monitoring."
                setShowBadge(false) // Don't show a badge dot on the app icon
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE // Hide sensitive info on lockscreen
            }
            // Get the NotificationManager system service
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
                Log.i(logTag, "Notification channel '$NOTIFICATION_CHANNEL_ID' created or verified.")
            } catch (e: Exception) {
                Log.e(logTag, "Failed to create notification channel '$NOTIFICATION_CHANNEL_ID'. Notifications may not work correctly.", e)
            }
        } else {
            Log.d(logTag, "Notification channel creation skipped (SDK < 26).")
        }
    }

    private fun createNotification(): Notification {
        // Intent to launch MainActivity when the notification is tapped
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            // Flags ensure that tapping the notification brings the existing task to the front
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Determine PendingIntent flags based on SDK version for immutability requirement
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT // FLAG_IMMUTABLE not needed before M
        }

        // Create the PendingIntent for the notification's content action
        val activityPendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0, // Request code (not used here)
            launchActivityIntent,
            pendingIntentFlags
        )

        // Build the notification using NotificationCompat for backward compatibility
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name)) // App name as title
            .setContentText("Monitoring heart rate...") // TODO: Update content text based on AppState? (Future task)
            .setSmallIcon(R.mipmap.ic_launcher) // App icon
            .setOngoing(true) // Makes the notification persistent (cannot be swiped away)
            .setSilent(true) // No sound/vibration for this persistent notification itself
            .setCategory(NotificationCompat.CATEGORY_SERVICE) // Indicates a background service
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Content hidden on secure lockscreen
            .setContentIntent(activityPendingIntent) // Action when tapped

        val notification = notificationBuilder.build()
        Log.d(logTag, "Foreground service notification constructed.")
        return notification
    }
}
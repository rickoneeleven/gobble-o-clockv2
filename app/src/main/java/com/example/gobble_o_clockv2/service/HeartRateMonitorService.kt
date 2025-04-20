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
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig

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
import com.example.gobble_o_clockv2.logic.HeartRateProcessor // Import the new Processor
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    private val logTag: String = HeartRateMonitorService::class.java.simpleName

    // --- Dependencies ---
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private lateinit var heartRateProcessor: HeartRateProcessor // Instance of the new processor

    // --- State ---
    private var isListenerRegistered = false // Tracks Health Services listener registration state

    // --- Constants ---
    companion object {
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
        // Processing-specific constants moved to HeartRateProcessor
    }

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            Log.d(logTag, "Callback: Received DataPointContainer at $callbackTimeMillis")

            // Ensure processor is initialized before processing
            if (!::heartRateProcessor.isInitialized) {
                Log.e(logTag, "CRITICAL: HeartRateProcessor accessed before initialization in callback. Aborting processing.")
                return
            }

            // Delegate processing to the dedicated processor class
            lifecycleScope.launch {
                heartRateProcessor.processHeartRateData(dataPoints, callbackTimeMillis)
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
        // Initialize PreferencesRepository first
        try {
            preferencesRepository = (application as MainApplication).preferencesRepository
            Log.i(logTag, "PreferencesRepository obtained from MainApplication.")
        } catch (e: ClassCastException) {
            Log.e(logTag, "CRITICAL: Application context could not be cast to MainApplication. Check AndroidManifest.xml. Stopping service.", e)
            stopSelf(); return
        } catch (e: Exception) {
            Log.e(logTag, "CRITICAL: Failed to obtain PreferencesRepository. Stopping service.", e)
            stopSelf(); return
        }

        // Initialize HeartRateProcessor (requires PreferencesRepository)
        try {
            heartRateProcessor = HeartRateProcessor(preferencesRepository)
            Log.i(logTag, "HeartRateProcessor initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "CRITICAL: Failed to initialize HeartRateProcessor. Stopping service.", e)
            stopSelf(); return
        }

        // Initialize HealthServices Client
        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "PassiveMonitoringClient initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "Fatal: Error initializing PassiveMonitoringClient during onCreate. Stopping service.", e)
            stopSelf(); return
        }

        createNotificationChannel()
        Log.i(logTag, "Service created and dependencies initialized.")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.i(logTag, "Service starting command. Action: $action, StartId: $startId")

        // --- Pre-computation / Dependency Checks ---
        // Check essential dependencies initialized in onCreate
        if (!::preferencesRepository.isInitialized || !::heartRateProcessor.isInitialized || !::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "CRITICAL: Service started but dependencies not initialized correctly. Stopping.")
            stopSelf(); return START_NOT_STICKY
        }
        Log.d(logTag, "Dependencies confirmed (Repo, Processor, Client).")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "CRITICAL: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return START_NOT_STICKY
        }
        Log.d(logTag, "BODY_SENSORS permission confirmed.")


        Log.i(logTag, "Starting foreground service and preparing listener registration.")
        startForeground(NOTIFICATION_ID, createNotification())

        // --- Initial State Handling ---
        // Launch a coroutine to handle the initial state check and potential registration
        lifecycleScope.launch {
            val currentState = try {
                preferencesRepository.appStateFlow.first() // Read initial state
            } catch (e: Exception) {
                Log.e(logTag, "Failed to read initial app state before starting service command processing. Stopping.", e)
                stopSelf(); return@launch
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
                // Log the error, but allow the service to potentially continue other operations.
            }
        }

        // Use START_STICKY to request the system restart the service if it's killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(logTag, "Destroying service...")
        // Attempt cleanup, cancelling coroutines and unregistering listener
        lifecycleScope.launch {
            safelyUnregisterListener()
        }
        super.onDestroy() // Call super.onDestroy() last
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

    // --- Data Processing (REMOVED) ---
    // The processDataPoints logic is now in HeartRateProcessor

    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        // Gate 1: Permission Check
        if (!hasBodySensorsPermission()) {
            Log.w(logTag, "Registration skipped: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf(); return
        }
        // Gate 2: Already Registered Check
        if (isListenerRegistered) {
            Log.d(logTag, "Registration skipped: Listener already registered.")
            return
        }
        // Gate 3: Client Initialization Check
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "Registration failed: Client not initialized. Stopping service.")
            stopSelf(); return
        }
        // Gate 4: AppState Check (Safety check)
        val currentState = try { preferencesRepository.appStateFlow.first() } catch (e: Exception) {
            Log.e(logTag, "Failed to read app state immediately before registering listener", e); return
        }
        if (currentState != AppState.MONITORING) {
            Log.w(logTag, "Registration aborted: AppState is $currentState, not MONITORING.")
            return
        }

        Log.i(logTag, "Attempting to register passive listener for HEART_RATE_BPM...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            isListenerRegistered = true // Set flag only on success
            Log.i(logTag, "Passive listener registration submitted successfully.")
        } catch (e: SecurityException) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed: SecurityException (Permissions revoked?). Stopping service.", e)
            stopSelf()
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed with general exception.", e)
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
            isListenerRegistered = false; return
        }

        Log.i(logTag, "Attempting to unregister passive listener...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isListenerRegistered = false // Set flag only on success
            Log.i(logTag, "Passive listener unregistration successful.")
        } catch (e: CancellationException) {
            isListenerRegistered = false
            Log.i(logTag, "Listener unregistration task cancelled (coroutine scope likely ending).", e)
        } catch (e: Exception) {
            isListenerRegistered = false // Optimistic flag setting on failure
            Log.e(logTag, "Listener unregistration failed. Setting flag to false anyway.", e)
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Provides status updates for background heart rate monitoring."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
                Log.i(logTag, "Notification channel '$NOTIFICATION_CHANNEL_ID' created or verified.")
            } catch (e: Exception) {
                Log.e(logTag, "Failed to create notification channel '$NOTIFICATION_CHANNEL_ID'.", e)
            }
        } else {
            Log.d(logTag, "Notification channel creation skipped (SDK < 26).")
        }
    }

    private fun createNotification(): Notification {
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val activityPendingIntent: PendingIntent = PendingIntent.getActivity(
            this, 0, launchActivityIntent, pendingIntentFlags
        )

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring heart rate...") // TODO: Update text based on state (future)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(activityPendingIntent)

        val notification = notificationBuilder.build()
        Log.d(logTag, "Foreground service notification constructed.")
        return notification
    }
}
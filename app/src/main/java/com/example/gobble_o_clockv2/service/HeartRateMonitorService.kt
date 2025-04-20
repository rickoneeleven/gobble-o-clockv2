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
import kotlinx.coroutines.Job // Import Job
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

    // Use companion object for Log Tag for consistency even in instance methods
    companion object {
        private val logTag: String = HeartRateMonitorService::class.java.simpleName
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
    }

    // --- Dependencies ---
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private lateinit var heartRateProcessor: HeartRateProcessor

    // --- State ---
    @Volatile // Ensure visibility across threads, although primary access is main/lifecycleScope
    private var isListenerRegistered = false
    private var stateObserverJob: Job? = null // Keep track of the observer job

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            Log.d(logTag, "[Callback] onNewDataPointsReceived at $callbackTimeMillis. Listener registered: $isListenerRegistered")

            // Ensure processor is initialized before processing
            if (!::heartRateProcessor.isInitialized) {
                Log.e(logTag, "[Callback] CRITICAL: HeartRateProcessor accessed before initialization. Aborting processing.")
                return
            }

            Log.d(logTag, "[Callback] Launching coroutine to process data points.")
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
            preferencesRepository = (application as MainApplication).preferencesRepository
            Log.i(logTag, "[Lifecycle] PreferencesRepository obtained successfully.")
        } catch (e: ClassCastException) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Application context could not be cast to MainApplication. Stopping service.", e)
            stopSelf(); return
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to obtain PreferencesRepository. Stopping service.", e)
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

        createNotificationChannel()
        Log.i(logTag, "[Lifecycle] Service created and dependencies initialized.")
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.i(logTag, "[Lifecycle] onStartCommand - Action: $action, StartId: $startId, Flags: $flags")

        // --- Pre-computation / Dependency Checks ---
        Log.d(logTag, "[Lifecycle] Checking prerequisites...")
        if (!::preferencesRepository.isInitialized || !::heartRateProcessor.isInitialized || !::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Service started but dependencies not initialized correctly. Stopping.")
            stopSelf(); return START_NOT_STICKY // Don't restart if fundamental setup failed
        }
        Log.d(logTag, "[Lifecycle] Dependencies confirmed (Repo, Processor, Client).")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "[Lifecycle] CRITICAL: BODY_SENSORS permission not granted at start. Stopping service.")
            stopSelf(); return START_NOT_STICKY // Don't restart if permissions are missing
        }
        Log.d(logTag, "[Lifecycle] BODY_SENSORS permission confirmed.")


        Log.i(logTag, "[Lifecycle] Starting foreground service...")
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(logTag, "[Lifecycle] Foreground service started successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "[Lifecycle] CRITICAL: Failed to start foreground service. Stopping.", e)
            stopSelf(); return START_NOT_STICKY
        }

        // --- Initial State Handling and Continuous Observation ---
        // Only start the observer if it's not already running
        if (stateObserverJob == null || !stateObserverJob!!.isActive) {
            Log.i(logTag, "[Observer] State observer job not active. Launching initial state check and continuous observer.")
            stateObserverJob = lifecycleScope.launch {
                handleInitialStateAndObserveChanges()
            }
        } else {
            Log.i(logTag, "[Observer] State observer job already active. Skipping launch.")
            // If the service is restarted (e.g., START_STICKY), we might still need
            // to ensure the listener state matches the persisted AppState.
            // Trigger a check without restarting the whole collector.
            lifecycleScope.launch {
                checkAndAlignListenerState()
            }
        }

        // Use START_STICKY to request the system restart the service if it's killed
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
            // Collect emits values indefinitely until the scope is cancelled
            preferencesRepository.appStateFlow.collect { state ->
                Log.i(logTag, "[Observer] Coroutine: Observed AppState change: $state. Current listener state: $isListenerRegistered")
                when (state) {
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
            // Log the error, but allow the service to potentially continue other operations.
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
            // Decide if we should stop the service here or rely on subsequent checks
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
        // Cancel the observer job explicitly to prevent leaks and stop collection
        stateObserverJob?.cancel(CancellationException("Service is being destroyed"))
        Log.d(logTag, "[Lifecycle] State observer job cancellation requested.")

        // Attempt cleanup: unregister listener
        // Using a new coroutine scope as the service scope might be cancelling
        // Although lifecycleScope is tied to the service lifecycle, launching here is explicit
        lifecycleScope.launch {
            Log.d(logTag, "[Lifecycle] Attempting final listener unregistration in onDestroy.")
            safelyUnregisterListener()
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
    private fun hasBodySensorsPermission(): Boolean {
        val permission = Manifest.permission.BODY_SENSORS
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        Log.d(logTag, "[Permission] Checking BODY_SENSORS: Granted = $granted")
        if (!granted) {
            Log.w(logTag, "[Permission] BODY_SENSORS permission check returned false.")
        }
        return granted
    }

    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to register passive listener...")
        Log.d(logTag, "[ListenerMgmt] Current state before registration attempt: isListenerRegistered=$isListenerRegistered")

        // Gate 1: Permission Check
        if (!hasBodySensorsPermission()) {
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
        // Gate 4: AppState Check (Safety check - read state *just before* registering)
        val currentState = try { preferencesRepository.appStateFlow.first() } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: Could not read app state immediately before registering.", e); return
        }
        if (currentState != AppState.MONITORING) {
            Log.w(logTag, "[ListenerMgmt] Registration ABORTED: AppState changed to $currentState just before registration call.")
            // If state flipped back to GOBBLE_TIME, ensure listener is *not* registered (double check)
            if (isListenerRegistered) safelyUnregisterListener()
            return
        }

        Log.d(logTag, "[ListenerMgmt] Prerequisites met. Configuring and submitting registration request...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)
            // IMPORTANT: Update state ONLY on success
            isListenerRegistered = true
            Log.i(logTag, "[ListenerMgmt] Passive listener registration SUCCESSFUL. isListenerRegistered set to true.")
        } catch (e: SecurityException) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: SecurityException (Permissions revoked?). Setting flag false and stopping service.", e)
            isListenerRegistered = false // Ensure flag is false on failure
            stopSelf()
        } catch (e: Exception) {
            Log.e(logTag, "[ListenerMgmt] Registration FAILED: General exception. Setting flag false.", e)
            isListenerRegistered = false // Ensure flag is false on failure
            // Consider if we should stopSelf() here too, depending on the exception type
        }
    }

    private suspend fun safelyUnregisterListener() {
        Log.i(logTag, "[ListenerMgmt] Attempting to unregister passive listener...")
        Log.d(logTag, "[ListenerMgmt] Current state before unregistration attempt: isListenerRegistered=$isListenerRegistered")

        // Gate 1: Already Unregistered Check
        if (!isListenerRegistered) {
            Log.d(logTag, "[ListenerMgmt] Unregistration SKIPPED: Listener not currently reported as registered.")
            return
        }
        // Gate 2: Client Initialization Check
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "[ListenerMgmt] Unregistration WARNING: Client not initialized. Cannot actively unregister, but setting flag false.")
            isListenerRegistered = false // Set flag false even if we can't call API
            return
        }

        Log.d(logTag, "[ListenerMgmt] Prerequisites met. Submitting unregistration request...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            // IMPORTANT: Update state ONLY on success
            isListenerRegistered = false
            Log.i(logTag, "[ListenerMgmt] Passive listener unregistration SUCCESSFUL. isListenerRegistered set to false.")
        } catch (e: CancellationException) {
            Log.i(logTag, "[ListenerMgmt] Unregistration task cancelled (coroutine scope likely ending). Setting flag false.", e)
            isListenerRegistered = false // Set flag false as the operation was interrupted
        } catch (e: Exception) {
            // If unregistration fails, the listener might *still* be active.
            // Should we keep isListenerRegistered = true? This is safer but might lead to retry loops.
            // Setting it to false might be optimistic but prevents loops if the API call fails temporarily.
            // Let's log aggressively and set to false for now to avoid potential loops on transient errors.
            Log.e(logTag, "[ListenerMgmt] Unregistration FAILED with exception. Setting flag to false optimistically.", e)
            isListenerRegistered = false
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        Log.d(logTag, "[Notification] Attempting to create/verify notification channel...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance to be less intrusive
            ).apply {
                description = "Provides status updates for background heart rate monitoring."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE // Hide details on lock screen
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try {
                notificationManager.createNotificationChannel(channel)
                Log.i(logTag, "[Notification] Notification channel '$NOTIFICATION_CHANNEL_ID' created or verified.")
            } catch (e: Exception) {
                Log.e(logTag, "[Notification] Failed to create notification channel '$NOTIFICATION_CHANNEL_ID'.", e)
            }
        } else {
            Log.d(logTag, "[Notification] Notification channel creation skipped (SDK < 26).")
        }
    }

    private fun createNotification(): Notification {
        Log.d(logTag, "[Notification] Creating foreground service notification...")
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            // Flags to bring existing task to foreground or start new one
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT // Mutable is implicitly allowed pre-M
        }

        val activityPendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0, // Request code
            launchActivityIntent,
            pendingIntentFlags
        )

        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring heart rate...") // TODO: Could dynamically update this based on state later
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this icon exists
            .setOngoing(true) // Makes it non-dismissible
            .setSilent(true) // No sound on initial display or updates
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // Content hidden on secure lock screen
            .setContentIntent(activityPendingIntent) // Action when tapped

        val notification = notificationBuilder.build()
        Log.d(logTag, "[Notification] Foreground service notification constructed.")
        return notification
    }
}
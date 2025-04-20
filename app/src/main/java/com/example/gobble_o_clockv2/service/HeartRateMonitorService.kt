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
import kotlinx.coroutines.guava.await // Still needed for clearPassiveListenerCallbackAsync
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
    private var isListenerRegistered = false

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
                stopSelf()
            }
        }
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "Creating service...")

        // --- Dependency Initialization ---
        // Get the singleton instance from MainApplication
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
        // --- End Dependency Initialization ---


        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "PassiveMonitoringClient initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "Fatal: Error initializing PassiveMonitoringClient during onCreate. Stopping service.", e)
            // Ensure dependencies are cleaned up if initialization fails partially
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
        // preferencesRepository should be initialized in onCreate now
        if (!::preferencesRepository.isInitialized) {
            Log.e(logTag, "CRITICAL: PreferencesRepository not initialized by onStartCommand. Service likely failed onCreate. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(logTag, "PreferencesRepository dependency confirmed (from Application).")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "CRITICAL: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(logTag, "BODY_SENSORS permission confirmed.")

        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "CRITICAL: PassiveMonitoringClient not initialized. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(logTag, "PassiveMonitoringClient confirmed.")


        Log.i(logTag, "Starting foreground service and preparing listener registration.")
        startForeground(NOTIFICATION_ID, createNotification())

        lifecycleScope.launch {
            val currentState = try {
                preferencesRepository.appStateFlow.first()
            } catch (e: Exception) {
                Log.e(logTag, "Failed to read app state before starting service command processing. Stopping.", e)
                stopSelf()
                return@launch
            }

            if (currentState == AppState.MONITORING) {
                Log.i(logTag, "Current state is MONITORING. Attempting listener registration.")
                registerPassiveListener()
            } else {
                Log.i(logTag, "Service started but state is $currentState. Listener not registered.")
                safelyUnregisterListener()
            }

            // TODO: Implement continuous observation of appStateFlow (Next Step)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(logTag, "Destroying service...")
        lifecycleScope.launch {
            safelyUnregisterListener()
        }
        super.onDestroy()
        Log.i(logTag, "Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        Log.d(logTag, "Binding attempted (not supported). Returning null.")
        return null
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
            // Access preferencesRepository directly (it's initialized in onCreate)
            val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
            val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
            var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
            val currentAppState = preferencesRepository.appStateFlow.first()

            if (currentAppState != AppState.MONITORING) {
                Log.d(logTag, "Skipping HR processing: AppState is $currentAppState.")
                return
            }

            if (callbackTimeMillis >= lastProcessedTimestamp + TIME_GATE_MILLISECONDS) {
                Log.i(logTag, "Time gate passed (Callback Time: $callbackTimeMillis >= Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS). Processing HR: $hrIntValue (Target: $targetHeartRate)")

                if (hrIntValue <= targetHeartRate) {
                    consecutiveCount++
                    Log.i(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate). Consecutive count incremented to: $consecutiveCount")
                } else {
                    if (consecutiveCount > 0) {
                        Log.i(logTag, "HR above target ($hrIntValue > $targetHeartRate). Resetting consecutive count from $consecutiveCount to 0.")
                        consecutiveCount = 0
                    } else {
                        Log.d(logTag, "HR above target ($hrIntValue > $targetHeartRate). Count already 0.")
                    }
                }

                preferencesRepository.updateConsecutiveCount(consecutiveCount)
                preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                preferencesRepository.updateLastDisplayedHr(hrIntValue)

                if (consecutiveCount >= CONSECUTIVE_COUNT_THRESHOLD) {
                    Log.w(logTag, "GOBBLE TIME DETECTED! Consecutive count reached $consecutiveCount.")
                    preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                    // TODO: Trigger alert/notification (Batch 4 task)
                    safelyUnregisterListener()
                    Log.i(logTag, "Listener unregistered due to reaching GOBBLE_TIME state.")
                }

            } else {
                Log.d(logTag, "Skipping HR processing (HR: $hrIntValue): Time gate NOT passed. (Callback Time: $callbackTimeMillis, Last Processed: $lastProcessedTimestamp, Gate: $TIME_GATE_MILLISECONDS)")
            }

        } catch (e: Exception) {
            Log.e(logTag, "Error during core HR processing logic", e)
        }
    }


    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        if (!hasBodySensorsPermission()) {
            Log.w(logTag, "Registration skipped: BODY_SENSORS permission not granted.")
            return
        }
        if (isListenerRegistered) {
            Log.d(logTag, "Registration skipped: Listener already registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "Registration failed: Client not initialized.")
            stopSelf()
            return
        }

        // Access preferencesRepository directly
        val currentState = try {
            preferencesRepository.appStateFlow.first()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to read app state immediately before registering listener", e)
            return
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
            isListenerRegistered = true
            Log.i(logTag, "Passive listener registration submitted successfully.")
        } catch (e: SecurityException) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed: SecurityException (Permissions revoked?). Stopping service.", e)
            stopSelf()
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Listener registration failed with exception.", e)
        }
    }

    private suspend fun safelyUnregisterListener() {
        if (!isListenerRegistered) {
            Log.d(logTag, "Unregistration skipped: Listener not currently registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "Unregistration warning: Client not initialized. Setting flag false.")
            isListenerRegistered = false
            return
        }

        Log.i(logTag, "Attempting to unregister passive listener...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isListenerRegistered = false
            Log.i(logTag, "Passive listener unregistration successful.")
        } catch (e: CancellationException) {
            isListenerRegistered = false
            Log.i(logTag, "Listener unregistration cancelled (coroutine scope likely ending).", e)
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Listener unregistration failed. Setting flag to false.", e)
        }
    }

    // --- Notification Management ---
    // (No changes needed in createNotificationChannel or createNotification)
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
            .setContentText("Monitoring heart rate...") // TODO: Update content text based on AppState?
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
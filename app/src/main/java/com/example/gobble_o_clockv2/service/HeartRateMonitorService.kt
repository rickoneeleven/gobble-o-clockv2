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
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    private val logTag: String = HeartRateMonitorService::class.java.simpleName

    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var isListenerRegistered = false

    // Dependency: Must be injected/provided externally
    lateinit var preferencesRepository: PreferencesRepository
        private set

    // --- Constants ---
    companion object {
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
        private const val TIME_GATE_MILLISECONDS = 55000L // Approx 1 minute (55 seconds)
        private const val CONSECUTIVE_COUNT_THRESHOLD = 5
    }

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            val callbackTimeMillis = System.currentTimeMillis()
            Log.d(logTag, "Callback: Received DataPointContainer at $callbackTimeMillis")
            lifecycleScope.launch {
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
        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "PassiveMonitoringClient initialized.")
        } catch (e: Exception) {
            Log.e(logTag, "Error initializing PassiveMonitoringClient during onCreate", e)
            stopSelf()
            return
        }
        createNotificationChannel()
        Log.i(logTag, "Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN_ACTION"
        Log.i(logTag, "Service starting command. Action: $action, StartId: $startId")

        if (!::preferencesRepository.isInitialized) {
            Log.e(logTag, "CRITICAL: PreferencesRepository not provided. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(logTag, "PreferencesRepository dependency confirmed.")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "Cannot start: BODY_SENSORS permission not granted. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(logTag, "BODY_SENSORS permission confirmed.")

        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "Cannot start: PassiveMonitoringClient not initialized. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(logTag, "Starting foreground service and attempting listener registration.")
        startForeground(NOTIFICATION_ID, createNotification())

        lifecycleScope.launch {
            val currentState = try {
                preferencesRepository.appStateFlow.first()
            } catch (e: Exception) {
                Log.e(logTag, "Failed to read app state before starting service command processing", e)
                stopSelf()
                return@launch
            }

            if (currentState == AppState.MONITORING) {
                registerPassiveListener()
            } else {
                Log.i(logTag, "Service started in GOBBLE_TIME state. Listener not registered.")
            }

            // TODO: Implement continuous observation of appStateFlow for external resets
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
            val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
            val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
            var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
            val currentAppState = preferencesRepository.appStateFlow.first()

            if (currentAppState != AppState.MONITORING) {
                Log.d(logTag, "Skipping HR processing: AppState is $currentAppState.")
                return
            }

            if (callbackTimeMillis >= lastProcessedTimestamp + TIME_GATE_MILLISECONDS) {
                Log.i(logTag, "Time gate passed. Processing HR: $hrIntValue (Target: $targetHeartRate)")

                if (hrIntValue <= targetHeartRate) {
                    consecutiveCount++
                    Log.i(logTag, "HR below/equal target. Consecutive count incremented to: $consecutiveCount")
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
                }

            } else {
                Log.d(logTag, "Skipping HR processing (HR: $hrIntValue): Time gate not passed. (CallbackTime: $callbackTimeMillis, LastProcessed: $lastProcessedTimestamp)")
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

        val currentState = try {
            preferencesRepository.appStateFlow.first()
        } catch (e: Exception) {
            Log.e(logTag, "Failed to read app state before registering listener", e)
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
            // Corrected logic: Directly call the method, no await, no future variable.
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback)

            // Assume registration submitted successfully if no exception was thrown.
            isListenerRegistered = true
            Log.i(logTag, "Passive listener registration submitted successfully.")
        } catch (e: CancellationException) {
            // This might occur if the calling coroutine scope is cancelled during the call
            isListenerRegistered = false
            Log.i(logTag, "Listener registration potentially cancelled (coroutine scope).", e)
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
            // clearPassiveListenerCallbackAsync *does* return ListenableFuture<Void>, so await is correct here.
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await<Void>()
            isListenerRegistered = false
            Log.i(logTag, "Passive listener unregistration successful.")
        } catch (e: CancellationException) {
            isListenerRegistered = false
            Log.i(logTag, "Listener unregistration cancelled.", e)
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Listener unregistration failed. Setting flag to false.", e)
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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
        val pendingIntentFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
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
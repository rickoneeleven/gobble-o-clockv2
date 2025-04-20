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
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.time.Instant // Needed for origin time methods

// App Specific Imports
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.PreferencesRepository // TODO: Inject this
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    private val logTag: String = HeartRateMonitorService::class.java.simpleName

    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var isListenerRegistered = false

    // TODO: Inject PreferencesRepository using dependency injection
    // Initialize temporarily in onCreate to satisfy compiler until DI is set up
    private lateinit var preferencesRepository: PreferencesRepository

    // --- Health Services Callback Implementation ---
    private val passiveListenerCallback = object : PassiveListenerCallback {
        override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
            Log.d(logTag, "Callback: Received DataPointContainer")
            lifecycleScope.launch {
                processDataPoints(dataPoints)
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

    // --- Static Constants ---
    companion object {
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
        private const val LOG_PREFIX = "[HeartRateMonitorService]"
    }

    // --- Lifecycle Methods ---
    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "$LOG_PREFIX Creating...")
        // Temporary initialization until DI is implemented
        preferencesRepository = PreferencesRepository(applicationContext)

        try {
            passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
            Log.i(logTag, "$LOG_PREFIX PassiveMonitoringClient initialized.")
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(logTag, "$LOG_PREFIX Error during service onCreate initialization", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: "UNKNOWN"
        Log.i(logTag, "$LOG_PREFIX Starting command. Action: $action, StartId: $startId")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "$LOG_PREFIX Cannot start: BODY_SENSORS permission not granted. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "$LOG_PREFIX Cannot start: PassiveMonitoringClient not initialized. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }

        // Check if preferencesRepository was initialized (should be by now)
        if (!::preferencesRepository.isInitialized) {
            Log.e(logTag, "$LOG_PREFIX Cannot start: PreferencesRepository not initialized. Stopping.")
            stopSelf()
            return START_NOT_STICKY
        }


        Log.i(logTag, "$LOG_PREFIX Starting foreground service and registering listener.")
        startForeground(NOTIFICATION_ID, createNotification())

        lifecycleScope.launch {
            registerPassiveListener()
            // TODO: Start collecting appState from PreferencesRepository here
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(logTag, "$LOG_PREFIX Destroying...")
        lifecycleScope.launch {
            safelyUnregisterListener()
        }
        super.onDestroy()
        Log.i(logTag, "$LOG_PREFIX Service destroyed.")
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        Log.d(logTag, "$LOG_PREFIX Binding attempted (not supported).")
        return null
    }

    // --- Permission Handling ---
    private fun hasBodySensorsPermission(): Boolean {
        val permissionState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        )
        val granted = permissionState == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(logTag, "$LOG_PREFIX BODY_SENSORS permission check: Not granted.")
        }
        return granted
    }

    // --- Data Processing ---
    private suspend fun processDataPoints(container: DataPointContainer) {
        val hrDataPoints: List<DataPoint<Double>> = container.getData(DataType.HEART_RATE_BPM)
        if (hrDataPoints.isEmpty()) {
            Log.d(logTag, "$LOG_PREFIX No HEART_RATE_BPM data points in this container.")
            return
        }

        // Find latest point using getEndOriginTime()
        val latestDataPoint = hrDataPoints.maxByOrNull {
            if (it is SampleDataPoint<Double>) {
                try { it.getEndOriginTime() } catch (e: Exception) { Instant.MIN } // Safely get time
            } else {
                Instant.MIN
            }
        }

        if (latestDataPoint == null || latestDataPoint !is SampleDataPoint<Double>) {
            Log.w(logTag, "$LOG_PREFIX Could not find a valid SampleDataPoint for HR in the container.")
            return
        }

        val hr: Double = latestDataPoint.value
        try {
            // Use getEndOriginTime() to get the timestamp directly
            val timestampInstant: Instant = latestDataPoint.getEndOriginTime() // <--- KEY CHANGE
            val timestampEpochMillis: Long = timestampInstant.toEpochMilli()

            Log.d(logTag, "$LOG_PREFIX Processing HR: $hr BPM, Timestamp (EpochMillis): $timestampEpochMillis")

            // --- TODO: Implement Batch 1 - Core Logic ---
            // 1. Inject PreferencesRepository properly
            // 2. Read targetHeartRate & lastProcessedTimestamp
            // 3. Check time gate: if (timestampEpochMillis >= lastProcessedTimestamp + 55000L)
            // 4. Implement state updates, saving, Gobble Time trigger...

        } catch (e: NoSuchMethodError) {
            // If getEndOriginTime doesn't exist, we need to inspect SampleDataPoint source
            Log.e(logTag, "$LOG_PREFIX Timestamp method getEndOriginTime() not found on SampleDataPoint.", e)
        } catch (e: Exception) {
            Log.e(logTag, "$LOG_PREFIX Error processing HR SampleDataPoint.", e)
        }
    }


    // --- Health Services Listener Management ---
    private suspend fun registerPassiveListener() {
        if (!hasBodySensorsPermission()) {
            Log.w(logTag, "$LOG_PREFIX Registration skipped: BODY_SENSORS permission not granted.")
            return
        }
        if (isListenerRegistered) {
            Log.d(logTag, "$LOG_PREFIX Registration skipped: Listener already registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.e(logTag, "$LOG_PREFIX Registration failed: Client not initialized.")
            return
        }

        Log.i(logTag, "$LOG_PREFIX Attempting to register passive listener for HEART_RATE_BPM...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback).await()
            isListenerRegistered = true
            Log.i(logTag, "$LOG_PREFIX Passive listener registration successful.")
        } catch (e: CancellationException) {
            isListenerRegistered = false
            Log.i(logTag, "$LOG_PREFIX Listener registration cancelled.", e)
        } catch (e: SecurityException) {
            isListenerRegistered = false
            Log.e(logTag, "$LOG_PREFIX Listener registration failed due to SecurityException (permissions?). Stopping service.", e)
            stopSelf()
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "$LOG_PREFIX Listener registration failed.", e)
        }
    }

    private suspend fun safelyUnregisterListener() {
        if (!isListenerRegistered) {
            Log.d(logTag, "$LOG_PREFIX Unregistration skipped: Listener not registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "$LOG_PREFIX Unregistration skipped: Client not initialized.")
            isListenerRegistered = false
            return
        }

        Log.i(logTag, "$LOG_PREFIX Attempting to unregister passive listener...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await()
            isListenerRegistered = false
            Log.i(logTag, "$LOG_PREFIX Passive listener unregistration successful.")
        } catch (e: CancellationException) {
            isListenerRegistered = false
            Log.i(logTag, "$LOG_PREFIX Listener unregistration cancelled.", e)
        } catch (e: Exception) {
            Log.e(logTag, "$LOG_PREFIX Listener unregistration failed. It might remain active.", e)
            isListenerRegistered = false
        }
    }

    // --- Notification Management ---
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background heart rate monitoring status"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.i(logTag, "$LOG_PREFIX Notification channel verified.")
    }

    private fun createNotification(): Notification {
        val launchActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchActivityIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Monitoring heart rate...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(activityPendingIntent)
            .build()

        Log.d(logTag, "$LOG_PREFIX Foreground service notification constructed.")
        return notification
    }
}
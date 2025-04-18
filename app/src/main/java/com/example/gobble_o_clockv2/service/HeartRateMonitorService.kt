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

// Health Services Imports (Explicitly listed)
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.PassiveMonitoringData // Verify this exact path

// Coroutines Imports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await // Verify guava import path
import kotlinx.coroutines.launch

// App Specific Imports
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.presentation.MainActivity


class HeartRateMonitorService : LifecycleService() {

    private val logTag: String = this::class.java.simpleName

    private lateinit var passiveMonitoringClient: PassiveMonitoringClient
    private var isListenerRegistered = false

    // --- Health Services Callback ---
    private fun createPassiveListenerCallback(): PassiveListenerCallback {
        return object : PassiveListenerCallback {
            override fun onNewDataPoints(data: PassiveMonitoringData) { // Uses explicit import
                Log.d(logTag, "Callback: onNewDataPoints received data")
                lifecycleScope.launch {
                    processDataPoints(data)
                }
            }

            override fun onPermissionLost() {
                Log.e(logTag, "Callback: BODY_SENSORS permission lost. Stopping service.")
                lifecycleScope.launch {
                    safelyUnregisterListener()
                }
                stopSelf()
            }

            override fun onError(error: Throwable) {
                Log.e(logTag, "Callback: onError - ${error.localizedMessage}", error)
            }
        }
    }

    private val passiveListenerCallback: PassiveListenerCallback by lazy {
        createPassiveListenerCallback()
    }


    companion object {
        private const val NOTIFICATION_ID = 101
        private const val NOTIFICATION_CHANNEL_ID = "gobble_o_clock_channel_01"
        private const val NOTIFICATION_CHANNEL_NAME = "Heart Rate Monitoring"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(logTag, "Service onCreate called.")
        passiveMonitoringClient = HealthServices.getClient(this).passiveMonitoringClient
        Log.i(logTag, "PassiveMonitoringClient initialized.")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(logTag, "Service onStartCommand called. Intent action: ${intent?.action}")

        if (!hasBodySensorsPermission()) {
            Log.e(logTag, "Service cannot start: BODY_SENSORS permission not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(logTag, "BODY_SENSORS permission granted. Starting foreground service.")
        startForeground(NOTIFICATION_ID, createNotification())

        lifecycleScope.launch {
            Log.d(logTag, "Service monitoring coroutine started.")
            registerPassiveListener()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(logTag, "Service onDestroy called.")
        lifecycleScope.launch {
            safelyUnregisterListener() // Fixed typo in original comment reference
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        Log.d(logTag, "Service onBind called (returning null).")
        return null
    }

    private fun hasBodySensorsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Uses explicit import for PassiveMonitoringData parameter
    private suspend fun processDataPoints(data: PassiveMonitoringData) {
        data.dataPoints.getData(DataType.HEART_RATE_BPM).forEach { dataPoint ->
            val hr = dataPoint.value
            val timestamp = dataPoint.endInstant
            Log.d(logTag, "Received HR: $hr BPM at end time: $timestamp")
            // TODO Phase 3 logic
        }
    }

    private suspend fun registerPassiveListener() {
        if (!hasBodySensorsPermission()) {
            Log.w(logTag, "Register: Attempted without BODY_SENSORS permission.")
            return
        }
        if (isListenerRegistered) {
            Log.d(logTag, "Register: Listener already registered.")
            return
        }

        Log.i(logTag, "Register: Attempting registration...")
        val passiveListenerConfig = PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            .build()

        try {
            passiveMonitoringClient.setPassiveListenerCallback(passiveListenerConfig, passiveListenerCallback).await() // Uses explicit await import
            isListenerRegistered = true
            Log.i(logTag, "Register: Success.")
        } catch (e: CancellationException) {
            Log.i(logTag, "Register: Coroutine cancelled during registration.", e)
            isListenerRegistered = false
        } catch (e: Exception) {
            isListenerRegistered = false
            Log.e(logTag, "Register: Failed.", e)
        }
    }

    private suspend fun safelyUnregisterListener() {
        if (!isListenerRegistered) {
            Log.d(logTag, "Unregister: Listener not registered.")
            return
        }
        if (!::passiveMonitoringClient.isInitialized) {
            Log.w(logTag, "Unregister: Client not initialized.")
            isListenerRegistered = false
            return
        }

        Log.i(logTag, "Unregister: Attempting...")
        try {
            passiveMonitoringClient.clearPassiveListenerCallbackAsync().await() // Uses explicit await import
            isListenerRegistered = false
            Log.i(logTag, "Unregister: Success.")
        } catch (e: CancellationException) {
            Log.i(logTag, "Unregister: Coroutine cancelled.", e)
            isListenerRegistered = false
        } catch (e: Exception) {
            Log.e(logTag, "Unregister: Failed.", e)
            isListenerRegistered = false
        }
    }

    // --- Notification Methods ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Gobble O'Clock heart rate monitoring status"
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.i(logTag, "Notification channel created.")
    }

    private fun createNotification(): Notification {
        val launchActivityIntent = Intent(this, MainActivity::class.java)
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
            .setContentIntent(activityPendingIntent)
            .build()

        Log.d(logTag, "Foreground notification created.")
        return notification
    }
}
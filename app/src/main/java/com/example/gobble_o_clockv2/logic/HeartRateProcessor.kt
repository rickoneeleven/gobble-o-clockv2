package com.example.gobble_o_clockv2.logic

import android.util.Log
// --- Health Services Imports ---
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.DataPoint // <<< ADD THIS IMPORT BACK

// --- App Specific Imports ---
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository

// --- Coroutines Imports ---
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock // Import withLock

/**
 * Encapsulates the core logic for processing heart rate data points.
 * Reads necessary state from PreferencesRepository, applies processing rules,
 * and updates state back to the repository. Includes Mutex to prevent race conditions.
 */
class HeartRateProcessor(private val preferencesRepository: PreferencesRepository) {

    // Use companion object for Log Tag for consistency
    companion object {
        private val logTag: String = HeartRateProcessor::class.java.simpleName
        private const val TIME_GATE_MILLISECONDS = 55000L // Approx 55 seconds gate
        // Threshold set to 2 as requested
        private const val CONSECUTIVE_COUNT_THRESHOLD = 2
    }

    // Mutex to protect access to shared state logic (reading/checking/writing timestamp/count)
    private val processingMutex = Mutex()

    /**
     * Processes a container of data points, focusing on the latest heart rate reading.
     * Applies time-gating, compares against the target HR, updates consecutive counts,
     * and triggers state changes based on the defined thresholds. Uses a Mutex to prevent race conditions.
     *
     * @param container The DataPointContainer received from Health Services.
     * @param callbackTimeMillis The system time when the callback was invoked.
     */
    suspend fun processHeartRateData(container: DataPointContainer, callbackTimeMillis: Long) {
        // Use the fully qualified name now that the import is added
        val hrDataPoints: List<DataPoint<Double>> = container.getData(DataType.HEART_RATE_BPM)
        if (hrDataPoints.isEmpty()) {
            return
        }

        // Get the most recent HR data point
        val latestDataPoint: SampleDataPoint<Double>? = hrDataPoints
            .filterIsInstance<SampleDataPoint<Double>>()
            .lastOrNull()

        if (latestDataPoint == null) {
            Log.w(logTag, "Could not find a SampleDataPoint for HR in the container.")
            return
        }

        val hrValue: Double = latestDataPoint.value
        val hrIntValue: Int = hrValue.toInt()
        // Log outside mutex for immediate feedback on received value
        Log.d(logTag,"HR Received: $hrIntValue, CallbackTime: $callbackTimeMillis")

        // --- Update Display HR (Can be done outside Mutex - low contention) ---
        // Allows UI to update even if processing is skipped/delayed by mutex
        try {
            preferencesRepository.updateLastDisplayedHr(hrIntValue)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to update LastDisplayedHr (non-critical)", e)
        }


        // --- Core Processing Logic within Mutex ---
        processingMutex.withLock {
            Log.d(logTag, "Mutex acquired for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            try {
                // Fetch current preferences required for processing logic *inside the lock*
                val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
                val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
                var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
                val currentAppState = preferencesRepository.appStateFlow.first()

                Log.d(logTag,"State inside lock: AppState=$currentAppState, LastProcessed=$lastProcessedTimestamp, Count=$consecutiveCount, Target=$targetHeartRate")

                // --- Gate 1: Check AppState ---
                if (currentAppState != AppState.MONITORING) {
                    Log.i(logTag, "Skipping core processing: AppState is $currentAppState (not MONITORING).")
                    return@withLock // Exit the withLock block
                }

                // --- Gate 2: Check Time Interval ---
                val timeGatePassed = callbackTimeMillis >= lastProcessedTimestamp + TIME_GATE_MILLISECONDS
                if (timeGatePassed) {
                    Log.i(logTag, "TIME GATE PASSED (Callback: $callbackTimeMillis >= Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS). Processing HR: $hrIntValue (Target: $targetHeartRate)")

                    // --- Core Logic: Compare HR and Update Count ---
                    if (hrIntValue <= targetHeartRate) {
                        consecutiveCount++
                        Log.i(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate). CONSECUTIVE COUNT INCREMENTED TO: $consecutiveCount")
                    } else {
                        if (consecutiveCount > 0) {
                            Log.i(logTag, "HR above target ($hrIntValue > $targetHeartRate). RESETTING CONSECUTIVE COUNT from $consecutiveCount to 0.")
                            consecutiveCount = 0
                        } else {
                            // Log needed if already 0? Maybe debug level.
                            Log.d(logTag, "HR above target ($hrIntValue > $targetHeartRate), consecutive count remains 0.")
                        }
                    }

                    // --- Persist Changes (only if time gate passed) ---
                    preferencesRepository.updateConsecutiveCount(consecutiveCount)
                    // *** CRITICAL: Update lastProcessedTimestamp ONLY when processing occurs ***
                    preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                    Log.i(logTag, "Persisted: Count=$consecutiveCount, LastProcessed=$callbackTimeMillis")


                    // --- Check for Gobble Time Condition ---
                    // Using the updated constant CONSECUTIVE_COUNT_THRESHOLD
                    if (consecutiveCount >= CONSECUTIVE_COUNT_THRESHOLD) {
                        Log.w(logTag, "GOBBLE TIME DETECTED! Consecutive count ($consecutiveCount) reached threshold ($CONSECUTIVE_COUNT_THRESHOLD).")
                        // Update state last, observer in service will handle listener unregistration
                        preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                        // Service observer will see GOBBLE_TIME and unregister listener
                    }

                } else {
                    Log.i(logTag, "Skipping core processing (HR: $hrIntValue): TIME GATE NOT PASSED. (Callback: $callbackTimeMillis < Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS)")
                    // Do NOT update lastProcessedTimestamp if gate is not passed
                }

            } catch (e: Exception) {
                Log.e(logTag, "Error during core HR processing logic inside mutex", e)
                // Avoid crashing the service if possible, log the error.
            } finally {
                Log.d(logTag, "Mutex released for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            }
        } // End of Mutex.withLock
    }
}
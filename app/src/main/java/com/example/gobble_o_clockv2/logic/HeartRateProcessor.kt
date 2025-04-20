package com.example.gobble_o_clockv2.logic

import android.util.Log
// --- Health Services Imports ---
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import androidx.health.services.client.data.DataPoint

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
        // Time gate remains ~55 seconds
        private const val TIME_GATE_MILLISECONDS = 55000L
        // Consecutive count threshold remains 2
        private const val CONSECUTIVE_COUNT_THRESHOLD = 2
        // Minimum valid HR reading to process
        private const val MIN_VALID_HEART_RATE = 1 // Ignore 0 or less
    }

    // Mutex to protect access to shared state logic (reading/checking/writing timestamp/count)
    private val processingMutex = Mutex()

    /**
     * Processes a container of data points, focusing on the latest heart rate reading.
     * Ignores readings <= 0. Applies time-gating, compares against the target HR,
     * updates consecutive counts, and triggers state changes based on the defined thresholds.
     * Uses a Mutex to prevent race conditions for core logic.
     *
     * @param container The DataPointContainer received from Health Services.
     * @param callbackTimeMillis The system time when the callback was invoked.
     */
    suspend fun processHeartRateData(container: DataPointContainer, callbackTimeMillis: Long) {
        // Get the most recent HR SampleDataPoint
        val latestDataPoint: SampleDataPoint<Double>? = container.getData(DataType.HEART_RATE_BPM)
            .filterIsInstance<SampleDataPoint<Double>>()
            .lastOrNull()

        if (latestDataPoint == null) {
            // This might happen if data container is empty or has unexpected types
            // Log.w(logTag, "Could not find a SampleDataPoint for HR in the container.") // Less verbose if frequent
            return
        }

        val hrValue: Double = latestDataPoint.value
        val hrIntValue: Int = hrValue.toInt()

        // --- Gate 1: Ignore Invalid HR Readings (0 or less) ---
        if (hrIntValue < MIN_VALID_HEART_RATE) {
            Log.w(logTag, "IGNORING invalid HR reading: $hrIntValue (Threshold: >= $MIN_VALID_HEART_RATE). CallbackTime: $callbackTimeMillis")
            // Do NOT update display HR or proceed to core logic/mutex
            return
        }

        // Log valid HR *after* the check, before potential mutex wait
        Log.d(logTag,"VALID HR Received: $hrIntValue, CallbackTime: $callbackTimeMillis")

        // --- Update Display HR (Can be done outside Mutex - low contention) ---
        // Allows UI to update quickly even if core processing is delayed by mutex or time gate
        // This only runs for valid (hrIntValue >= MIN_VALID_HEART_RATE) readings
        try {
            preferencesRepository.updateLastDisplayedHr(hrIntValue)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to update LastDisplayedHr to $hrIntValue (non-critical)", e)
        }


        // --- Core Processing Logic within Mutex ---
        // This section only runs for valid HR readings (hrIntValue >= MIN_VALID_HEART_RATE)
        processingMutex.withLock {
            Log.d(logTag, "Mutex acquired for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            try {
                // Fetch current preferences required for processing logic *inside the lock*
                // Reading these fresh prevents race conditions with potential external updates
                val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
                val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
                var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
                val currentAppState = preferencesRepository.appStateFlow.first()

                Log.d(logTag,"State inside lock: AppState=$currentAppState, LastProcessed=$lastProcessedTimestamp, Count=$consecutiveCount, Target=$targetHeartRate")

                // --- Gate 2: Check AppState ---
                if (currentAppState != AppState.MONITORING) {
                    Log.i(logTag, "Skipping core processing: AppState is $currentAppState (not MONITORING).")
                    return@withLock // Exit the withLock block safely
                }

                // --- Gate 3: Check Time Interval ---
                val timeSinceLastProcessed = callbackTimeMillis - lastProcessedTimestamp
                val timeGatePassed = timeSinceLastProcessed >= TIME_GATE_MILLISECONDS

                if (timeGatePassed) {
                    Log.i(logTag, "TIME GATE PASSED (Callback: $callbackTimeMillis >= Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS -> Diff: $timeSinceLastProcessed ms). Processing HR: $hrIntValue (Target: $targetHeartRate)")

                    // --- Core Logic: Compare HR and Update Count ---
                    if (hrIntValue <= targetHeartRate) {
                        consecutiveCount++
                        Log.i(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate). CONSECUTIVE COUNT INCREMENTED TO: $consecutiveCount")
                    } else {
                        if (consecutiveCount > 0) {
                            Log.i(logTag, "HR above target ($hrIntValue > $targetHeartRate). RESETTING CONSECUTIVE COUNT from $consecutiveCount to 0.")
                            consecutiveCount = 0
                        } else {
                            Log.d(logTag, "HR above target ($hrIntValue > $targetHeartRate), consecutive count already 0.")
                        }
                    }

                    // --- Persist Changes (only if time gate passed and logic was run) ---
                    preferencesRepository.updateConsecutiveCount(consecutiveCount)
                    // *** CRITICAL: Update lastProcessedTimestamp ONLY when processing occurs ***
                    preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                    Log.i(logTag, "Persisted changes: Count=$consecutiveCount, LastProcessed=$callbackTimeMillis")


                    // --- Check for Gobble Time Condition ---
                    if (consecutiveCount >= CONSECUTIVE_COUNT_THRESHOLD) {
                        Log.w(logTag, "GOBBLE TIME DETECTED! Consecutive count ($consecutiveCount) reached threshold ($CONSECUTIVE_COUNT_THRESHOLD). Updating state.")
                        // Update state last. The service's observer will see this change
                        // and handle unregistering the listener.
                        preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                    }

                } else {
                    // Log why processing was skipped (Time Gate)
                    Log.i(logTag, "Skipping core processing (HR: $hrIntValue): TIME GATE NOT PASSED. (Callback: $callbackTimeMillis < Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS -> Diff: $timeSinceLastProcessed ms)")
                    // Do NOT update lastProcessedTimestamp or consecutive count if gate is not passed
                }

            } catch (e: Exception) {
                // Catch potential errors during preference reads or writes inside the lock
                Log.e(logTag, "Error during core HR processing logic inside mutex for HR $hrIntValue", e)
                // Avoid crashing the service if possible, log the error. State might be inconsistent temporarily.
            } finally {
                // Ensure mutex release is logged for debugging locks
                Log.d(logTag, "Mutex released for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            }
        } // End of Mutex.withLock
    }
}
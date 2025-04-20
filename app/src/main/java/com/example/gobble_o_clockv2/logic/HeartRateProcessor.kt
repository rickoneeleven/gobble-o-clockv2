package com.example.gobble_o_clockv2.logic

import android.util.Log
import androidx.health.services.client.data.DataPoint
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository
import kotlinx.coroutines.flow.first

/**
 * Encapsulates the core logic for processing heart rate data points.
 * Reads necessary state from PreferencesRepository, applies processing rules,
 * and updates state back to the repository.
 */
class HeartRateProcessor(private val preferencesRepository: PreferencesRepository) {

    private val logTag: String = this::class.java.simpleName

    // --- Constants ---
    // Moved from the service as they are specific to this processing logic
    companion object {
        private const val TIME_GATE_MILLISECONDS = 55000L // Approx 55 seconds gate
        private const val CONSECUTIVE_COUNT_THRESHOLD = 5
    }

    /**
     * Processes a container of data points, focusing on the latest heart rate reading.
     * Applies time-gating, compares against the target HR, updates consecutive counts,
     * and triggers state changes based on the defined thresholds.
     *
     * @param container The DataPointContainer received from Health Services.
     * @param callbackTimeMillis The system time when the callback was invoked.
     */
    suspend fun processHeartRateData(container: DataPointContainer, callbackTimeMillis: Long) {
        val hrDataPoints: List<DataPoint<Double>> = container.getData(DataType.HEART_RATE_BPM)
        if (hrDataPoints.isEmpty()) {
            Log.d(logTag, "No HEART_RATE_BPM data points in this container. Skipping.")
            return
        }

        // Get the most recent HR data point from the container
        val latestDataPoint: SampleDataPoint<Double>? = hrDataPoints
            .filterIsInstance<SampleDataPoint<Double>>()
            .lastOrNull()

        if (latestDataPoint == null) {
            Log.w(logTag, "Could not find a SampleDataPoint for HR in the container. Skipping.")
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
                // Update displayed HR even if skipping core logic for smoother UI feel
                preferencesRepository.updateLastDisplayedHr(hrIntValue)
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
                        // Log not strictly needed if count is already 0 and HR is above target
                        Log.d(logTag, "HR above target ($hrIntValue > $targetHeartRate), consecutive count remains 0.")
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
                    // The appStateFlow collector in the service will observe GOBBLE_TIME and trigger unregistration.
                    // TODO: Trigger alert/notification (Future task, handled separately)
                }

            } else {
                Log.d(logTag, "Skipping core HR processing (HR: $hrIntValue): Time gate NOT passed. (Callback Time: $callbackTimeMillis < Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS)")
                // Also update the displayed HR even if not processed for core logic, provides smoother UI
                preferencesRepository.updateLastDisplayedHr(hrIntValue)
            }

        } catch (e: Exception) {
            Log.e(logTag, "Error during core HR processing logic", e)
            // Avoid crashing the service if possible, log the error.
        }
    }
}
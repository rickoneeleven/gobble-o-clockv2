package com.example.gobble_o_clockv2.logic

import android.util.Log
// --- Health Services Imports ---
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.SampleDataPoint

// --- App Specific Imports ---
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.data.PreferencesRepository

// --- Coroutines Imports ---
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Encapsulates the core logic for processing heart rate data points.
 * Reads necessary state from PreferencesRepository, applies processing rules,
 * and updates state back to the repository. Includes Mutex to prevent race conditions.
 */
class HeartRateProcessor(private val preferencesRepository: PreferencesRepository) {

    companion object {
        private val logTag: String = HeartRateProcessor::class.java.simpleName
        private const val TIME_GATE_MILLISECONDS = 55000L // Approx 55 seconds
        private const val CONSECUTIVE_COUNT_THRESHOLD = 2
        private const val MIN_VALID_HEART_RATE = 1 // Ignore 0 or less
        private const val GRACE_PERIOD_MILLISECONDS = 30 * 60 * 1000L // 30 minutes
    }

    private val processingMutex = Mutex()

    suspend fun processHeartRateData(container: DataPointContainer, callbackTimeMillis: Long) {
        val latestDataPoint: SampleDataPoint<Double>? = container.getData(DataType.HEART_RATE_BPM).lastOrNull()

        if (latestDataPoint == null) {
            Log.w(logTag, "No SampleDataPoint for HEART_RATE_BPM found in the container.")
            return
        }

        val hrValue: Double = latestDataPoint.value
        val hrIntValue: Int = hrValue.toInt()

        if (hrIntValue < MIN_VALID_HEART_RATE) {
            Log.w(logTag, "IGNORING invalid HR reading: $hrIntValue (Threshold: >= $MIN_VALID_HEART_RATE). CallbackTime: $callbackTimeMillis")
            return
        }

        Log.d(logTag,"VALID HR Received: $hrIntValue, CallbackTime: $callbackTimeMillis")

        try {
            preferencesRepository.updateLastDisplayedHr(hrIntValue)
        } catch (e: Exception) {
            Log.w(logTag, "Failed to update LastDisplayedHr to $hrIntValue (non-critical)", e)
        }

        processingMutex.withLock {
            Log.d(logTag, "Mutex acquired for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            try {
                val targetHeartRate = preferencesRepository.targetHeartRateFlow.first()
                val lastProcessedTimestamp = preferencesRepository.lastProcessedTimestampFlow.first()
                var consecutiveCount = preferencesRepository.consecutiveCountFlow.first()
                val currentAppState = preferencesRepository.appStateFlow.first()
                val monitoringStartTime = preferencesRepository.monitoringStartTimeFlow.first()

                Log.d(logTag,"State inside lock: AppState=$currentAppState, LastProcessed=$lastProcessedTimestamp, Count=$consecutiveCount, Target=$targetHeartRate, MonitoringStart=$monitoringStartTime")

                if (currentAppState != AppState.MONITORING) {
                    Log.i(logTag, "Skipping core processing: AppState is $currentAppState (not MONITORING).")
                    return@withLock
                }

                val timeSinceMonitoringStart = if (monitoringStartTime > 0L) callbackTimeMillis - monitoringStartTime else -1L
                val isGracePeriodActive = monitoringStartTime > 0L && timeSinceMonitoringStart >= 0L && timeSinceMonitoringStart < GRACE_PERIOD_MILLISECONDS
                val gracePeriodRemainingMs = if (isGracePeriodActive) GRACE_PERIOD_MILLISECONDS - timeSinceMonitoringStart else 0L


                val timeSinceLastProcessed = callbackTimeMillis - lastProcessedTimestamp
                val timeGatePassed = timeSinceLastProcessed >= TIME_GATE_MILLISECONDS

                if (timeGatePassed) {
                    Log.i(logTag, "TIME GATE PASSED (Callback: $callbackTimeMillis >= Last Processed: $lastProcessedTimestamp + Gate: $TIME_GATE_MILLISECONDS -> Diff: $timeSinceLastProcessed ms). Processing HR: $hrIntValue (Target: $targetHeartRate)")

                    val gracePeriodEndTime = if (monitoringStartTime > 0L) monitoringStartTime + GRACE_PERIOD_MILLISECONDS else 0L
                    if (monitoringStartTime > 0L &&
                        lastProcessedTimestamp < gracePeriodEndTime &&
                        callbackTimeMillis >= gracePeriodEndTime &&
                        !isGracePeriodActive) {
                        Log.i(logTag, "GRACE PERIOD TRANSITION: ENDED. Normal HR processing resumes. MonitoringStart=$monitoringStartTime, GraceEndEstimate=$gracePeriodEndTime, LastProcessed=$lastProcessedTimestamp, CurrentTime=$callbackTimeMillis")
                    }

                    if (isGracePeriodActive) {
                        Log.i(logTag, "GRACE PERIOD ACTIVE. HR: $hrIntValue. Time since monitoring start: $timeSinceMonitoringStart ms. Remaining: $gracePeriodRemainingMs ms. Low HRs will not increment count.")
                        if (hrIntValue <= targetHeartRate) {
                            if (consecutiveCount > 0) {
                                Log.i(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate) during GRACE PERIOD. RESETTING CONSECUTIVE COUNT from $consecutiveCount to 0.")
                                consecutiveCount = 0
                                preferencesRepository.updateConsecutiveCount(consecutiveCount)
                            } else {
                                Log.d(logTag, "HR below/equal target ($hrIntValue <= $targetHeartRate) during GRACE PERIOD, count already 0.")
                            }
                        } else {
                            if (consecutiveCount > 0) {
                                Log.i(logTag, "HR above target ($hrIntValue > $targetHeartRate) during GRACE PERIOD. RESETTING CONSECUTIVE COUNT from $consecutiveCount to 0.")
                                consecutiveCount = 0
                                preferencesRepository.updateConsecutiveCount(consecutiveCount)
                            } else {
                                Log.d(logTag, "HR above target ($hrIntValue > $targetHeartRate) during GRACE PERIOD, count already 0.")
                            }
                        }
                        preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                        Log.i(logTag, "Persisted LastProcessed=$callbackTimeMillis during grace period. Count remains $consecutiveCount.")
                    } else {
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
                        preferencesRepository.updateConsecutiveCount(consecutiveCount)
                        preferencesRepository.updateLastProcessedTimestamp(callbackTimeMillis)
                        Log.i(logTag, "Persisted changes (post-grace or no grace): Count=$consecutiveCount, LastProcessed=$callbackTimeMillis")

                        if (consecutiveCount >= CONSECUTIVE_COUNT_THRESHOLD) {
                            // Corrected typo in the constant name in the log string
                            Log.w(logTag, "GOBBLE TIME DETECTED (HR)! Consecutive count ($consecutiveCount) reached threshold ($CONSECUTIVE_COUNT_THRESHOLD). Updating state.")
                            preferencesRepository.updateAppState(AppState.GOBBLE_TIME)
                        }
                    }
                } else {
                    Log.i(logTag, "Skipping core processing (HR: $hrIntValue): TIME GATE NOT PASSED. (Diff: $timeSinceLastProcessed ms)")
                }

            } catch (e: Exception) {
                Log.e(logTag, "Error during core HR processing logic inside mutex for HR $hrIntValue", e)
            } finally {
                Log.d(logTag, "Mutex released for HR: $hrIntValue, CallbackTime: $callbackTimeMillis")
            }
        }
    }
}
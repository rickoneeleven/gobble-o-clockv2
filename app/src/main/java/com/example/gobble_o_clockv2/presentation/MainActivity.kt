package com.example.gobble_o_clockv2.presentation

// --- Android Core & SDK Imports ---
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

// --- Activity & ViewModel Imports ---
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

// --- Compose UI & Foundation Imports (Core) ---
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- AndroidX Core & Lifecycle Imports ---
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// --- Wear Compose Material Components ---
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog

// --- Wear Tooling Imports ---
import androidx.wear.tooling.preview.devices.WearDevices

// --- App Specific Imports ---
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService

private const val LOG_TAG_ACTIVITY = "MainActivity" // Standardized log tag
private const val BODY_SENSORS_PERMISSION = Manifest.permission.BODY_SENSORS
private val POST_NOTIFICATIONS_PERMISSION_STRING = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.POST_NOTIFICATIONS
} else { "" } // Remains empty string if not applicable

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val requiresNotificationPermission = POST_NOTIFICATIONS_PERMISSION_STRING.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(LOG_TAG_ACTIVITY, "[LifeCycle] onCreate - Start. Requires Notification Perm: $requiresNotificationPermission")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(LOG_TAG_ACTIVITY, "[LifeCycle] onCreate - Splash screen installed, super.onCreate called.")

        setContent {
            Log.d(LOG_TAG_ACTIVITY, "[UI] setContent - Composable hierarchy building.")
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var permissionRequestInProgress by rememberSaveable { mutableStateOf<String?>(null) }
            Log.d(LOG_TAG_ACTIVITY, "[UI] Initial UI State: AppState=${uiState.appState}, SensorsGranted=${uiState.isBodySensorsPermissionGranted}, NotifsGranted=${uiState.isNotificationsPermissionGranted}, ExactAlarmGranted=${uiState.canScheduleExactAlarms}")

            val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(LOG_TAG_ACTIVITY, "[Permission] Result for $BODY_SENSORS_PERMISSION: $isGranted. Current request in progress: $permissionRequestInProgress")
                    viewModel.updateBodySensorsPermissionStatus(isGranted)
                    if (permissionRequestInProgress == BODY_SENSORS_PERMISSION) permissionRequestInProgress = null
                }
            )
            val notificationsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    if (requiresNotificationPermission) {
                        Log.i(LOG_TAG_ACTIVITY, "[Permission] Result for $POST_NOTIFICATIONS_PERMISSION_STRING: $isGranted. Current request in progress: $permissionRequestInProgress")
                        viewModel.updateNotificationsPermissionStatus(isGranted)
                    }
                    if (permissionRequestInProgress == POST_NOTIFICATIONS_PERMISSION_STRING) permissionRequestInProgress = null
                }
            )

            DisposableEffect(Unit) {
                Log.d(LOG_TAG_ACTIVITY, "[Effect] DisposableEffect (Unit): Initial permission check scheduled.")
                checkAndUpdateAllPermissions(context)
                onDispose { Log.d(LOG_TAG_ACTIVITY, "[Effect] DisposableEffect (Unit): Disposed.") }
            }

            LaunchedEffect(uiState.isBodySensorsPermissionGranted, uiState.appState) {
                Log.d(LOG_TAG_ACTIVITY, "[Effect] Service control effect triggered. SensorsGranted=${uiState.isBodySensorsPermissionGranted}, AppState=${uiState.appState}")
                if (uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(LOG_TAG_ACTIVITY, "[Service] Conditions met to start service (Sensors: ${uiState.isBodySensorsPermissionGranted}, AppState: ${uiState.appState}).")
                    startHeartRateService(context)
                } else {
                    logServiceStartConditionNotMet(uiState)
                }
            }

            WearApp(
                uiState = uiState,
                requiresNotificationPermission = requiresNotificationPermission,
                onRequestPermission = { permission ->
                    if (permissionRequestInProgress == null && permission.isNotEmpty()) {
                        Log.i(LOG_TAG_ACTIVITY, "[Action] Requesting permission: $permission")
                        permissionRequestInProgress = permission
                        when(permission) {
                            BODY_SENSORS_PERMISSION -> {
                                Log.d(LOG_TAG_ACTIVITY, "[Action] Launching Body Sensors permission request.")
                                bodySensorsPermissionLauncher.launch(permission)
                            }
                            POST_NOTIFICATIONS_PERMISSION_STRING -> {
                                Log.d(LOG_TAG_ACTIVITY, "[Action] Launching Notifications permission request.")
                                notificationsPermissionLauncher.launch(permission)
                            }
                            else -> {
                                Log.w(LOG_TAG_ACTIVITY, "[Action] Unknown permission requested: $permission. Resetting request state.")
                                permissionRequestInProgress = null
                            }
                        }
                    } else if (permission.isEmpty()) {
                        Log.w(LOG_TAG_ACTIVITY, "[Action] Attempted to request an empty permission string.")
                    } else {
                        Log.d(LOG_TAG_ACTIVITY, "[Action] Permission request for '$permissionRequestInProgress' already in progress. Ignoring new request for '$permission'.")
                    }
                },
                onResetMonitoring = {
                    Log.i(LOG_TAG_ACTIVITY, "[Action] 'Reset Monitoring' requested from UI.")
                    viewModel.resetMonitoring()
                },
                onUpdateTargetHeartRate = { newRate ->
                    Log.i(LOG_TAG_ACTIVITY, "[Action] 'Update Target HR' to $newRate requested from UI.")
                    viewModel.updateTargetHeartRate(newRate)
                },
                onUpdateTargetHours = { newHours ->
                    Log.i(LOG_TAG_ACTIVITY, "[Action] 'Update Target Hours' to $newHours requested from UI.")
                    viewModel.updateTargetHours(newHours)
                },
                onRequestExactAlarmPermission = {
                    Log.i(LOG_TAG_ACTIVITY, "[Action] 'Request Exact Alarm Permission' guidance requested from UI.")
                    handleExactAlarmPermissionRequest(context)
                },
                onStopMonitoringByUser = { // New lambda for stopping monitoring
                    Log.i(LOG_TAG_ACTIVITY, "[Action] 'Stop Monitoring by User' requested from UI.")
                    viewModel.stopMonitoringByUser()
                }
            )
            Log.d(LOG_TAG_ACTIVITY, "[UI] setContent - Composable hierarchy built.")
        }
        Log.i(LOG_TAG_ACTIVITY, "[LifeCycle] onCreate - End.")
    }

    override fun onResume() {
        super.onResume()
        Log.i(LOG_TAG_ACTIVITY, "[LifeCycle] onResume - Start. Re-checking all permissions.")
        checkAndUpdateAllPermissions(this)
        Log.i(LOG_TAG_ACTIVITY, "[LifeCycle] onResume - End.")
    }

    private fun checkAndUpdateAllPermissions(context: Context) {
        Log.d(LOG_TAG_ACTIVITY, "[Permission] Executing checkAndUpdateAllPermissions.")
        val sensorsGranted = ContextCompat.checkSelfPermission(context, BODY_SENSORS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        Log.d(LOG_TAG_ACTIVITY, "[Permission] Body Sensors permission status: $sensorsGranted")
        viewModel.updateBodySensorsPermissionStatus(sensorsGranted)

        if (requiresNotificationPermission) {
            val notificationsGranted = ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION_STRING) == PackageManager.PERMISSION_GRANTED
            Log.d(LOG_TAG_ACTIVITY, "[Permission] Notifications permission status: $notificationsGranted")
            viewModel.updateNotificationsPermissionStatus(notificationsGranted)
        } else {
            Log.d(LOG_TAG_ACTIVITY, "[Permission] Notifications permission not required by this SDK version, setting to true.")
            viewModel.updateNotificationsPermissionStatus(true) // Not required, so effectively granted
        }

        Log.d(LOG_TAG_ACTIVITY, "[Permission] Updating Exact Alarm permission status from ViewModel.")
        viewModel.updateExactAlarmPermissionStatus()
        Log.d(LOG_TAG_ACTIVITY, "[Permission] Finished checkAndUpdateAllPermissions.")
    }

    private fun startHeartRateService(context: Context) {
        val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
        Log.i(LOG_TAG_ACTIVITY, "[Service] Attempting to start foreground service: ${HeartRateMonitorService::class.java.name}")
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(LOG_TAG_ACTIVITY, "[Service] startForegroundService call successful.")
        } catch (e: SecurityException) {
            Log.e(LOG_TAG_ACTIVITY, "[Service] SecurityException: Failed to start HeartRateMonitorService. This might be due to background start restrictions on newer Android versions if permissions (e.g., POST_NOTIFICATIONS for FGS type health) are not correctly handled or if app is in a restricted state.", e)
        } catch (e: Exception) {
            Log.e(LOG_TAG_ACTIVITY, "[Service] Generic Exception: Failed to start HeartRateMonitorService.", e)
        }
    }

    private fun logServiceStartConditionNotMet(uiState: MainUiState) {
        val reason = when {
            !uiState.isBodySensorsPermissionGranted -> "Body Sensors permission not granted (Current: ${uiState.isBodySensorsPermissionGranted})"
            uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState} (Expected: MONITORING)"
            else -> "Unknown reason (Sensors: ${uiState.isBodySensorsPermissionGranted}, AppState: ${uiState.appState})"
        }
        Log.i(LOG_TAG_ACTIVITY, "[Service] Service start conditions NOT MET: $reason")
    }

    private fun handleExactAlarmPermissionRequest(context: Context) {
        Log.d(LOG_TAG_ACTIVITY, "[Permission] Entered handleExactAlarmPermissionRequest. SDK: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", context.packageName, null) // Recommended to scope to app
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                Log.i(LOG_TAG_ACTIVITY, "[Permission] Attempting to launch ACTION_REQUEST_SCHEDULE_EXACT_ALARM for package: ${context.packageName}")
                context.startActivity(intent)
                Log.i(LOG_TAG_ACTIVITY, "[Permission] Intent to ACTION_REQUEST_SCHEDULE_EXACT_ALARM settings sent.")
            } catch (e: Exception) {
                Log.e(LOG_TAG_ACTIVITY, "[Permission] Failed to start ACTION_REQUEST_SCHEDULE_EXACT_ALARM settings. Attempting fallback to App Details.", e)
                try {
                    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    Log.i(LOG_TAG_ACTIVITY, "[Permission] Attempting fallback: ACTION_APPLICATION_DETAILS_SETTINGS for package: ${context.packageName}")
                    context.startActivity(fallbackIntent)
                    Log.i(LOG_TAG_ACTIVITY, "[Permission] Fallback: Intent to ACTION_APPLICATION_DETAILS_SETTINGS sent.")
                } catch (e2: Exception) {
                    Log.e(LOG_TAG_ACTIVITY, "[Permission] Fallback to ACTION_APPLICATION_DETAILS_SETTINGS also failed.", e2)
                }
            }
        } else {
            Log.w(LOG_TAG_ACTIVITY, "[Permission] Exact Alarm Permission guidance requested on pre-SDK S device (SDK ${Build.VERSION.SDK_INT}). This UI path should ideally not be active, or intent should not be sent.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOG_TAG_ACTIVITY, "[LifeCycle] onDestroy.")
    }
}

@Composable
fun WearApp(
    uiState: MainUiState,
    requiresNotificationPermission: Boolean,
    onRequestPermission: (String) -> Unit,
    onResetMonitoring: () -> Unit,
    onUpdateTargetHeartRate: (Int) -> Unit,
    onUpdateTargetHours: (Int) -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onStopMonitoringByUser: () -> Unit // New parameter
) {
    Log.d(LOG_TAG_ACTIVITY, "[UI] WearApp Composable Invoked. AppState: ${uiState.appState}")
    Gobbleoclockv2Theme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            timeText = {
                TimeText(
                    modifier = Modifier.padding(top = 4.dp),
                    // Optional: Log time text composition if needed for specific debugging
                    // startCurvedContent = { Log.v(LOG_TAG_ACTIVITY, "[UI] TimeText: startCurvedContent") },
                    // endCurvedContent = { Log.v(LOG_TAG_ACTIVITY, "[UI] TimeText: endCurvedContent") }
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                StateDisplay(
                    uiState = uiState,
                    requiresNotificationPermission = requiresNotificationPermission,
                    onRequestPermission = onRequestPermission,
                    onResetMonitoring = onResetMonitoring,
                    onUpdateTargetHeartRate = onUpdateTargetHeartRate,
                    onUpdateTargetHours = onUpdateTargetHours,
                    onRequestExactAlarmPermission = onRequestExactAlarmPermission,
                    onStopMonitoringByUser = onStopMonitoringByUser // Pass down
                )
            }
        }
    }
}

@Composable
fun StateDisplay(
    uiState: MainUiState,
    requiresNotificationPermission: Boolean,
    onRequestPermission: (String) -> Unit,
    onResetMonitoring: () -> Unit,
    onUpdateTargetHeartRate: (Int) -> Unit,
    onUpdateTargetHours: (Int) -> Unit,
    onRequestExactAlarmPermission: () -> Unit,
    onStopMonitoringByUser: () -> Unit // New parameter
) {
    val logTag = "$LOG_TAG_ACTIVITY.StateDisplay" // More specific log tag
    Log.d(logTag, "[UI] StateDisplay Composable Invoked. AppState: ${uiState.appState}, HR: ${uiState.lastDisplayedHr}, Count: ${uiState.consecutiveCount}")
    val scrollState = rememberScrollState()
    var showTargetHrDialog by rememberSaveable { mutableStateOf(false) }
    var showTargetHoursDialogState by rememberSaveable { mutableStateOf(false) }

    if (showTargetHrDialog) {
        Log.d(logTag, "[UI] TargetHeartRateDialog will be shown. InitialValue: ${uiState.targetHeartRate}")
        TargetHeartRateDialog(
            showDialog = true, // Controlled by rememberSaveable state
            initialValue = uiState.targetHeartRate,
            onDismiss = {
                Log.d(logTag, "[Dialog] TargetHeartRateDialog dismissed.")
                showTargetHrDialog = false
            },
            onConfirm = { newValue ->
                Log.i(logTag, "[Dialog] TargetHeartRateDialog confirmed with value: $newValue")
                onUpdateTargetHeartRate(newValue)
                showTargetHrDialog = false
            }
        )
    }

    if (showTargetHoursDialogState) {
        Log.d(logTag, "[UI] TargetHoursDialog will be shown. InitialValue: ${uiState.targetHours}")
        TargetHoursDialog(
            showDialog = true, // Controlled by rememberSaveable state
            initialValue = uiState.targetHours,
            onDismiss = {
                Log.d(logTag, "[Dialog] TargetHoursDialog dismissed.")
                showTargetHoursDialogState = false
            },
            onConfirm = { newValue ->
                Log.i(logTag, "[Dialog] TargetHoursDialog confirmed with value: $newValue")
                onUpdateTargetHours(newValue)
                showTargetHoursDialogState = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp) // Main content padding
            .verticalScroll(scrollState)
            .padding(vertical = 4.dp), // Inner padding for scroll content
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp) // Consistent spacing
    ) {
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr.toString() else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        val canChangeSettings = uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING
        Log.d(logTag, "[UI] Settings Interaction: canChangeSettings=$canChangeSettings (Sensors: ${uiState.isBodySensorsPermissionGranted}, AppState: ${uiState.appState})")

        Chip(
            label = { Text("Target HR: ${uiState.targetHeartRate} bpm") },
            onClick = {
                Log.d(logTag, "[Click] Target HR Chip clicked. Enabled: $canChangeSettings")
                if (canChangeSettings) showTargetHrDialog = true
            },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        Chip(
            label = { Text("Target Hours: ${uiState.targetHours}h") },
            onClick = {
                Log.d(logTag, "[Click] Target Hours Chip clicked. Enabled: $canChangeSettings")
                if (canChangeSettings) showTargetHoursDialogState = true
            },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        // Exact Alarm Permission Chip
        val showExactAlarmChip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                uiState.targetHours > 0 &&
                !uiState.canScheduleExactAlarms &&
                uiState.appState == AppState.MONITORING
        Log.d(logTag, "[UI] Exact Alarm Chip Visibility: showExactAlarmChip=$showExactAlarmChip (SDK=${Build.VERSION.SDK_INT}, TargetHours=${uiState.targetHours}, CanSchedule=${uiState.canScheduleExactAlarms}, AppState=${uiState.appState})")
        if (showExactAlarmChip) {
            Chip(
                label = { Text("Alarm Perm Needed", textAlign = TextAlign.Center) },
                onClick = {
                    Log.d(logTag, "[Click] 'Alarm Perm Needed' Chip clicked.")
                    onRequestExactAlarmPermission()
                },
                colors = ChipDefaults.primaryChipColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth(0.8f).padding(top = 2.dp)
            )
            Text(
                text = "(Tap above to grant 'Alarms & reminders' permission for Target Hours alerts)",
                style = MaterialTheme.typography.caption2.copy(color = MaterialTheme.colors.error),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        val settingsDisabledReason = when {
            !uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING -> "(Grant Sensors to change)"
            uiState.appState == AppState.GOBBLE_TIME -> "(Reset monitor to change)"
            else -> null
        }
        settingsDisabledReason?.let { reason ->
            Log.d(logTag, "[UI] Settings disabled reason: $reason")
            Text(
                text = reason,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }

        Text(
            text = "Count: ${uiState.consecutiveCount}",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Status: ${uiState.appState.name}",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            color = if (uiState.appState == AppState.GOBBLE_TIME) MaterialTheme.colors.primary else Color.Unspecified
        )

        val showMonitoringAlertTime = uiState.appState == AppState.MONITORING && uiState.monitoringStartTime > 0L && uiState.targetHours > 0
        Log.d(logTag, "[UI] Monitoring Alert Time Visibility: showMonitoringAlertTime=$showMonitoringAlertTime (AppState=${uiState.appState}, StartTime=${uiState.monitoringStartTime}, TargetHours=${uiState.targetHours})")
        if (showMonitoringAlertTime) {
            val alertTimeMillis = uiState.monitoringStartTime + (uiState.targetHours * 60 * 60 * 1000L)
            val sdf = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) } // Remember SDF for efficiency
            val formattedAlertTime = remember(alertTimeMillis) { sdf.format(Date(alertTimeMillis)) } // Recompute only if alertTimeMillis changes
            Log.d(logTag, "[UI] Calculated Alert Time: $formattedAlertTime (raw: $alertTimeMillis)")
            Text(
                text = "Alert By: $formattedAlertTime",
                style = MaterialTheme.typography.caption1,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp)) // Spacing before action buttons/messages

        // Conditional UI for Permissions & App State Actions
        when {
            !uiState.isBodySensorsPermissionGranted -> {
                Log.d(logTag, "[UI] Branch: Body Sensors permission NOT granted.")
                PermissionRequiredContent(
                    permissionType = "Body Sensors",
                    permissionName = BODY_SENSORS_PERMISSION,
                    onRequestPermission = onRequestPermission,
                    logTagPrefix = logTag
                )
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Text(text = "Grant sensor permission to reset.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 2.dp))
                }
            }
            requiresNotificationPermission && !uiState.isNotificationsPermissionGranted -> {
                Log.d(logTag, "[UI] Branch: Notifications permission NOT granted (and required).")
                PermissionRequiredContent(
                    permissionType = "Notifications",
                    permissionName = POST_NOTIFICATIONS_PERMISSION_STRING,
                    onRequestPermission = onRequestPermission,
                    rationale = "Notifications needed for Gobble Time alerts.",
                    logTagPrefix = logTag
                )
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Chip(
                        label = { Text("Reset Monitor") },
                        onClick = { Log.d(logTag, "[Click] 'Reset Monitor' Chip clicked (NotificationsPerm section)."); onResetMonitoring() },
                        colors = ChipDefaults.chipColors()
                    )
                }
            }
            else -> { // All critical permissions granted
                Log.d(logTag, "[UI] Branch: All critical permissions granted. AppState: ${uiState.appState}")
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Chip(
                        label = { Text("Reset Monitor") },
                        onClick = { Log.d(logTag, "[Click] 'Reset Monitor' Chip clicked (GobbleTime state)."); onResetMonitoring() },
                        colors = ChipDefaults.chipColors() // Default chip colors
                    )
                } else if (uiState.appState == AppState.MONITORING) {
                    // New "Stop Monitoring" button
                    Chip(
                        label = { Text("Stop Monitoring") },
                        onClick = { Log.d(logTag, "[Click] 'Stop Monitoring' Chip clicked."); onStopMonitoringByUser() },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = MaterialTheme.colors.error), // Error color for stop
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp)) // Space between stop and status text

                    // "Monitoring active..." text only if exact alarm perm is not an issue
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || uiState.targetHours == 0 || uiState.canScheduleExactAlarms) {
                        Text(text = "Monitoring active...", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.secondary)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp)) // Bottom padding
    }
}

@Composable
private fun PermissionRequiredContent(
    permissionType: String,
    permissionName: String,
    onRequestPermission: (String) -> Unit,
    rationale: String? = null,
    logTagPrefix: String // Added for more specific logging
) {
    val logTag = "$logTagPrefix.PermissionRequired"
    Log.d(logTag, "[UI] PermissionRequiredContent for '$permissionType'. Rationale: ${rationale ?: "Default"}")
    val context = LocalContext.current
    val defaultRationale = "$permissionType permission needed for core functionality."
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = rationale ?: defaultRationale,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error
        )
        Chip(
            label = { Text("Grant $permissionType") },
            onClick = {
                Log.i(logTag, "[Click] 'Grant $permissionType' Chip clicked. Requesting: $permissionName")
                onRequestPermission(permissionName)
            },
            colors = ChipDefaults.chipColors() // Default colors for grant button
        )

        if (permissionName == BODY_SENSORS_PERMISSION) {
            Log.d(logTag, "[UI] 'Open Settings' button will be shown for Body Sensors.")
            Button(
                onClick = {
                    Log.i(logTag, "[Click] 'Open Settings' button clicked for $permissionName. Package: ${context.packageName}")
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Log.i(logTag, "[Intent] ACTION_APPLICATION_DETAILS_SETTINGS intent sent.")
                    } catch (e: Exception) {
                        Log.e(logTag, "[Intent] Failed to open app details settings for $permissionName.", e)
                    }
                },
                modifier = Modifier.padding(top = 2.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) { Text("Open Settings", style = MaterialTheme.typography.caption2) }
        }
    }
}

@Composable
fun TargetHeartRateDialog(
    showDialog: Boolean,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val logTag = "$LOG_TAG_ACTIVITY.TargetHRDialog"
    val minTargetHr = 30
    val maxTargetHr = 200
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableIntStateOf(initialValue.coerceIn(minTargetHr, maxTargetHr)) }

    LaunchedEffect(initialValue, showDialog) {
        if (showDialog) {
            val coercedInitial = initialValue.coerceIn(minTargetHr, maxTargetHr)
            Log.d(logTag, "[Effect] Dialog shown. Initial value: $initialValue, Coerced: $coercedInitial. Updating selectedValue.")
            selectedValue = coercedInitial
        }
    }

    if (showDialog) { // This check is redundant if Dialog composable handles showDialog correctly, but good for clarity
        Log.d(logTag, "[UI] Rendering TargetHeartRateDialog. Current selectedValue: $selectedValue")
        Dialog(
            showDialog = true, // The Dialog composable itself handles this
            onDismissRequest = { Log.d(logTag, "[Event] Dialog dismiss requested."); onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Alert(
                title = { Text(text = "Set Target HR", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                negativeButton = { Button(onClick = { Log.d(logTag, "[Click] Cancel button clicked."); onDismiss() }, colors = ButtonDefaults.secondaryButtonColors()) { Text("Cancel") } },
                positiveButton = { Button(onClick = { Log.i(logTag, "[Click] OK button clicked. Value: $selectedValue"); onConfirm(selectedValue) }, colors = ButtonDefaults.buttonColors()) { Text("OK") } }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { if (selectedValue > minTargetHr) { selectedValue--; Log.d(logTag, "[Click] Minus. New value: $selectedValue") } },
                            enabled = selectedValue > minTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) { Text(text = "-") }
                        Text(text = "$selectedValue bpm", style = MaterialTheme.typography.body1, textAlign = TextAlign.Center, modifier = Modifier.width(IntrinsicSize.Min))
                        Button(
                            onClick = { if (selectedValue < maxTargetHr) { selectedValue++; Log.d(logTag, "[Click] Plus. New value: $selectedValue") } },
                            enabled = selectedValue < maxTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) { Text(text = "+") }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun TargetHoursDialog(
    showDialog: Boolean,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val logTag = "$LOG_TAG_ACTIVITY.TargetHoursDialog"
    val minTargetHours = 1
    val maxTargetHours = 99
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableIntStateOf(initialValue.coerceIn(minTargetHours, maxTargetHours)) }

    LaunchedEffect(initialValue, showDialog) {
        if (showDialog) {
            val coercedInitial = initialValue.coerceIn(minTargetHours, maxTargetHours)
            Log.d(logTag, "[Effect] Dialog shown. Initial value: $initialValue, Coerced: $coercedInitial. Updating selectedValue.")
            selectedValue = coercedInitial
        }
    }

    if (showDialog) {
        Log.d(logTag, "[UI] Rendering TargetHoursDialog. Current selectedValue: $selectedValue")
        Dialog(
            showDialog = true,
            onDismissRequest = { Log.d(logTag, "[Event] Dialog dismiss requested."); onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Alert(
                title = { Text(text = "Set Target Hours", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                negativeButton = { Button(onClick = { Log.d(logTag, "[Click] Cancel button clicked."); onDismiss() }, colors = ButtonDefaults.secondaryButtonColors()) { Text("Cancel") } },
                positiveButton = { Button(onClick = { Log.i(logTag, "[Click] OK button clicked. Value: $selectedValue"); onConfirm(selectedValue) }, colors = ButtonDefaults.buttonColors()) { Text("OK") } }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { if (selectedValue > minTargetHours) { selectedValue--; Log.d(logTag, "[Click] Minus. New value: $selectedValue") } },
                            enabled = selectedValue > minTargetHours,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) { Text(text = "-") }

                        Text(
                            text = "$selectedValue h",
                            style = MaterialTheme.typography.body1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(IntrinsicSize.Min)
                        )

                        Button(
                            onClick = { if (selectedValue < maxTargetHours) { selectedValue++; Log.d(logTag, "[Click] Plus. New value: $selectedValue") } },
                            enabled = selectedValue < maxTargetHours,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) { Text(text = "+") }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

// --- Previews ---
@Composable
private fun rememberPreviewState(
    appState: AppState = AppState.MONITORING,
    consecutiveCount: Int = 0,
    lastDisplayedHr: Int = 0,
    targetHeartRate: Int = 70,
    targetHours: Int = 6,
    monitoringStartTime: Long = System.currentTimeMillis() - (2 * 60 * 60 * 1000L), // 2 hours ago
    isBodySensorsPermissionGranted: Boolean = false,
    isNotificationsPermissionGranted: Boolean = false,
    canScheduleExactAlarms: Boolean = true
): MainUiState = remember(appState, consecutiveCount, lastDisplayedHr, targetHeartRate, targetHours, monitoringStartTime, isBodySensorsPermissionGranted, isNotificationsPermissionGranted, canScheduleExactAlarms) {
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        targetHours = targetHours,
        monitoringStartTime = monitoringStartTime,
        isBodySensorsPermissionGranted = isBodySensorsPermissionGranted,
        isNotificationsPermissionGranted = isNotificationsPermissionGranted,
        canScheduleExactAlarms = canScheduleExactAlarms
    )
}

@Composable
private fun PreviewWearAppWrapper(
    uiState: MainUiState,
    requiresNotificationPermission: Boolean = false,
    logTagPrefix: String = LOG_TAG_ACTIVITY // Added for consistency
) {
    val logTag = "$logTagPrefix.PreviewWrapper"
    Log.d(logTag, "[UI_Preview] PreviewWearAppWrapper for AppState: ${uiState.appState}")
    Gobbleoclockv2Theme {
        WearApp(
            uiState = uiState,
            requiresNotificationPermission = requiresNotificationPermission,
            onRequestPermission = { perm -> Log.d(logTag, "[PreviewAction] onRequestPermission: $perm") },
            onResetMonitoring = { Log.d(logTag, "[PreviewAction] onResetMonitoring") },
            onUpdateTargetHeartRate = { hr -> Log.d(logTag, "[PreviewAction] onUpdateTargetHeartRate: $hr") },
            onUpdateTargetHours = { h -> Log.d(logTag, "[PreviewAction] onUpdateTargetHours: $h") },
            onRequestExactAlarmPermission = { Log.d(logTag, "[PreviewAction] onRequestExactAlarmPermission") },
            onStopMonitoringByUser = { Log.d(logTag, "[PreviewAction] onStopMonitoringByUser") }
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Monitoring (Stop Btn)")
@Composable
private fun PreviewMonitoringWithStopButton() {
    val state = rememberPreviewState(
        isBodySensorsPermissionGranted = true,
        isNotificationsPermissionGranted = true,
        canScheduleExactAlarms = true,
        appState = AppState.MONITORING,
        lastDisplayedHr = 72
    )
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Alarm Perm Needed")
@Composable
private fun PreviewAlarmPermNeeded() {
    val state = rememberPreviewState(
        isBodySensorsPermissionGranted = true,
        isNotificationsPermissionGranted = true,
        canScheduleExactAlarms = false,
        targetHours = 6,
        lastDisplayedHr = 72
    )
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Monitoring (All Perms)")
@Composable
private fun PreviewMonitoringAllPerms() {
    val state = rememberPreviewState(
        isBodySensorsPermissionGranted = true,
        isNotificationsPermissionGranted = true,
        canScheduleExactAlarms = true,
        targetHours = 6,
        lastDisplayedHr = 72
    )
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Monitoring with Alert Time")
@Composable private fun PreviewMonitoringWithAlertTime() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true, lastDisplayedHr = 72, targetHours = 6, canScheduleExactAlarms = true)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Target Hours Dialog Preview")
@Composable private fun PreviewTargetHoursDialog() {
    Gobbleoclockv2Theme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            var showDialog by remember { mutableStateOf(true) }
            var targetH by remember { mutableIntStateOf(5) }
            TargetHoursDialog(
                showDialog = showDialog,
                initialValue = targetH,
                onDismiss = { showDialog = false },
                onConfirm = { newValue -> targetH = newValue; showDialog = false }
            )
        }
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Sensors Needed")
@Composable private fun PreviewSensorsNeeded() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = false, lastDisplayedHr = 65)
    PreviewWearAppWrapper(uiState = state)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Gobble Time (All Granted)")
@Composable private fun PreviewGobbleTimeAllGranted() {
    val state = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true, canScheduleExactAlarms = true)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}
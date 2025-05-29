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

private const val BODY_SENSORS_PERMISSION = Manifest.permission.BODY_SENSORS
private val POST_NOTIFICATIONS_PERMISSION_STRING = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.POST_NOTIFICATIONS
} else { "" }

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity"
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val requiresNotificationPermission = POST_NOTIFICATIONS_PERMISSION_STRING.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.i(logTag, "onCreate. Requires Notification Perm: $requiresNotificationPermission")

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var permissionRequestInProgress by rememberSaveable { mutableStateOf<String?>(null) }

            val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "$BODY_SENSORS_PERMISSION result: $isGranted")
                    viewModel.updateBodySensorsPermissionStatus(isGranted)
                    permissionRequestInProgress = null
                }
            )
            val notificationsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    if (requiresNotificationPermission) {
                        Log.i(logTag, "$POST_NOTIFICATIONS_PERMISSION_STRING result: $isGranted")
                        viewModel.updateNotificationsPermissionStatus(isGranted)
                    }
                    permissionRequestInProgress = null
                }
            )

            DisposableEffect(Unit) {
                Log.d(logTag, "DisposableEffect: Checking initial permissions.")
                checkAndUpdateAllPermissions(context)
                onDispose { Log.d(logTag, "DisposableEffect for permission check disposed.") }
            }

            LaunchedEffect(uiState.isBodySensorsPermissionGranted, uiState.appState) {
                Log.d(logTag, "Service control effect: SensorsGranted=${uiState.isBodySensorsPermissionGranted}, AppState=${uiState.appState}")
                if (uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(logTag, "Conditions met to start service.")
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
                        Log.i(logTag, "Requesting permission: $permission")
                        permissionRequestInProgress = permission
                        when(permission) {
                            BODY_SENSORS_PERMISSION -> bodySensorsPermissionLauncher.launch(permission)
                            POST_NOTIFICATIONS_PERMISSION_STRING -> notificationsPermissionLauncher.launch(permission)
                            else -> {
                                Log.w(logTag, "Unknown permission requested: $permission")
                                permissionRequestInProgress = null
                            }
                        }
                    } else if (permission.isEmpty()) {
                        Log.w(logTag, "Attempted to request empty permission string.")
                    } else {
                        Log.d(logTag, "Permission request for '$permissionRequestInProgress' already in progress. Ignoring new request for '$permission'.")
                    }
                },
                onResetMonitoring = viewModel::resetMonitoring,
                onUpdateTargetHeartRate = viewModel::updateTargetHeartRate,
                onUpdateTargetHours = viewModel::updateTargetHours,
                onRequestExactAlarmPermission = {
                    Log.i(logTag, "Exact Alarm Permission guidance requested by UI.")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            // ACTION_REQUEST_SCHEDULE_EXACT_ALARM is the most direct way for API 31+
                            // For API 33+, this will show apps that can manage exact alarms.
                            // For API 31, 32, it directly takes to the special app access screen listing apps.
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                // Optionally add package if this intent supports it directly for API 33+
                                // to go to *this* app's setting.
                                // However, ACTION_REQUEST_SCHEDULE_EXACT_ALARM itself often lists apps.
                                // For broader compatibility, not setting data/package here might be better first.
                                // data = Uri.fromParts("package", context.packageName, null)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            Log.i(logTag, "Intent to ACTION_REQUEST_SCHEDULE_EXACT_ALARM settings sent.")
                        } catch (e: Exception) {
                            Log.e(logTag, "Failed to start ACTION_REQUEST_SCHEDULE_EXACT_ALARM settings activity. Attempting fallback.", e)
                            // Fallback: Open general app settings or notification settings for the app
                            // as ACTION_REQUEST_SCHEDULE_EXACT_ALARM might not be available or behave differently on some OEM devices.
                            try {
                                val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(fallbackIntent)
                                Log.i(logTag, "Fallback: Intent to ACTION_APPLICATION_DETAILS_SETTINGS sent.")
                            } catch (e2: Exception) {
                                Log.e(logTag, "Fallback to ACTION_APPLICATION_DETAILS_SETTINGS also failed.", e2)
                            }
                        }
                    } else {
                        Log.w(logTag, "Exact Alarm Permission guidance requested on pre-SDK S device. This UI path should not be active.")
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume. Re-checking all permissions.")
        checkAndUpdateAllPermissions(this)
    }

    private fun checkAndUpdateAllPermissions(context: Context) {
        Log.d(logTag, "Executing checkAndUpdateAllPermissions.")
        // Standard permissions
        val sensorsGranted = ContextCompat.checkSelfPermission(context, BODY_SENSORS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        viewModel.updateBodySensorsPermissionStatus(sensorsGranted)

        if (requiresNotificationPermission) {
            val notificationsGranted = ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION_STRING) == PackageManager.PERMISSION_GRANTED
            viewModel.updateNotificationsPermissionStatus(notificationsGranted)
        } else {
            viewModel.updateNotificationsPermissionStatus(true)
        }

        viewModel.updateExactAlarmPermissionStatus()
        Log.d(logTag, "Finished checkAndUpdateAllPermissions.")
    }

    private fun startHeartRateService(context: Context) {
        val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
        try {
            Log.i(logTag, "Attempting to start foreground service.")
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start HeartRateMonitorService.", e)
        }
    }

    private fun logServiceStartConditionNotMet(uiState: MainUiState) {
        val reason = when {
            !uiState.isBodySensorsPermissionGranted -> "Body Sensors permission not granted"
            uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
            else -> "Unknown reason"
        }
        Log.i(logTag, "Service start conditions not met: $reason")
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
    onRequestExactAlarmPermission: () -> Unit
) {
    Gobbleoclockv2Theme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            timeText = { TimeText(modifier = Modifier.padding(top = 4.dp)) }
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
                    onRequestExactAlarmPermission = onRequestExactAlarmPermission
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
    onRequestExactAlarmPermission: () -> Unit
) {
    val scrollState = rememberScrollState()
    var showTargetHrDialog by rememberSaveable { mutableStateOf(false) }
    // Correctly use a distinct variable for the TargetHoursDialog visibility state
    var showTargetHoursDialogState by rememberSaveable { mutableStateOf(false) }


    TargetHeartRateDialog(
        showDialog = showTargetHrDialog,
        initialValue = uiState.targetHeartRate,
        onDismiss = { showTargetHrDialog = false },
        onConfirm = { newValue ->
            onUpdateTargetHeartRate(newValue)
            showTargetHrDialog = false
        }
    )

    TargetHoursDialog(
        showDialog = showTargetHoursDialogState, // Use the corrected state variable
        initialValue = uiState.targetHours,
        onDismiss = { showTargetHoursDialogState = false }, // Use the corrected state variable
        onConfirm = { newValue ->
            onUpdateTargetHours(newValue)
            showTargetHoursDialogState = false // Use the corrected state variable
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .verticalScroll(scrollState)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        val canChangeSettings = uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING

        Chip(
            label = { Text("Target HR: ${uiState.targetHeartRate} bpm") },
            onClick = { if (canChangeSettings) showTargetHrDialog = true },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        Chip(
            label = { Text("Target Hours: ${uiState.targetHours}h") },
            onClick = { if (canChangeSettings) showTargetHoursDialogState = true }, // Use corrected state variable
            colors = ChipDefaults.secondaryChipColors(),
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            uiState.targetHours > 0 &&
            !uiState.canScheduleExactAlarms &&
            uiState.appState == AppState.MONITORING) {
            Chip(
                label = { Text("Alarm Perm Needed", textAlign = TextAlign.Center) },
                onClick = onRequestExactAlarmPermission,
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
        settingsDisabledReason?.let {
            Text(
                text = it,
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

        if (uiState.appState == AppState.MONITORING && uiState.monitoringStartTime > 0L && uiState.targetHours > 0) {
            val alertTimeMillis = uiState.monitoringStartTime + (uiState.targetHours * 60 * 60 * 1000L)
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            Text(
                text = "Alert By: ${sdf.format(alertTimeMillis)}",
                style = MaterialTheme.typography.caption1,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        when {
            !uiState.isBodySensorsPermissionGranted -> {
                PermissionRequiredContent(
                    permissionType = "Body Sensors",
                    permissionName = BODY_SENSORS_PERMISSION,
                    onRequestPermission = onRequestPermission
                )
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Text(text = "Grant sensor permission to reset.", style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, color = MaterialTheme.colors.error, modifier = Modifier.padding(top = 2.dp))
                }
            }
            requiresNotificationPermission && !uiState.isNotificationsPermissionGranted -> {
                PermissionRequiredContent(
                    permissionType = "Notifications",
                    permissionName = POST_NOTIFICATIONS_PERMISSION_STRING,
                    onRequestPermission = onRequestPermission,
                    rationale = "Notifications needed for Gobble Time alerts."
                )
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Chip(label = { Text("Reset Monitor") }, onClick = onResetMonitoring, colors = ChipDefaults.chipColors())
                }
            }
            else -> {
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Chip(label = { Text("Reset Monitor") }, onClick = onResetMonitoring, colors = ChipDefaults.chipColors())
                } else {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || uiState.targetHours == 0 || uiState.canScheduleExactAlarms) {
                        Text(text = "Monitoring active...", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.secondary)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun PermissionRequiredContent(
    permissionType: String,
    permissionName: String,
    onRequestPermission: (String) -> Unit,
    rationale: String? = null
) {
    val context = LocalContext.current
    val defaultRationale = "$permissionType permission needed."
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = rationale ?: defaultRationale, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.error)
        Chip(label = { Text("Grant $permissionType") }, onClick = { onRequestPermission(permissionName) }, colors = ChipDefaults.chipColors())

        if (permissionName == BODY_SENSORS_PERMISSION) {
            Button(
                onClick = {
                    Log.i("PermissionRequired", "Opening app details settings for $permissionName.")
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // FLAG_ACTIVITY_NEW_TASK is usually fine for this
                        })
                    } catch (e: Exception) {
                        Log.e("PermissionRequired", "Failed to open app details settings for $permissionName.", e)
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
    val minTargetHr = 30
    val maxTargetHr = 200
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableStateOf(initialValue.coerceIn(minTargetHr, maxTargetHr)) }

    LaunchedEffect(initialValue, showDialog) {
        if (showDialog) {
            selectedValue = initialValue.coerceIn(minTargetHr, maxTargetHr)
        }
    }

    if (showDialog) {
        Dialog(showDialog = true, onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Alert(
                title = { Text(text = "Set Target HR", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                negativeButton = { Button(onClick = onDismiss, colors = ButtonDefaults.secondaryButtonColors()) { Text("Cancel") } },
                positiveButton = { Button(onClick = { onConfirm(selectedValue) }, colors = ButtonDefaults.buttonColors()) { Text("OK") } }
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { if (selectedValue > minTargetHr) selectedValue-- }, enabled = selectedValue > minTargetHr, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) { Text(text = "-") }
                        Text(text = "$selectedValue bpm", style = MaterialTheme.typography.body1, textAlign = TextAlign.Center, modifier = Modifier.width(IntrinsicSize.Min))
                        Button(onClick = { if (selectedValue < maxTargetHr) selectedValue++ }, enabled = selectedValue < maxTargetHr, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) { Text(text = "+") }
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
    val minTargetHours = 1
    val maxTargetHours = 99
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableStateOf(initialValue.coerceIn(minTargetHours, maxTargetHours)) }

    LaunchedEffect(initialValue, showDialog) {
        if (showDialog) {
            selectedValue = initialValue.coerceIn(minTargetHours, maxTargetHours)
        }
    }

    if (showDialog) {
        Dialog(
            showDialog = true,
            onDismissRequest = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Alert(
                title = { Text(text = "Set Target Hours", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                negativeButton = { Button(onClick = onDismiss, colors = ButtonDefaults.secondaryButtonColors()) { Text("Cancel") } },
                positiveButton = { Button(onClick = { onConfirm(selectedValue) }, colors = ButtonDefaults.buttonColors()) { Text("OK") } }
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
                            onClick = { if (selectedValue > minTargetHours) selectedValue-- },
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
                            onClick = { if (selectedValue < maxTargetHours) selectedValue++ },
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
    monitoringStartTime: Long = System.currentTimeMillis() - (2 * 60 * 60 * 1000L),
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
private fun PreviewWearAppWrapper(uiState: MainUiState, requiresNotificationPermission: Boolean = false) {
    Gobbleoclockv2Theme {
        WearApp(
            uiState = uiState,
            requiresNotificationPermission = requiresNotificationPermission,
            onRequestPermission = {},
            onResetMonitoring = {},
            onUpdateTargetHeartRate = {},
            onUpdateTargetHours = {},
            onRequestExactAlarmPermission = {}
        )
    }
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
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
                Log.d(logTag, "Checking initial permissions.")
                checkAndUpdatePermissions(context)
                onDispose { Log.d(logTag, "Permission check effect disposed.") }
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
                        Log.w(logTag, "Attempted to request empty permission.")
                    } else {
                        Log.d(logTag, "Perm request for '$permissionRequestInProgress' in progress. Ignoring '$permission'.")
                    }
                },
                onResetMonitoring = viewModel::resetMonitoring,
                onUpdateTargetHeartRate = viewModel::updateTargetHeartRate,
                onUpdateTargetHours = viewModel::updateTargetHours
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume. Re-checking permissions.")
        checkAndUpdatePermissions(this)
    }

    private fun checkAndUpdatePermissions(context: Context) {
        val sensorsGranted = ContextCompat.checkSelfPermission(context, BODY_SENSORS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        viewModel.updateBodySensorsPermissionStatus(sensorsGranted)
        if (requiresNotificationPermission) {
            val notificationsGranted = ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION_STRING) == PackageManager.PERMISSION_GRANTED
            viewModel.updateNotificationsPermissionStatus(notificationsGranted)
        } else {
            viewModel.updateNotificationsPermissionStatus(true)
        }
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
    onUpdateTargetHours: (Int) -> Unit
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
                    onUpdateTargetHours = onUpdateTargetHours
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
    onUpdateTargetHours: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    var showTargetHrDialog by rememberSaveable { mutableStateOf(false) }
    var showTargetHoursDialog by rememberSaveable { mutableStateOf(false) }

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
        showDialog = showTargetHoursDialog,
        initialValue = uiState.targetHours,
        onDismiss = { showTargetHoursDialog = false },
        onConfirm = { newValue ->
            onUpdateTargetHours(newValue)
            showTargetHoursDialog = false
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
            colors = ChipDefaults.secondaryChipColors(), // Settings chips are secondary
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

        Chip(
            label = { Text("Target Hours: ${uiState.targetHours}h") },
            onClick = { if (canChangeSettings) showTargetHoursDialog = true },
            colors = ChipDefaults.secondaryChipColors(), // Settings chips are secondary
            enabled = canChangeSettings,
            modifier = Modifier.fillMaxWidth(0.8f)
        )

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
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()) // Use HH for 24-hour format, hh for 12-hour with AM/PM
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
                    // Corrected: Using standard chipColors for primary actions
                    Chip(label = { Text("Reset Monitor") }, onClick = onResetMonitoring, colors = ChipDefaults.chipColors())
                }
            }
            else -> {
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    // Corrected: Using standard chipColors for primary actions
                    Chip(label = { Text("Reset Monitor") }, onClick = onResetMonitoring, colors = ChipDefaults.chipColors())
                } else {
                    Text(text = "Monitoring active...", style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.secondary)
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
        // Corrected: Using standard chipColors for primary actions
        Chip(label = { Text("Grant $permissionType") }, onClick = { onRequestPermission(permissionName) }, colors = ChipDefaults.chipColors())
        if (permissionName == BODY_SENSORS_PERMISSION) {
            Button(
                onClick = {
                    Log.i("PermissionRequired", "Opening app settings for $permissionName.")
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (e: Exception) {
                        Log.e("PermissionRequired", "Failed to open app settings.", e)
                    }
                },
                modifier = Modifier.padding(top = 2.dp),
                colors = ButtonDefaults.secondaryButtonColors() // Open Settings can be secondary
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
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableIntStateOf(initialValue) }
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
                // Corrected: Using standard button colors for dialog confirm
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
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableIntStateOf(initialValue) }
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
                // Corrected: Using standard button colors for dialog confirm
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
    isNotificationsPermissionGranted: Boolean = false
): MainUiState = remember(appState, consecutiveCount, lastDisplayedHr, targetHeartRate, targetHours, monitoringStartTime, isBodySensorsPermissionGranted, isNotificationsPermissionGranted) {
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        targetHours = targetHours,
        monitoringStartTime = monitoringStartTime,
        isBodySensorsPermissionGranted = isBodySensorsPermissionGranted,
        isNotificationsPermissionGranted = isNotificationsPermissionGranted
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
            onUpdateTargetHours = {}
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Monitoring with Alert Time")
@Composable private fun PreviewMonitoringWithAlertTime() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true, lastDisplayedHr = 72, targetHours = 6)
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
    val state = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}
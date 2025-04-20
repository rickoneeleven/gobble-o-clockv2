package com.example.gobble_o_clockv2.presentation

// --- Android Core & SDK Imports ---
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build // Ensure Build is imported
import android.os.Bundle
import android.provider.Settings
import android.util.Log

// --- Activity & ViewModel Imports ---
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

// --- Compose UI & Foundation Imports (Core) ---
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.* // Includes Column, Row, Spacer, padding, fillMaxWidth, IntrinsicSize, size, height, width etc.
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.* // Includes Composable, LaunchedEffect, DisposableEffect, remember, mutableStateOf, mutableIntStateOf etc.
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview // Ensure this is the correct preview import
import androidx.compose.ui.unit.dp

// --- AndroidX Core & Lifecycle Imports ---
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// --- Wear Compose Material Components ---
// Explicit imports are good practice
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert // Correct import for Alert
import androidx.wear.compose.material.dialog.Dialog // Correct import for Dialog

// --- Wear Tooling Imports ---
import androidx.wear.tooling.preview.devices.WearDevices // Correct preview devices import

// --- App Specific Imports ---
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService

// Define Permissions Constantly
private const val BODY_SENSORS_PERMISSION = Manifest.permission.BODY_SENSORS
// Check if target API needs POST_NOTIFICATIONS (API 33+)
private val POST_NOTIFICATIONS_PERMISSION_STRING = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.POST_NOTIFICATIONS
} else {
    // Provide a placeholder or handle appropriately for older APIs if needed,
    // although the permission itself won't exist. Empty string signals it's not applicable.
    ""
}

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity"
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    // Flag to track if the OS supports notification permission requests
    private val requiresNotificationPermission = POST_NOTIFICATIONS_PERMISSION_STRING.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.i(logTag, "onCreate lifecycle event. Requires Notification Permission: $requiresNotificationPermission")

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current

            // State to prevent multiple simultaneous permission requests
            var permissionRequestInProgress by rememberSaveable { mutableStateOf<String?>(null) } // Track which permission

            // --- Permission Launchers ---
            val bodySensorsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "$BODY_SENSORS_PERMISSION permission result: isGranted=$isGranted")
                    viewModel.updateBodySensorsPermissionStatus(isGranted)
                    permissionRequestInProgress = null // Clear flag
                }
            )

            val notificationsPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    if (requiresNotificationPermission) { // Only log/update if permission is relevant
                        Log.i(logTag, "$POST_NOTIFICATIONS_PERMISSION_STRING permission result: isGranted=$isGranted")
                        viewModel.updateNotificationsPermissionStatus(isGranted)
                    }
                    permissionRequestInProgress = null // Clear flag
                }
            )

            // --- Initial Permission Checks ---
            DisposableEffect(Unit) {
                Log.d(logTag, "Checking initial permissions.")
                checkAndUpdatePermissions(context)
                onDispose { Log.d(logTag, "Permission check effect disposed.") }
            }

            // --- Service Control Effect ---
            LaunchedEffect(uiState.isBodySensorsPermissionGranted, uiState.appState) {
                Log.d(logTag, "Service control effect triggered. isSensorsGranted=${uiState.isBodySensorsPermissionGranted}, appState=${uiState.appState}")
                if (uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(logTag, "Conditions met to start service (Sensors Granted & State MONITORING).")
                    startHeartRateService(context)
                } else {
                    logServiceStartConditionNotMet(uiState)
                    // Optional: Explicitly stop service if needed (see comment in function)
                    // if (uiState.appState == AppState.GOBBLE_TIME) {
                    //     stopHeartRateService(context)
                    // }
                }
            }

            // --- UI ---
            WearApp(
                uiState = uiState,
                requiresNotificationPermission = requiresNotificationPermission,
                onRequestPermission = { permission -> // Lambda now takes the permission string
                    if (permissionRequestInProgress == null && permission.isNotEmpty()) { // Ensure permission string is valid
                        Log.i(logTag, "Requesting permission: $permission")
                        permissionRequestInProgress = permission // Set flag
                        when(permission) {
                            BODY_SENSORS_PERMISSION -> bodySensorsPermissionLauncher.launch(permission)
                            POST_NOTIFICATIONS_PERMISSION_STRING -> notificationsPermissionLauncher.launch(permission) // Use constant
                            else -> {
                                Log.w(logTag, "Unknown or inapplicable permission requested: $permission")
                                permissionRequestInProgress = null // Clear flag on error
                            }
                        }
                    } else if (permission.isEmpty()) {
                        Log.w(logTag, "Attempted to request an empty/inapplicable permission string.")
                    } else {
                        Log.d(logTag, "Permission request for '$permissionRequestInProgress' already in progress. Ignoring new request for '$permission'.")
                    }
                },
                onResetMonitoring = viewModel::resetMonitoring,
                onUpdateTargetHeartRate = viewModel::updateTargetHeartRate
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume lifecycle event. Re-checking permissions.")
        checkAndUpdatePermissions(this)
    }

    private fun checkAndUpdatePermissions(context: Context) {
        // Check Body Sensors
        val sensorsGranted = ContextCompat.checkSelfPermission(
            context, BODY_SENSORS_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.updateBodySensorsPermissionStatus(sensorsGranted)
        // Log.d(logTag, "Checked $BODY_SENSORS_PERMISSION: isGranted=$sensorsGranted") // Less verbose

        // Check Notifications (only if required by SDK)
        if (requiresNotificationPermission) {
            val notificationsGranted = ContextCompat.checkSelfPermission(
                context, POST_NOTIFICATIONS_PERMISSION_STRING // Use constant
            ) == PackageManager.PERMISSION_GRANTED
            viewModel.updateNotificationsPermissionStatus(notificationsGranted)
            // Log.d(logTag, "Checked $POST_NOTIFICATIONS_PERMISSION_STRING: isGranted=$notificationsGranted") // Less verbose
        } else {
            viewModel.updateNotificationsPermissionStatus(true) // Implicitly granted on older SDKs
            // Log.d(logTag, "$POST_NOTIFICATIONS_PERMISSION_STRING not required by SDK, considered granted.")
        }
    }


    private fun startHeartRateService(context: Context) {
        val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
        try {
            Log.i(logTag, "Attempting to start foreground service.")
            ContextCompat.startForegroundService(context, serviceIntent)
            // Log.i(logTag, "startForegroundService called successfully.") // Less verbose
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start HeartRateMonitorService via startForegroundService.", e)
        }
    }

    private fun logServiceStartConditionNotMet(uiState: MainUiState) {
        val reason = when {
            !uiState.isBodySensorsPermissionGranted -> "Body Sensors permission not granted"
            uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
            else -> "Unknown reason"
        }
        Log.i(logTag, "Service start conditions not met: $reason")
        // If the service might already be running but conditions are no longer met (e.g., state changed),
        // the service itself handles unregistering the listener. Stopping the service explicitly
        // might be needed if it manages other resources, but for now, relying on internal service logic.
    }
}

// --- Composable UI ---

@Composable
fun WearApp(
    uiState: MainUiState,
    requiresNotificationPermission: Boolean,
    onRequestPermission: (String) -> Unit,
    onResetMonitoring: () -> Unit,
    onUpdateTargetHeartRate: (Int) -> Unit
) {
    Gobbleoclockv2Theme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            timeText = { TimeText(modifier = Modifier.padding(top = 4.dp)) }
        ) {
            // Use BoxWithConstraints if needed later for adaptive layouts
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
                    onUpdateTargetHeartRate = onUpdateTargetHeartRate
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
    onUpdateTargetHeartRate: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    var showTargetDialog by rememberSaveable { mutableStateOf(false) }

    // --- THIS IS THE CALL SITE FOR THE DIALOG ---
    // Ensure the function definition below matches this call.
    TargetHeartRateDialog(
        showDialog = showTargetDialog,
        initialValue = uiState.targetHeartRate,
        onDismiss = {
            Log.d("StateDisplay", "TargetHeartRateDialog dismissed.")
            showTargetDialog = false
        },
        onConfirm = { newValue ->
            Log.i("StateDisplay", "TargetHeartRateDialog confirmed with value: $newValue")
            onUpdateTargetHeartRate(newValue)
            showTargetDialog = false
        }
    )
    // --- END OF DIALOG CALL ---

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp) // Consistent padding
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp), // Padding for top/bottom scroll space
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp) // Consistent spacing between items
    ) {
        // --- Core HR Info ---
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        // --- Target HR Chip & Helper Text ---
        val canChangeTarget = uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING
        Chip(
            label = { Text("Target: ${uiState.targetHeartRate} bpm") },
            onClick = { if (canChangeTarget) showTargetDialog = true },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = canChangeTarget,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        val targetChipHelperText = when {
            !uiState.isBodySensorsPermissionGranted && uiState.appState == AppState.MONITORING -> "(Grant Sensors to change)"
            uiState.appState == AppState.GOBBLE_TIME -> "(Reset monitor to change)"
            else -> null
        }
        targetChipHelperText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // --- Status & Count ---
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

        Spacer(modifier = Modifier.height(8.dp)) // Space before buttons/permissions

        // --- Action Buttons / Permission Prompts ---
        when {
            // 1. Body Sensors Permission Needed FIRST
            !uiState.isBodySensorsPermissionGranted -> {
                PermissionRequiredContent(
                    permissionType = "Body Sensors",
                    permissionName = BODY_SENSORS_PERMISSION,
                    onRequestPermission = onRequestPermission
                )
                // Add helper text if in Gobble Time but sensors denied
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Text(
                        text = "Grant sensor permission to reset.",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // 2. Body Sensors Granted, Check Notification Permission (if required by SDK & not granted)
            requiresNotificationPermission && !uiState.isNotificationsPermissionGranted -> {
                PermissionRequiredContent(
                    permissionType = "Notifications",
                    permissionName = POST_NOTIFICATIONS_PERMISSION_STRING, // Use constant
                    onRequestPermission = onRequestPermission,
                    rationale = "Notifications needed for Gobble Time alerts." // Specific rationale
                )
                // Show Reset button even if notifications are denied, as long as sensors are granted
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Spacer(modifier = Modifier.height(6.dp)) // Add space if both are shown
                    Chip(
                        label = { Text("Reset Monitor") },
                        onClick = onResetMonitoring, // Will be guarded by sensor permission check in ViewModel
                        colors = ChipDefaults.primaryChipColors()
                    )
                }
            }
            // 3. All necessary permissions granted OR Notification perm not required
            else -> {
                if (uiState.appState == AppState.GOBBLE_TIME) {
                    Chip(
                        label = { Text("Reset Monitor") },
                        onClick = onResetMonitoring, // Guarded in ViewModel
                        colors = ChipDefaults.primaryChipColors()
                    )
                } else {
                    // Monitoring is active, show subtle indicator or nothing
                    Text(
                        text = "Monitoring active...",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.secondary // Use a less prominent color
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequiredContent(
    permissionType: String, // e.g., "Body Sensors", "Notifications"
    permissionName: String, // The Manifest.permission string
    onRequestPermission: (String) -> Unit,
    rationale: String? = null // Optional specific rationale
) {
    val context = LocalContext.current
    val defaultRationale = "$permissionType permission needed for core functionality."
    // Log.d("PermissionRequired", "Rendering permission request UI for $permissionType") // Less verbose

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp) // Space between elements
    ) {
        Text(
            text = rationale ?: defaultRationale,
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.error
        )
        Chip(
            label = { Text("Grant $permissionType") },
            onClick = { onRequestPermission(permissionName) }, // Pass the specific permission name
            colors = ChipDefaults.primaryChipColors()
        )
        // Only show "Open Settings" for the critical Body Sensors permission.
        if (permissionName == BODY_SENSORS_PERMISSION) {
            Button(
                onClick = {
                    Log.i("PermissionRequired", "Opening app settings via button for $permissionName.")
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Need NEW_TASK when starting from non-Activity context potentially
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("PermissionRequired", "Failed to open app settings.", e)
                        // TODO: Maybe show a toast to the user that opening settings failed?
                    }
                },
                modifier = Modifier.padding(top = 4.dp), // Space from Grant button
                colors = ButtonDefaults.secondaryButtonColors() // Less prominent style
            ) {
                Text("Open Settings", style = MaterialTheme.typography.caption2) // Smaller text
            }
        }
    }
}


// --- THIS IS THE FUNCTION DEFINITION ---
// Ensure it's at the top level within the file, not nested inside another composable or class.
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

    // Only compose the Dialog when showDialog is true for efficiency
    if (showDialog) {
        Dialog(
            showDialog = true, // This parameter name is specific to the Wear Dialog composable
            onDismissRequest = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp) // Padding around the dialog
        ) {
            Alert(
                title = {
                    Text(
                        text = "Set Target HR",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth() // Ensure title centers correctly
                    )
                },
                negativeButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("Cancel") }
                },
                positiveButton = {
                    Button(
                        onClick = { onConfirm(selectedValue) },
                        colors = ButtonDefaults.primaryButtonColors() // Standard primary color for confirm
                    ) { Text("OK") }
                }
                // Content is the lambda body of Alert
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(), // Ensure column takes width for alignment
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center // Center content vertically
                ) {
                    Spacer(modifier = Modifier.height(8.dp)) // Space below title (within Alert content)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp) // Space between -, value, +
                    ) {
                        // Decrease Button
                        Button(
                            onClick = { if (selectedValue > minTargetHr) selectedValue-- },
                            enabled = selectedValue > minTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize) // Use standard small size
                        ) {
                            Text(text = "-") // Standard minus icon text
                        }

                        // Value Text
                        Text(
                            text = "$selectedValue bpm",
                            style = MaterialTheme.typography.body1, // Appropriate size for value display
                            textAlign = TextAlign.Center,
                            // Let the text determine its own width, Row handles spacing
                            modifier = Modifier.width(IntrinsicSize.Min) // Keep min width for stability
                        )

                        // Increase Button
                        Button(
                            onClick = { if (selectedValue < maxTargetHr) selectedValue++ },
                            enabled = selectedValue < maxTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize) // Use standard small size
                        ) {
                            Text(text = "+") // Standard plus icon text
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp)) // Space above buttons (within Alert content)
                }
            }
        }
    }
}
// --- END OF FUNCTION DEFINITION ---


// --- Previews ---
// Helper function to create preview state
@Composable
private fun rememberPreviewState(
    appState: AppState = AppState.MONITORING,
    consecutiveCount: Int = 0,
    lastDisplayedHr: Int = 0,
    targetHeartRate: Int = 70,
    isBodySensorsPermissionGranted: Boolean = false,
    isNotificationsPermissionGranted: Boolean = false
): MainUiState = remember(appState, consecutiveCount, lastDisplayedHr, targetHeartRate, isBodySensorsPermissionGranted, isNotificationsPermissionGranted) {
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        isBodySensorsPermissionGranted = isBodySensorsPermissionGranted,
        isNotificationsPermissionGranted = isNotificationsPermissionGranted
    )
}

// Helper for passing params to WearApp preview consistently
@Composable
private fun PreviewWearAppWrapper(uiState: MainUiState, requiresNotificationPermission: Boolean = false) {
    Gobbleoclockv2Theme {
        WearApp(
            uiState = uiState,
            requiresNotificationPermission = requiresNotificationPermission,
            onRequestPermission = {}, // No-op for preview
            onResetMonitoring = {}, // No-op for preview
            onUpdateTargetHeartRate = {} // No-op for preview
        )
    }
}

// --- Preview Cases ---

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Sensors Needed")
@Composable private fun PreviewSensorsNeeded() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = false, lastDisplayedHr = 65)
    PreviewWearAppWrapper(uiState = state)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Notifs Needed (API 33+)")
@Composable private fun PreviewNotificationsNeededApi33() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = false)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Notifs Not Needed (<API 33)")
@Composable private fun PreviewNotificationsNotNeededApi30() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = false)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Monitoring (All Granted)")
@Composable private fun PreviewAllGrantedMonitoring() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1, targetHeartRate = 68)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Gobble Time (All Granted)")
@Composable private fun PreviewGobbleTimeAllGranted() {
    val state = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = true)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Gobble Time (Sensors Denied)")
@Composable private fun PreviewGobbleTimeSensorsDenied() {
    val state = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isBodySensorsPermissionGranted = false)
    PreviewWearAppWrapper(uiState = state)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Gobble Time (Notifs Denied API 33+)")
@Composable private fun PreviewGobbleTimeNotificationsDeniedApi33() {
    val state = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isBodySensorsPermissionGranted = true, isNotificationsPermissionGranted = false)
    PreviewWearAppWrapper(uiState = state, requiresNotificationPermission = true)
}


@Preview(device = WearDevices.SQUARE, showSystemUi = true, name = "Square (Sensors Needed)")
@Composable private fun PreviewSquareSensorsNeeded() {
    val state = rememberPreviewState(isBodySensorsPermissionGranted = false)
    PreviewWearAppWrapper(uiState = state)
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Dialog Preview", backgroundColor = 0xFF000000, showBackground = true)
@Composable private fun PreviewTargetHeartRateDialog() {
    Gobbleoclockv2Theme {
        // Simulate the dimming effect often seen behind dialogs
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            var showDialog by remember { mutableStateOf(true) } // Start with dialog shown for preview
            var targetHr by remember { mutableIntStateOf(65) }

            // --- Call the actual dialog function here for preview ---
            TargetHeartRateDialog(
                showDialog = showDialog,
                initialValue = targetHr,
                onDismiss = { /* Preview dismiss: Do nothing or log */ },
                onConfirm = { newValue -> targetHr = newValue; /* Preview confirm: Do nothing or log */ }
            )
            // --- End dialog call ---
        }
    }
}
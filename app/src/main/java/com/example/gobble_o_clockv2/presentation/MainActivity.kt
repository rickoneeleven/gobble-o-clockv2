package com.example.gobble_o_clockv2.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button // Explicit import
import androidx.wear.compose.material.ButtonDefaults // Explicit import
import androidx.wear.compose.material.Chip // Explicit import
import androidx.wear.compose.material.ChipDefaults // Explicit import
import androidx.wear.compose.material.Icon // Explicit import
import androidx.wear.compose.material.MaterialTheme // Explicit import
import androidx.wear.compose.material.Scaffold // Explicit import
import androidx.wear.compose.material.Stepper // Explicit import
import androidx.wear.compose.material.StepperDefaults // Explicit import
import androidx.wear.compose.material.Text // Explicit import
import androidx.wear.compose.material.TimeText // Explicit import
import androidx.wear.compose.material.dialog.Alert // Explicit import
import androidx.wear.compose.material.dialog.Dialog // Explicit import
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.gobble_o_clockv2.R // Keep Resource import
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity"
    // Use the custom factory for ViewModel instantiation
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.i(logTag, "onCreate lifecycle event.")

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var permissionRequestInProgress by rememberSaveable { mutableStateOf(false) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "BODY_SENSORS permission result received: isGranted=$isGranted")
                    viewModel.updatePermissionStatus(isGranted)
                    permissionRequestInProgress = false // Allow new requests
                }
            )

            // Effect to check initial permission status on composition
            DisposableEffect(Unit) {
                Log.d(logTag, "Checking initial BODY_SENSORS permission.")
                val isGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BODY_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
                Log.d(logTag, "Initial permission status: isGranted=$isGranted")
                viewModel.updatePermissionStatus(isGranted)
                // No explicit cleanup needed here
                onDispose { Log.d(logTag, "Permission check effect disposed.") }
            }

            // Effect to control the foreground service based on state and permission
            LaunchedEffect(uiState.isPermissionGranted, uiState.appState) {
                Log.d(logTag, "Service control effect triggered. isPermissionGranted=${uiState.isPermissionGranted}, appState=${uiState.appState}")
                if (uiState.isPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(logTag, "Conditions met to start service (Permission Granted & State MONITORING).")
                    startHeartRateService(context)
                } else {
                    // Log why service isn't starting, but rely on service self-stopping if needed
                    logServiceStartConditionNotMet(uiState)
                    // Consider stopping service explicitly if needed, though current logic has service stop itself
                    // stopHeartRateService(context)
                }
            }

            WearApp(
                uiState = uiState,
                onRequestPermission = {
                    if (!permissionRequestInProgress) {
                        Log.i(logTag, "Requesting BODY_SENSORS permission via launcher.")
                        permissionRequestInProgress = true
                        permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                    } else {
                        Log.d(logTag, "Permission request already in progress.")
                    }
                },
                onResetMonitoring = viewModel::resetMonitoring,
                onUpdateTargetHeartRate = viewModel::updateTargetHeartRate
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume lifecycle event. Re-checking permission status.")
        // Ensure UI reflects potentially changed permission status (e.g., changed in system settings)
        val currentPermissionStatus = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        if (viewModel.uiState.value.isPermissionGranted != currentPermissionStatus) {
            Log.i(logTag, "Permission status discrepancy detected onResume. Updating ViewModel: isGranted=$currentPermissionStatus")
            viewModel.updatePermissionStatus(currentPermissionStatus)
        }
    }

    private fun startHeartRateService(context: android.content.Context) {
        val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
        try {
            Log.i(logTag, "Attempting to start foreground service.")
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(logTag, "startForegroundService called successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start HeartRateMonitorService via startForegroundService.", e)
        }
    }

    // Optional: Add function to explicitly stop service if needed elsewhere
    // private fun stopHeartRateService(context: android.content.Context) {
    //    val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
    //    Log.i(logTag, "Attempting to stop service.")
    //    context.stopService(serviceIntent)
    // }

    private fun logServiceStartConditionNotMet(uiState: MainUiState) {
        val reason = when {
            !uiState.isPermissionGranted -> "Permission not granted"
            uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
            else -> "Unknown reason" // Should not happen with current logic
        }
        Log.i(logTag, "Service start conditions not met: $reason")
    }
}

@Composable
fun WearApp(
    uiState: MainUiState,
    onRequestPermission: () -> Unit,
    onResetMonitoring: () -> Unit,
    onUpdateTargetHeartRate: (Int) -> Unit
) {
    Gobbleoclockv2Theme {
        Scaffold(
            modifier = Modifier.fillMaxSize(), // Ensure Scaffold fills the screen
            timeText = { TimeText(modifier = Modifier.padding(top = 4.dp)) } // Standard time text
        ) {
            // Box acts as the main content area provided by Scaffold
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                StateDisplay(
                    uiState = uiState,
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
    onRequestPermission: () -> Unit,
    onResetMonitoring: () -> Unit,
    onUpdateTargetHeartRate: (Int) -> Unit
) {
    val context = LocalContext.current
    // Remember scroll state for the main column
    val scrollState = rememberScrollState()
    // State to control the visibility of the target heart rate dialog
    var showTargetDialog by rememberSaveable { mutableStateOf(false) }

    // Conditionally display the Target Heart Rate Dialog
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

    // Main content column
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp) // Horizontal padding for content
            .verticalScroll(scrollState) // Make column scrollable
            .padding(vertical = 8.dp), // Vertical padding for scroll content
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp) // Consistent spacing between elements
    ) {
        // Display current or placeholder HR
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        // Display target HR chip - clickable to open dialog if conditions met
        val isTargetChipEnabled = uiState.isPermissionGranted && uiState.appState == AppState.MONITORING
        Chip(
            label = { Text("Target: ${uiState.targetHeartRate} bpm") },
            onClick = {
                if (isTargetChipEnabled) {
                    Log.d("StateDisplay", "Target HR chip clicked, showing dialog.")
                    showTargetDialog = true
                } else {
                    Log.d("StateDisplay", "Target HR chip clicked, but disabled (permission=${uiState.isPermissionGranted}, state=${uiState.appState}).")
                }
            },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = isTargetChipEnabled,
            modifier = Modifier.padding(bottom = 2.dp) // Reduced bottom padding if helper text appears
        )

        // Display helper text for the target HR chip when disabled
        val targetChipHelperText = when {
            !uiState.isPermissionGranted && uiState.appState == AppState.MONITORING -> "(Grant permission to change)"
            uiState.appState == AppState.GOBBLE_TIME -> "(Reset monitor to change)"
            else -> null // No helper text needed if enabled
        }
        targetChipHelperText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp) // Spacing after helper text
            )
        }

        // Display consecutive count
        Text(
            text = "Count: ${uiState.consecutiveCount}",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center
        )

        // Display current app status, highlighted if in GOBBLE_TIME
        Text(
            text = "Status: ${uiState.appState.name}",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            color = if (uiState.appState == AppState.GOBBLE_TIME) MaterialTheme.colors.primary else Color.Unspecified // Use default color unless GOBBLE_TIME
        )

        Spacer(modifier = Modifier.height(8.dp)) // Larger spacer before action buttons/permission section

        // Conditional section for actions or permission requests
        if (uiState.isPermissionGranted) {
            // Actions available when permission is granted
            if (uiState.appState == AppState.GOBBLE_TIME) {
                Chip(
                    label = { Text("Reset Monitor") },
                    onClick = onResetMonitoring, // Reference to ViewModel function
                    colors = ChipDefaults.primaryChipColors()
                    // Enabled by default when displayed
                )
            }
            // No primary action shown in MONITORING state when permission is granted
        } else {
            // UI shown when permission is NOT granted
            PermissionRequiredContent(onRequestPermission = onRequestPermission)

            // Additional helper text if trying to reset without permission
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
    }
}

@Composable
private fun PermissionRequiredContent(onRequestPermission: () -> Unit) {
    val context = LocalContext.current
    Log.d("PermissionRequired", "Rendering permission request UI elements.")

    Text(
        text = "Sensor permission needed for heart rate monitoring.",
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.error
    )
    Spacer(modifier = Modifier.height(4.dp))
    Chip(
        label = { Text("Grant Permission") },
        onClick = onRequestPermission,
        colors = ChipDefaults.primaryChipColors()
    )
    // Button to open system settings for the app
    Button(
        onClick = {
            Log.i("PermissionRequired", "Opening app settings via button.")
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Recommended for starting activity from non-activity context
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("PermissionRequired", "Failed to open app settings.", e)
                // Consider showing a Toast to the user indicating failure
            }
        },
        modifier = Modifier.padding(top = 4.dp),
        colors = ButtonDefaults.secondaryButtonColors()
    ) {
        Text("Open Settings", style = MaterialTheme.typography.caption2)
    }
}


@Composable
fun TargetHeartRateDialog(
    showDialog: Boolean,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    // Use rememberSaveable with keys to ensure state resets correctly if initialValue changes while dialog is hidden
    var selectedValue by rememberSaveable(initialValue, showDialog) { mutableIntStateOf(initialValue) }

    // Only compose the Dialog if showDialog is true for efficiency
    if (showDialog) {
        Dialog(
            showDialog = true, // Explicitly true since we are inside the if block
            onDismissRequest = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp) // Standard padding
        ) {
            // Alert provides standard dialog layout: Title, Content, Buttons
            Alert(
                title = { Text(text = "Set Target HR", textAlign = TextAlign.Center) },
                negativeButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("Cancel") }
                },
                positiveButton = {
                    Button(onClick = { onConfirm(selectedValue) }) { Text("OK") }
                }
            ) { // Content slot of the Alert (provides ColumnScope)
                Column(
                    modifier = Modifier.fillMaxWidth(), // Allow content to center horizontally
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // --- Stepper Call for Wear Compose Material 1.4.x ---
                    // This signature matches the documentation for recent versions
                    Stepper(
                        value = selectedValue,
                        onValueChange = { newValue: Int -> selectedValue = newValue }, // Explicit type helps compiler
                        valueProgression = 30..200, // Standard IntRange for steps of 1
                        increaseButton = { // Standard Composable slot for the increase icon
                            Icon(
                                imageVector = StepperDefaults.Increase, // Use default icon
                                contentDescription = "Increase Target Heart Rate" // Accessibility
                            )
                        },
                        decreaseButton = { // Standard Composable slot for the decrease icon
                            Icon(
                                imageVector = StepperDefaults.Decrease, // Use default icon
                                contentDescription = "Decrease Target Heart Rate" // Accessibility
                            )
                        }
                        // Removed the incorrect trailing lambda from previous attempts
                    ) { displayedValue: Int -> // Use the content lambda to display the value
                        Text(
                            text = "$displayedValue bpm",
                            style = MaterialTheme.typography.body1, // Adjusted style for typical dialog content
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    // --- End Stepper Call ---

                    Spacer(modifier = Modifier.height(12.dp))
                } // End Column
            } // End Alert content
        } // End Dialog
    } // End if(showDialog)
}


// --- Previews ---
// Helper function for creating preview states - remains useful
@Composable
private fun rememberPreviewState(
    appState: AppState = AppState.MONITORING,
    consecutiveCount: Int = 0,
    lastDisplayedHr: Int = 0,
    targetHeartRate: Int = 70,
    isPermissionGranted: Boolean = false
): MainUiState = remember(appState, consecutiveCount, lastDisplayedHr, targetHeartRate, isPermissionGranted) {
    // Added keys to remember for better recomposition control if needed, though likely overkill here
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        isPermissionGranted = isPermissionGranted
    )
}

// Previews using the helper function - update if UI structure changes significantly
@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PreviewPermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false, lastDisplayedHr = 65)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PreviewPermissionGrantedMonitoring() {
    val previewState = rememberPreviewState(isPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1, targetHeartRate = 68)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PreviewGobbleTimePermissionGranted() {
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isPermissionGranted = true
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PreviewGobbleTimePermissionDenied() {
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isPermissionGranted = false
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SQUARE, showSystemUi = true)
@Composable
private fun PreviewSquarePermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

// Preview for the Dialog - Now uses the corrected Stepper structure
@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewTargetHeartRateDialog() {
    Gobbleoclockv2Theme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
            // Simulate dialog state within the preview
            var showDialog by remember { mutableStateOf(true) }
            var targetHr by remember { mutableIntStateOf(65) }
            TargetHeartRateDialog(
                showDialog = showDialog,
                initialValue = targetHr,
                onDismiss = { showDialog = false },
                onConfirm = { newValue ->
                    targetHr = newValue
                    showDialog = false
                }
            )
        }
    }
}
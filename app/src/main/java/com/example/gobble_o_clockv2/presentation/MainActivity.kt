package com.example.gobble_o_clockv2.presentation

import android.Manifest
import android.content.Intent // Import Intent for starting service
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState // Ensure AppState is imported
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService // Import the service

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity"
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate called.")

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            var permissionRequested by remember { mutableStateOf(false) }

            // --- Permission Handling ---
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "BODY_SENSORS permission result: $isGranted")
                    viewModel.updatePermissionStatus(isGranted)
                    permissionRequested = false
                    // Service start logic is handled by LaunchedEffect below
                }
            )

            // Effect to check permission on initial composition
            DisposableEffect(Unit) {
                Log.d(logTag, "DisposableEffect launched for initial permission check.")
                val isGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BODY_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
                Log.d(logTag, "Initial permission check status: $isGranted")
                viewModel.updatePermissionStatus(isGranted)
                onDispose { Log.d(logTag, "DisposableEffect disposed.") }
            }

            // --- Service Start Logic ---
            LaunchedEffect(uiState.isPermissionGranted, uiState.appState) {
                Log.d(logTag, "Service control LaunchedEffect triggered: isPermissionGranted=${uiState.isPermissionGranted}, appState=${uiState.appState}")
                if (uiState.isPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(logTag, "Conditions met (Permission Granted & State MONITORING). Attempting to start service...")
                    val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                        Log.i(logTag, "startForegroundService called successfully.")
                    } catch (e: Exception) {
                        Log.e(logTag, "Failed to start HeartRateMonitorService", e)
                        // TODO: Consider notifying user or updating UI state if start fails critically
                    }
                } else {
                    val reason = when {
                        !uiState.isPermissionGranted -> "Permission not granted"
                        uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
                        else -> "Unknown reason"
                    }
                    Log.i(logTag, "Conditions not met to start service: $reason")
                    // Stop service if state is GOBBLE_TIME?
                    // Note: The service already handles unregistering its listener based on state.
                    // Explicitly stopping might be desired if the service does *nothing* else in GOBBLE_TIME.
                    // Let's rely on the service's internal state handling for now.
                    // if (uiState.appState == AppState.GOBBLE_TIME) {
                    //     val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
                    //     context.stopService(serviceIntent) // Consider implications carefully
                    //     Log.i(logTag, "Stopping service due to GOBBLE_TIME state.")
                    // }
                }
            }

            // --- Render UI ---
            WearApp(
                uiState = uiState,
                onRequestPermission = {
                    if (!permissionRequested) {
                        Log.i(logTag, "Requesting BODY_SENSORS permission...")
                        permissionRequested = true
                        permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                    } else {
                        Log.d(logTag, "Permission request already in progress.")
                    }
                },
                onResetMonitoring = { // Pass the reset callback down
                    viewModel.resetMonitoring()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume called. Re-checking permission status.")
        // Re-check permission status when activity resumes
        val isGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        // Only update if the state might have changed externally (e.g., via settings)
        if (viewModel.uiState.value.isPermissionGranted != isGranted) {
            Log.i(logTag, "Permission status potentially changed externally, updating ViewModel.")
            viewModel.updatePermissionStatus(isGranted)
            // The LaunchedEffect will react to this state change if needed
        }
    }
}

@Composable
fun WearApp(
    uiState: MainUiState,
    onRequestPermission: () -> Unit,
    onResetMonitoring: () -> Unit // Added callback parameter
) {
    Gobbleoclockv2Theme {
        Scaffold(
            timeText = { TimeText() }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center
            ) {
                StateDisplay(
                    uiState = uiState,
                    onRequestPermission = onRequestPermission,
                    onResetMonitoring = onResetMonitoring // Pass down the callback
                )
            }
        }
    }
}

@Composable
fun StateDisplay(
    uiState: MainUiState,
    onRequestPermission: () -> Unit,
    onResetMonitoring: () -> Unit // Added callback parameter
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // HR Display
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        // Target HR
        Text(
            text = "Target: ${uiState.targetHeartRate} bpm",
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center
        )

        // Consecutive Count
        Text(
            text = "Count: ${uiState.consecutiveCount}",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center
        )

        // App State Status
        Text(
            text = "Status: ${uiState.appState.name}",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            color = if (uiState.appState == AppState.GOBBLE_TIME) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Action Buttons Area ---

        // Reset Button (Conditional)
        if (uiState.appState == AppState.GOBBLE_TIME) {
            Chip(
                modifier = Modifier.padding(top = 4.dp),
                label = { Text("Reset Monitor") },
                onClick = onResetMonitoring, // Wire up the reset action
                colors = ChipDefaults.primaryChipColors(),
                enabled = uiState.isPermissionGranted // Should only be resettable if permission is still granted
            )
            // Display message if reset is disabled due to permission loss
            if (!uiState.isPermissionGranted) {
                Text(
                    text = "Grant sensor permission to reset.",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Permission Handling UI (Conditional)
        if (!uiState.isPermissionGranted) {
            Spacer(modifier = Modifier.height(4.dp)) // Add space if Reset button isn't shown
            Text(
                text = "Sensor permission needed for heart rate monitoring.",
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Chip(
                modifier = Modifier.padding(top = 4.dp),
                label = { Text("Grant Permission") },
                onClick = onRequestPermission,
                colors = ChipDefaults.primaryChipColors()
            )
            Button( // Changed to Button for visual distinction from action Chips
                onClick = {
                    Log.i("StateDisplay", "Opening app settings via button.")
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", context.packageName, null)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("StateDisplay", "Failed to open app settings", e)
                        // Optionally show a toast to the user
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Open Settings", style = MaterialTheme.typography.caption2)
            }
        } else {
            // Optionally show permission granted status even when monitoring
            // if (uiState.appState == AppState.MONITORING) { // Only show if not in Gobble Time
            //     Text(
            //         text = "Sensor Permission: Granted",
            //         style = MaterialTheme.typography.caption2,
            //         textAlign = TextAlign.Center,
            //         color = MaterialTheme.colors.secondary,
            //         modifier = Modifier.padding(top = 4.dp)
            //     )
            // }
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
    isPermissionGranted: Boolean = false
) = remember {
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        isPermissionGranted = isPermissionGranted
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewPermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false, lastDisplayedHr = 65)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewPermissionGrantedMonitoring() { // Renamed for clarity
    val previewState = rememberPreviewState(isPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}) }
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewGobbleTimePermissionGranted() {
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME,
        consecutiveCount = 5,
        lastDisplayedHr = 65,
        isPermissionGranted = true // Reset button should be visible and enabled
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewGobbleTimePermissionDenied() { // Added preview for reset disabled state
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME,
        consecutiveCount = 5,
        lastDisplayedHr = 65,
        isPermissionGranted = false // Reset button should be visible but disabled
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}) }
}


@Preview(device = WearDevices.SQUARE, showSystemUi = true)
@Composable
fun PreviewSquarePermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}) }
}
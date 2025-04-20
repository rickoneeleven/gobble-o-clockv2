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
import androidx.compose.runtime.* // Keep wildcard import
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Import Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService

// (MainActivity class and WearApp composable remain unchanged from the previous correct version)
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
            var permissionRequested by rememberSaveable { mutableStateOf(false) }

            // --- Permission Handling ---
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "BODY_SENSORS permission result: $isGranted")
                    viewModel.updatePermissionStatus(isGranted)
                    permissionRequested = false
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
                    }
                } else {
                    val reason = when {
                        !uiState.isPermissionGranted -> "Permission not granted"
                        uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
                        else -> "Unknown reason"
                    }
                    Log.i(logTag, "Conditions not met to start service: $reason")
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
                onResetMonitoring = viewModel::resetMonitoring,
                onUpdateTargetHeartRate = viewModel::updateTargetHeartRate
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume called. Re-checking permission status.")
        val isGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        if (viewModel.uiState.value.isPermissionGranted != isGranted) {
            Log.i(logTag, "Permission status potentially changed externally, updating ViewModel.")
            viewModel.updatePermissionStatus(isGranted)
        }
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
    val scrollState = rememberScrollState()
    var showTargetDialog by rememberSaveable { mutableStateOf(false) }

    TargetHeartRateDialog(
        showDialog = showTargetDialog,
        initialValue = uiState.targetHeartRate,
        onDismiss = { showTargetDialog = false },
        onConfirm = { newValue ->
            onUpdateTargetHeartRate(newValue)
            showTargetDialog = false
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        Chip(
            modifier = Modifier.padding(top = 0.dp, bottom = 4.dp),
            label = { Text("Target: ${uiState.targetHeartRate} bpm") },
            onClick = { if (uiState.isPermissionGranted) showTargetDialog = true },
            colors = ChipDefaults.secondaryChipColors(),
            enabled = uiState.isPermissionGranted
        )
        if (!uiState.isPermissionGranted && uiState.appState == AppState.MONITORING) {
            Text(
                text = "(Grant permission to change)",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp)
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
            color = if (uiState.appState == AppState.GOBBLE_TIME) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.appState == AppState.GOBBLE_TIME) {
            Chip(
                modifier = Modifier.padding(top = 4.dp),
                label = { Text("Reset Monitor") },
                onClick = onResetMonitoring,
                colors = ChipDefaults.primaryChipColors(),
                enabled = uiState.isPermissionGranted
            )
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

        if (!uiState.isPermissionGranted) {
            if(uiState.appState == AppState.MONITORING) Spacer(modifier = Modifier.height(4.dp))
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
            Button(
                onClick = {
                    Log.i("StateDisplay", "Opening app settings via button.")
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        intent.data = Uri.fromParts("package", context.packageName, null)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("StateDisplay", "Failed to open app settings", e)
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text("Open Settings", style = MaterialTheme.typography.caption2)
            }
        }
    }
}

// --- Target Heart Rate Dialog Composable ---
@Composable
fun TargetHeartRateDialog(
    showDialog: Boolean,
    initialValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedValue by rememberSaveable { mutableIntStateOf(initialValue) }
    val scrollState = rememberScrollState()

    Dialog(
        showDialog = showDialog,
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        Alert(
            title = { Text(text = "Set Target HR", textAlign = TextAlign.Center) },
            negativeButton = {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.secondaryButtonColors()
                ) {
                    Text("Cancel")
                }
            },
            positiveButton = {
                Button(
                    onClick = { onConfirm(selectedValue) }
                ) {
                    Text("OK")
                }
            },
        ) {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // *** Explicitly naming Stepper arguments ***
                Stepper(
                    value = selectedValue,
                    onValueChange = { newValue -> selectedValue = newValue },
                    valueRange = 30..200, // IntRange is an IntProgression
                    increaseIcon = { Icon(StepperDefaults.Increase, "Increase") },
                    decreaseIcon = { Icon(StepperDefaults.Decrease, "Decrease") }
                    // No explicit 'steps' parameter needed if using range directly
                ) {
                    Text(
                        text = "$selectedValue bpm",
                        style = MaterialTheme.typography.display3
                    )
                } // End Stepper trailing lambda (content)

                Spacer(modifier = Modifier.height(12.dp))
            } // End Column
        } // End Alert content
    } // End Dialog
}


// --- Previews ---
// (Previews remain unchanged)
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
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewPermissionGrantedMonitoring() {
    val previewState = rememberPreviewState(isPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}


@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewGobbleTimePermissionGranted() {
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME,
        consecutiveCount = 5,
        lastDisplayedHr = 65,
        isPermissionGranted = true
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewGobbleTimePermissionDenied() {
    val previewState = rememberPreviewState(
        appState = AppState.GOBBLE_TIME,
        consecutiveCount = 5,
        lastDisplayedHr = 65,
        isPermissionGranted = false
    )
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SQUARE, showSystemUi = true)
@Composable
fun PreviewSquarePermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
fun PreviewTargetHeartRateDialog() {
    Gobbleoclockv2Theme {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) {
            TargetHeartRateDialog(
                showDialog = true,
                initialValue = 65,
                onDismiss = {},
                onConfirm = {}
            )
        }
    }
}
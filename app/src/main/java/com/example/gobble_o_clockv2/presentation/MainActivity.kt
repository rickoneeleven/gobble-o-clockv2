package com.example.gobble_o_clockv2.presentation

// --- Android Core & SDK Imports ---
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.layout.* // Includes Column, Row, Spacer, padding, etc.
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.* // Includes Composable, LaunchedEffect, DisposableEffect, remember, mutableStateOf, etc.
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// --- AndroidX Core & Lifecycle Imports ---
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// --- Standard Material Icons (Vectors) ---
// Not strictly needed now for the dialog, but keep if used elsewhere or for future
// import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.filled.Add
// import androidx.compose.material.icons.filled.Remove

// --- Wear Compose Material Components ---
// Explicit imports for components used throughout the file
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
// Removed unused import: import androidx.wear.compose.material.IconButton

// --- Wear Tooling Imports ---
import androidx.wear.tooling.preview.devices.WearDevices

// --- App Specific Imports ---
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme
import com.example.gobble_o_clockv2.service.HeartRateMonitorService

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity"
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
                    permissionRequestInProgress = false
                }
            )

            DisposableEffect(Unit) {
                Log.d(logTag, "Checking initial BODY_SENSORS permission.")
                val isGranted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BODY_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
                Log.d(logTag, "Initial permission status: isGranted=$isGranted")
                viewModel.updatePermissionStatus(isGranted)
                onDispose { Log.d(logTag, "Permission check effect disposed.") }
            }

            LaunchedEffect(uiState.isPermissionGranted, uiState.appState) {
                Log.d(logTag, "Service control effect triggered. isPermissionGranted=${uiState.isPermissionGranted}, appState=${uiState.appState}")
                if (uiState.isPermissionGranted && uiState.appState == AppState.MONITORING) {
                    Log.i(logTag, "Conditions met to start service (Permission Granted & State MONITORING).")
                    startHeartRateService(context)
                } else {
                    logServiceStartConditionNotMet(uiState)
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
        val currentPermissionStatus = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED

        if (viewModel.uiState.value.isPermissionGranted != currentPermissionStatus) {
            Log.i(logTag, "Permission status discrepancy detected onResume. Updating ViewModel: isGranted=$currentPermissionStatus")
            viewModel.updatePermissionStatus(currentPermissionStatus)
        }
    }

    private fun startHeartRateService(context: Context) {
        val serviceIntent = Intent(context, HeartRateMonitorService::class.java)
        try {
            Log.i(logTag, "Attempting to start foreground service.")
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(logTag, "startForegroundService called successfully.")
        } catch (e: Exception) {
            Log.e(logTag, "Failed to start HeartRateMonitorService via startForegroundService.", e)
        }
    }

    private fun logServiceStartConditionNotMet(uiState: MainUiState) {
        val reason = when {
            !uiState.isPermissionGranted -> "Permission not granted"
            uiState.appState != AppState.MONITORING -> "App state is ${uiState.appState}"
            else -> "Unknown reason"
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
    val scrollState = rememberScrollState()
    var showTargetDialog by rememberSaveable { mutableStateOf(false) }

    // Call the dialog function - uses Button+Text workaround internally
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

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
            modifier = Modifier.padding(bottom = 2.dp)
        )

        val targetChipHelperText = when {
            !uiState.isPermissionGranted && uiState.appState == AppState.MONITORING -> "(Grant permission to change)"
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

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isPermissionGranted) {
            if (uiState.appState == AppState.GOBBLE_TIME) {
                Chip(
                    label = { Text("Reset Monitor") },
                    onClick = onResetMonitoring,
                    colors = ChipDefaults.primaryChipColors()
                )
            }
        } else {
            PermissionRequiredContent(onRequestPermission = onRequestPermission)
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
    Button(
        onClick = {
            Log.i("PermissionRequired", "Opening app settings via button.")
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    // Only FLAG_ACTIVITY_NEW_TASK is needed here
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("PermissionRequired", "Failed to open app settings.", e)
            }
        },
        modifier = Modifier.padding(top = 4.dp),
        colors = ButtonDefaults.secondaryButtonColors()
    ) {
        Text("Open Settings", style = MaterialTheme.typography.caption2)
    }
}

// --- WORKAROUND IMPLEMENTATION using Button + Text ---
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

    if (showDialog) {
        Dialog(
            showDialog = true,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Alert(
                title = {
                    Text(text = "Set Target HR", textAlign = TextAlign.Center)
                },
                negativeButton = {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) { Text("Cancel") }
                },
                positiveButton = {
                    Button(onClick = { onConfirm(selectedValue) }) { Text("OK") }
                }
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
                            onClick = { if (selectedValue > minTargetHr) selectedValue-- },
                            enabled = selectedValue > minTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) {
                            Text(text = "-")
                        }

                        Text(
                            text = "$selectedValue bpm",
                            style = MaterialTheme.typography.body1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(IntrinsicSize.Min)
                        )

                        Button(
                            onClick = { if (selectedValue < maxTargetHr) selectedValue++ },
                            enabled = selectedValue < maxTargetHr,
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize)
                        ) {
                            Text(text = "+")
                        }
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
    isPermissionGranted: Boolean = false
): MainUiState = remember(appState, consecutiveCount, lastDisplayedHr, targetHeartRate, isPermissionGranted) {
    MainUiState(
        appState = appState,
        consecutiveCount = consecutiveCount,
        lastDisplayedHr = lastDisplayedHr,
        targetHeartRate = targetHeartRate,
        isPermissionGranted = isPermissionGranted
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable private fun PreviewPermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false, lastDisplayedHr = 65)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable private fun PreviewPermissionGrantedMonitoring() {
    val previewState = rememberPreviewState(isPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1, targetHeartRate = 68)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable private fun PreviewGobbleTimePermissionGranted() {
    val previewState = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isPermissionGranted = true)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable private fun PreviewGobbleTimePermissionDenied() {
    val previewState = rememberPreviewState(appState = AppState.GOBBLE_TIME, consecutiveCount = 5, lastDisplayedHr = 65, isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SQUARE, showSystemUi = true)
@Composable private fun PreviewSquarePermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}, onResetMonitoring = {}, onUpdateTargetHeartRate = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable private fun PreviewTargetHeartRateDialog() {
    Gobbleoclockv2Theme {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            var showDialog by remember { mutableStateOf(true) }
            var targetHr by remember { mutableIntStateOf(65) }
            TargetHeartRateDialog(
                showDialog = showDialog,
                initialValue = targetHr,
                onDismiss = { showDialog = false },
                onConfirm = { newValue -> targetHr = newValue; showDialog = false }
            )
        }
    }
}
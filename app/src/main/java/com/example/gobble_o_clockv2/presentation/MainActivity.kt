package com.example.gobble_o_clockv2.presentation

import android.Manifest // Import Manifest
import android.content.Intent // Import Intent
import android.content.pm.PackageManager // Import PackageManager
import android.net.Uri // Import Uri
import android.os.Bundle
import android.provider.Settings // Import Settings
import android.util.Log // Import Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult // Import rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts // Import ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.* // Import foundational composables and state management
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext // Import LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat // Import ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.gobble_o_clockv2.R
import com.example.gobble_o_clockv2.data.AppState
import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme

class MainActivity : ComponentActivity() {

    private val logTag: String = "MainActivity" // Add log tag
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate called.")

        setContent {
            // Collect the UI state flow from the ViewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // --- Permission Handling ---
            val context = LocalContext.current
            var permissionRequested by remember { mutableStateOf(false) } // Track if request was launched

            // Permission Launcher
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { isGranted ->
                    Log.i(logTag, "BODY_SENSORS permission result: $isGranted")
                    viewModel.updatePermissionStatus(isGranted) // Update ViewModel with the result
                    permissionRequested = false // Reset tracker after result
                    if (!isGranted) {
                        // Optional: Handle persistent denial or show rationale if needed later
                        // Log.w(logTag, "Permission denied.")
                        // Consider showing a Snackbar or persistent message about limited functionality
                    } else {
                        Log.i(logTag, "Permission granted. Service should be able to start/monitor.")
                        // TODO: Potentially trigger service start here if not already running and state is MONITORING
                        // This interaction needs careful consideration in Batch 3.
                    }
                }
            )

            // Effect to check permission status when the composable launches or resumes
            // Using DisposableEffect to handle potential recompositions more robustly
            DisposableEffect(Unit) {
                Log.d(logTag, "DisposableEffect launched for permission check.")
                val checkPermission = {
                    val isGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BODY_SENSORS
                    ) == PackageManager.PERMISSION_GRANTED
                    Log.d(logTag, "Initial permission check status: $isGranted")
                    viewModel.updatePermissionStatus(isGranted) // Update VM immediately
                }
                checkPermission() // Initial check

                // Implement onResume-like behavior if needed (e.g., using LifecycleEventObserver)
                // For now, the initial check and launcher result cover the main cases.

                onDispose {
                    Log.d(logTag, "DisposableEffect disposed.")
                }
            }


            // Render the main UI
            WearApp(
                uiState = uiState,
                onRequestPermission = {
                    if (!permissionRequested) {
                        Log.i(logTag, "Requesting BODY_SENSORS permission...")
                        permissionRequested = true // Set tracker before launching
                        permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                    } else {
                        Log.d(logTag, "Permission request already in progress, ignoring button click.")
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(logTag, "onResume called. Re-checking permission status.")
        // Re-check permission status when activity resumes, as user might grant it in settings
        val isGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermissionStatus(isGranted)
    }
}

@Composable
fun WearApp(
    uiState: MainUiState,
    onRequestPermission: () -> Unit // Callback to trigger permission request
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
                // Pass permission request callback down
                StateDisplay(
                    uiState = uiState,
                    onRequestPermission = onRequestPermission
                )
            }
        }
    }
}

@Composable
fun StateDisplay(
    uiState: MainUiState,
    onRequestPermission: () -> Unit // Callback to trigger permission request
) {
    val context = LocalContext.current // Get context for opening settings

    Column(
        modifier = Modifier
            .fillMaxWidth() // Ensure column takes full width for centering button
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // *** MODIFIED LINE: Removed Alignment.CenterVertically ***
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Display Current Heart Rate
        Text(
            text = "HR: ${if (uiState.lastDisplayedHr > 0) uiState.lastDisplayedHr else "--"}",
            style = MaterialTheme.typography.display1,
            textAlign = TextAlign.Center
        )

        // Display Target Heart Rate
        Text(
            text = "Target: ${uiState.targetHeartRate} bpm",
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center
        )

        // Display Consecutive Count
        Text(
            text = "Count: ${uiState.consecutiveCount}",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center
        )

        // Display Application State
        Text(
            text = "Status: ${uiState.appState.name}",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            color = if (uiState.appState == AppState.GOBBLE_TIME) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp)) // Add space before permission info/button

        // Conditional Permission UI
        if (uiState.isPermissionGranted) {
            Text(
                text = "Sensor Permission: Granted",
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.secondary // Greenish color for granted
            )
        } else {
            // Show message and button if permission is needed
            Text(
                text = "Sensor permission needed for heart rate monitoring.",
                style = MaterialTheme.typography.caption1, // Slightly larger for emphasis
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.error // Red color for needed
            )
            Spacer(modifier = Modifier.height(4.dp))
            // Use Chip for standard Wear OS button style
            Chip(
                modifier = Modifier.padding(top = 4.dp),
                label = { Text("Grant Permission") },
                onClick = onRequestPermission, // Trigger the request
                colors = ChipDefaults.primaryChipColors()
            )
            // Optional: Add a button/link to open App Settings if permission is permanently denied
            Button( // Example: Button to go to settings
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.fromParts("package", context.packageName, null)
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(top = 4.dp),
                colors = ButtonDefaults.secondaryButtonColors() // Different color for settings
            ) {
                Text("Open Settings", style = MaterialTheme.typography.caption2)
            }
        }
    }
}


// --- Previews ---
// Helper function to create preview state easily
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
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}) }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewPermissionGranted() {
    val previewState = rememberPreviewState(isPermissionGranted = true, lastDisplayedHr = 72, consecutiveCount = 1)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}) }
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
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}) }
}

@Preview(device = WearDevices.SQUARE, showSystemUi = true)
@Composable
fun PreviewSquarePermissionNeeded() {
    val previewState = rememberPreviewState(isPermissionGranted = false)
    Gobbleoclockv2Theme { WearApp(uiState = previewState, onRequestPermission = {}) }
}
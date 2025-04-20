package com.example.gobble_o_clockv2.presentation

import android.content.res.Configuration // Import Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration // Import LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.gobble_o_clockv2.R
// import com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme // Keep commented/remove later

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            WearApp("Android")
        }
    }
}

@Composable
fun WearApp(greetingName: String) {
    // Restore Gobbleoclockv2Theme wrapper now that base MaterialTheme works
    // Note: If Gobbleoclockv2Theme is truly empty, remove it later. For now, restore.
    com.example.gobble_o_clockv2.presentation.theme.Gobbleoclockv2Theme { // <<< Restore original theme wrapper
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(greetingName = greetingName)
        }
    }
}

@Composable
fun Greeting(greetingName: String) {
    // Determine if the device is round using LocalConfiguration
    val configuration = LocalConfiguration.current
    val isRound = configuration.isScreenRound

    // Define the prefix based on screen shape
    val prefix = if (isRound) {
        "From the Round world,\n" // Add newline manually here
    } else {
        "From the Square world,\n" // Add newline manually here
    }

    // Load the simple format string
    val formatString = stringResource(R.string.greeting_format)
    // Combine prefix and formatted string
    val fullGreeting = prefix + String.format(formatString.replace("%1\$s", "%s"), greetingName) // Safer formatting

    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = fullGreeting // Use the manually constructed string
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun DefaultPreview() {
    WearApp("Preview Android")
}

// Optional Cleanup: Remove the now unused values-round/strings.xml if it only contained hello_world
// Optional Cleanup: Remove presentation/theme/Theme.kt if Gobbleoclockv2Theme remains empty
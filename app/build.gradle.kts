plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.gobble_o_clockv2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gobble_o_clockv2"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Core Wear OS / AndroidX / Jetpack
    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom)) // Use the BOM
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)

    // --- Wear Compose UI Libraries ---
    // CRITICAL: Ensure this pulls the WEAR material alias defined in TOML
    implementation(libs.compose.material)
    implementation(libs.compose.foundation) // Alias points to WEAR foundation
    implementation(libs.wear.tooling.preview)

    // --- Standard Material Libraries (for Icons) ---
    implementation(libs.compose.material.core)
    implementation(libs.compose.material.iconsExtended) // Provides Icons.Default.*

    // App Logic & Services Dependencies
    implementation(libs.health.services.client)
    implementation(libs.datastore.preferences)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // Testing Dependencies
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
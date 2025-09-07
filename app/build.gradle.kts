plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" // Match your Kotlin version
}

android {
    namespace = "com.cri5ti.bluetick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cri5ti.bluetick"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13" // match your Compose BOM
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
}

dependencies {
    // Compose BOM to align versions
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))

    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.wear.compose:compose-material:1.3.1")
    implementation("androidx.wear.compose:compose-foundation:1.3.1")


    // Core libraries
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")


    // Optional debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.1")
    implementation("androidx.wear:wear-ongoing:1.0.0")
}
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: android/keystore.properties (never committed) holds the key details.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.laradars.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.laradars.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.9"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "MANIFEST_URL", "\"https://raw.githubusercontent.com/Bastientiger/la-radars/main/data/manifest.json\"")
        }
        debug {
            // 10.0.2.2 is the development machine as seen from the Android emulator.
            buildConfigField("String", "MANIFEST_URL", "\"http://10.0.2.2:8765/manifest.json\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    // "In vehicle" detection for auto-start.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // OpenStreetMap map view (no API key needed).
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    // The android.jar used by local tests has empty stubs for org.json; use the real library.
    testImplementation("org.json:json:20240303")
}

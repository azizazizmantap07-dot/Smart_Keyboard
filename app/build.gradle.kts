plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

import java.util.Properties
import java.io.FileInputStream

// Load keystore.properties if present (for local signed builds)
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.smartkeyboard.ime"

    compileSdk = 35

    defaultConfig {
        applicationId = "com.smartkeyboard.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-framework"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // Local build: read from keystore.properties
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            } else {
                // CI build: read from environment variables / GitHub Secrets
                keyAlias = System.getenv("KEYSTORE_ALIAS")?.takeIf { it.isNotBlank() } ?: "smartkeyboard"
                keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                val keystorePath = System.getenv("KEYSTORE_PATH")
                if (keystorePath != null) {
                    storeFile = rootProject.file(keystorePath)
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only apply signing if we have a valid storeFile
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // viewBinding disabled: UI is built programmatically (no layout XML)
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Coroutines for async auto-correct & translation
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Room — personal adaptive dictionary
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Google ML Kit (translation only)
    implementation("com.google.mlkit:translate:17.0.3")

    // ONNX Runtime for on-device TinyChar-BiGRU auto-correct scorer (INT8, bundled in assets)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

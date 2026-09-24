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
        versionCode = 2
        versionName = "1.1.0-symspell-tflite"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // ARM-only: covers virtually all real Android devices (incl. Infinix X6891 / Helio G99).
            // x86_64 dropped — that's emulator/Chromebook territory, not a real target here.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DTFLITE_AVAILABLE=OFF" // enable when TFLite C++ prebuilts are wired
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            } else {
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Compressed native libs: smaller download/storage size.
            // (Trade-off vs uncompressed is faster load time; negligible for this app's small .so files.)
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true // still produce one universal APK for compatibility/manual installs
        }
    }

    // NDK side-by-side; GitHub Actions installs via sdkmanager
    ndkVersion = "26.1.10909125"
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

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

    // Note: next-word TFLite inference runs entirely through the native C++
    // engine (see app/src/main/cpp, CMake TFLITE_AVAILABLE) via JNI — no
    // TFLite Java/Kotlin library is needed on this side.

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

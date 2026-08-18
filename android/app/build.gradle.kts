import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load keystore properties from a local-only file (NOT committed).
// Same shared-keystore pattern as ReadAloudAI/ReadAloud Voice so
// Play Console treats this as the same publisher identity.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.listenai.describe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.listenai.describe"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.1"

        // M11: added x86_64 alongside arm64-v8a. Unblocks emulator-based
        // testing (arm64-only meant every native-path check in this repo
        // had to happen on real hardware) and broader Play Store device
        // coverage (some Chromebooks/tablets are x86_64). armeabi-v7a
        // (32-bit) is intentionally still excluded — no test devices to
        // validate against and 32-bit Android's install base keeps
        // shrinking.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Match llama.cpp's required minimum (3.22 since they
                // moved off ancient CMake). Same toolchain as the rest
                // of the Android NDK build.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile     = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias      = keystoreProperties["keyAlias"] as String
                keyPassword   = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Image loading — we display the shared image while inference runs
    implementation("io.coil-kt:coil-compose:2.5.0")

    // WorkManager — runs the GGUF download as a foreground-promoted
    // background task so it survives app backgrounding, process death,
    // network changes, and screen-off (same pattern as ReadAloud Voice's
    // Kokoro downloader).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // OkHttp — used inside the download worker for byte-range resumable
    // downloads. Stream-to-disk so we never load the 868MB+ files into
    // RAM.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // M10 translation: ML Kit's on-device Translation API, not a custom
    // GGUF model. Translation models (NLLB/M2M100-class) are
    // encoder-decoder, an architecturally different shape from the
    // decoder-only causal-LM loop describe_jni.cpp is built around —
    // doing this in llama.cpp would mean a second from-scratch native
    // inference engine for a feature that isn't the core differentiator.
    // ML Kit downloads a language pack once and works fully offline
    // after, so it doesn't compromise the app's offline/privacy story.
    implementation("com.google.mlkit:translate:17.0.3")
    // Bridges ML Kit's Task-based API into suspend functions (Task.await()).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.listenai.describe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.listenai.describe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // arm64-v8a only for now — Day-5+ ships native llama.cpp .so files
        // and we don't intend to support x86 emulators. Matches ReadAloud
        // Voice's NDK constraint.
        ndk {
            abiFilters += listOf("arm64-v8a")
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
        // Day-4 ships unsigned debug only; the signing config matching
        // ReadAloud Voice's keystore comes in Day-6 when we wire fastlane.
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed for now so adb install works; swap to a proper
            // keystore when we set up Play uploads.
            signingConfig = signingConfigs.getByName("debug")
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

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

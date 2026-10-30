plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.rexpocket"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.rexpocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.0.6"

        // libbox is built for arm64 only, so the APK must not claim the other ABIs either.
        ndk.abiFilters += "arm64-v8a"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal build: sign with the debug key so a sideloaded APK can be updated
            // without keeping a private keystore in the repository.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // libbox is a 57 MB uncompressed arm64 library. Stored as-is it would be the whole APK,
        // so let the installer unpack it instead of keeping it uncompressed for mmap loading.
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// libghostty-android is published against a preview SDK 37 that the public SDK manager does not
// offer yet. Its AAR metadata therefore demands compileSdk 37; the bytecode itself runs from
// API 23 up and guards newer APIs, so the metadata check is what we drop here, not the library.
tasks.matching { it.name.endsWith("AarMetadata") }.configureEach { enabled = false }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.sagernet:libghostty-android:0.1.0-alpha01")
    implementation("io.github.sagernet:libghostty-android-compose-legacy:0.1.0-alpha01")
    // Built from sing-box 1.14.4 with the Tailscale endpoint, see tools/build-libbox.sh.
    implementation(files("libs/libbox.aar"))
}

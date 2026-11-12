import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every release is signed with one key, so a build made on any computer installs over the last one.
// The key is not in the repository: point these at the keystore (Gradle properties, environment or
// local.properties; a relative path is from the project root).
//   REXPOCKET_KEYSTORE, REXPOCKET_KEYSTORE_PASSWORD, REXPOCKET_KEY_ALIAS, REXPOCKET_KEY_PASSWORD
// A release build stops when the key is not set, instead of falling back to the debug key.
// local.properties is not tracked, so the key's location and passwords can live there on a build machine.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun signingSetting(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)

android {
    namespace = "me.leaver.rexpocket"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "me.leaver.rexpocket"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.1.0"

        // The tailnet and Ghostty libraries are built for arm64 only, so the APK must not claim the other ABIs either.
        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            signingSetting("REXPOCKET_KEYSTORE")?.let { storeFile = rootProject.file(it) }
            storePassword = signingSetting("REXPOCKET_KEYSTORE_PASSWORD")
            keyAlias = signingSetting("REXPOCKET_KEY_ALIAS")
            keyPassword = signingSetting("REXPOCKET_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    testOptions {
        // The unit tests run the real libghostty-vt on this computer; the Android classes the
        // session touches (a Handler for its debounce) only need to exist.
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        // The native libraries stay uncompressed and 16 KB page-aligned in the APK, and are mapped
        // straight from it. That is what Android 15 checks on a device with 16 KB pages: a
        // compressed library cannot be checked at all, and a debuggable build says so at launch.
        // The APK is larger to download (about 31 MB instead of 16 MB), smaller once installed,
        // since nothing is unpacked beside it.
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// libghostty-vt, the official Ghostty terminal library, built from the revision pinned in
// native/ghostty/source.json together with the JNI layer beside it: see tools/build-ghostty.py.
// The library lands in src/main/jniLibs, which is not tracked.
val buildGhostty by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libghostty_jni.so from the pinned official Ghostty source."
    commandLine("python3", rootProject.file("tools/build-ghostty.py").absolutePath)
    inputs.dir(rootProject.file("native/ghostty"))
    inputs.file(rootProject.file("tools/build-ghostty.py"))
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libghostty_jni.so"))
}
tasks.named("preBuild") { dependsOn(buildGhostty) }

// The same sources built for this computer: the JVM unit tests load it to run the real libghostty-vt.
val hostLibraryDir = rootProject.file("native/.build/host")
val buildGhosttyHost by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libghostty_jni for this computer, for the JVM unit tests."
    commandLine(
        "python3", rootProject.file("tools/build-ghostty.py").absolutePath,
        "--host", "--jdk", System.getProperty("java.home"),
    )
    inputs.dir(rootProject.file("native/ghostty"))
    inputs.file(rootProject.file("tools/build-ghostty.py"))
    outputs.dir(hostLibraryDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(buildGhosttyHost)
    // A new libghostty is a reason to run them again, not just a changed test.
    inputs.dir(hostLibraryDir)
    systemProperty("java.library.path", hostLibraryDir.absolutePath)
}

// A release build is only made with the release key: with no key set it stops before anything is
// signed, instead of producing an APK that cannot update the installed app.
gradle.taskGraph.whenReady {
    if (allTasks.none { it.project == project && Regex("(package|bundle)Release").matches(it.name) }) return@whenReady
    val release = android.signingConfigs.getByName("release")
    if (release.storeFile == null || release.storePassword == null || release.keyAlias == null || release.keyPassword == null) {
        throw GradleException("Release builds need REXPOCKET_KEYSTORE, REXPOCKET_KEYSTORE_PASSWORD, REXPOCKET_KEY_ALIAS and REXPOCKET_KEY_PASSWORD (Gradle properties, environment or local.properties); see the README.")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The official tailscale.com tsnet library behind the tailnet/ Go module, see tools/build-tailnet.sh.
    implementation(files("libs/tailnet.aar"))
}

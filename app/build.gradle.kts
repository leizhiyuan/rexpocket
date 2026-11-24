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
        versionCode = 28
        versionName = "0.1.3"

        // The tailnet and Ghostty libraries are built for arm64 only, so the APK must not claim the other ABIs either.
        // The device tests on an x86_64 emulator build both libraries for x86_64 and pass
        // -Prexpocket.abi=x86_64 (see .github/workflows/device-test.yml); nothing else sets it.
        ndk.abiFilters += (findProperty("rexpocket.abi") as String?) ?: "arm64-v8a"

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

    // CI's phone builds: -Prexpocket.debugKeystore names a debug key kept between runs, so each debug
    // APK installs over the last. Without it a debug build signs with this computer's own debug key.
    (findProperty("rexpocket.debugKeystore") as String?)?.let { path ->
        signingConfigs.create("ciDebug") {
            storeFile = file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("ciDebug")
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
        // The Compose screen tests on the JVM (Robolectric, Roborazzi) read the app's resources.
        // See docs/design/testing.md.
        unitTests.isIncludeAndroidResources = true
    }

    // The screen tests in src/sharedTest run twice from one source: on the JVM (Robolectric) with
    // the unit tests, quick to run on any computer, and on the emulator with the instrumented ones.
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
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

// Robolectric's Android for the JVM screen tests comes through Gradle's own repositories, cached
// like any other dependency, instead of Robolectric fetching it from Maven Central as the tests
// run. One Android version: the one the tests' @Config names. See docs/design/testing.md.
val robolectricAndroid: Configuration by configurations.creating { isTransitive = false }
val robolectricJars by tasks.registering(Sync::class) {
    from(robolectricAndroid)
    into(layout.buildDirectory.dir("robolectric"))
}
tasks.withType<Test>().configureEach {
    dependsOn(robolectricJars)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric").get().asFile.absolutePath)
    // Roborazzi writes each screenshot the tests take to build/outputs/roborazzi, for review.
    systemProperty("roborazzi.test.record", "true")
    // Real rendering, so screenshots show the screen and layout is measured as on a phone.
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
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

// Which tailnet library to link: the Tailscale-only one unless Netbird is asked for.
val withNetbird = (findProperty("rexpocket.netbird") as String?).toBoolean()
val tailnetLibrary = rootProject.file(if (withNetbird) "app/libs/tailnet-netbird.aar" else "app/libs/tailnet.aar")
require(tailnetLibrary.isFile) { "${tailnetLibrary.name} is missing: build it with tools/build-tailnet.sh" }

// The Netbird add-on (:netbirdaddon, a separate app) is offered by the default build only; the build
// with Netbird embedded has no use for it. Its client lives in src/addon, with its unit and device
// tests in src/testAddon and src/androidTestAddon; src/embedded is what the embedded build has in
// its place. See docs/design/tailnet-backends.md.
val withAddon = !withNetbird
android.sourceSets {
    getByName("main").java.srcDir(if (withAddon) "src/addon/java" else "src/embedded/java")
    if (withAddon) {
        getByName("test").java.srcDir("src/testAddon/java")
        getByName("androidTest").java.srcDir("src/androidTestAddon/java")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Screens driven and captured on the JVM: see docs/design/testing.md.
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    robolectricAndroid("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.40.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.40.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // The terminal screen's own controls, driven on a device: see FullScreenTest.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // The fake Rex the scenarios in core run on, for the screen tests that connect: see LaunchTest.
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))
    // The empty activity the Compose test rule hosts its content in is declared in
    // src/debug/AndroidManifest.xml, not by ui-test-manifest: that one is exported, and the debug
    // APK is the one people install.
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // What the window is wide enough for: one column, or a list beside its pane.
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.activity:activity-compose:1.9.3")
    // The home screen widget: what needs the person, what is done and what works, per host.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // ExploreByTouchHelper: the terminal's lines as virtual views for TalkBack.
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The tailnet library behind the tailnet/ Go module, see tools/build-tailnet.sh: the official
    // tailscale.com tsnet library, and Netbird's embedded client when it is built in. Netbird adds
    // about 30 MB to the APK, so it is a build choice: -Prexpocket.netbird=true links the library built
    // with REXPOCKET_NETBIRD=1 instead.
    implementation(files(tailnetLibrary))
    if (withAddon) implementation(project(":netbirdaddon-api"))
}

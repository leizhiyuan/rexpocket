import java.util.Properties

// The Netbird add-on: a separate app, me.leaver.rexpocket.netbird, that runs a Netbird node for
// RexPocket's default build and dials through it over a bound service. It is signed with the same
// key as RexPocket, which is what lets RexPocket bind it. See docs/design/tailnet-backends.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The same key settings as the app's (see app/build.gradle.kts): the add-on must be signed by the
// key RexPocket is signed by, or the signature permission keeps RexPocket out.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun signingSetting(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name) ?: localProperties.getProperty(name)

// The add-on carries RexPocket's version, so a release's add-on and app match by name and the app
// can tell an add-on older than itself (docs/design/tailnet-backends.md, "Versions").
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)?.groupValues?.get(1) ?: "0"
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)?.groupValues?.get(1)?.toInt() ?: 1

// What the add-on speaks (NetbirdAddon.VERSION and REVISION). Only the device tests set these, to
// build an add-on of an older revision or another version and check how RexPocket meets it.
val addonVersion = (findProperty("rexpocket.addonVersion") as String?)?.toInt()
val addonRevision = (findProperty("rexpocket.addonRevision") as String?)?.toInt()

android {
    namespace = "me.leaver.rexpocket.netbird"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "me.leaver.rexpocket.netbird"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("int", "ADDON_VERSION", addonVersion?.toString() ?: "me.leaver.rexpocket.netbird.api.NetbirdAddon.VERSION")
        buildConfigField("int", "ADDON_REVISION", addonRevision?.toString() ?: "me.leaver.rexpocket.netbird.api.NetbirdAddon.REVISION")
        // The revision RexPocket reads without binding: the service's meta-data.
        manifestPlaceholders["addonRevision"] = (addonRevision ?: 2).toString()
        // As the app: arm64 only, unless the device tests ask for x86_64.
        ndk.abiFilters += (findProperty("rexpocket.abi") as String?) ?: "arm64-v8a"
    }

    signingConfigs {
        create("release") {
            signingSetting("REXPOCKET_KEYSTORE")?.let { storeFile = rootProject.file(it) }
            storePassword = signingSetting("REXPOCKET_KEYSTORE_PASSWORD")
            keyAlias = signingSetting("REXPOCKET_KEY_ALIAS")
            keyPassword = signingSetting("REXPOCKET_KEY_PASSWORD")
        }
    }

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
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Stored and 16 KB aligned, as the app's (see app/build.gradle.kts).
    packaging {
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Netbird node alone, built by REXPOCKET_NETBIRD=addon tools/build-tailnet.sh. It is not checked
// in (about 13 MB), so a checkout without it still configures: only building the add-on needs it.
val netbirdLibrary = file("libs/tailnet-netbird-addon.aar")
val checkNetbirdLibrary by tasks.registering {
    doLast {
        if (!netbirdLibrary.isFile) {
            throw GradleException("${netbirdLibrary.name} is missing: build it with REXPOCKET_NETBIRD=addon tools/build-tailnet.sh")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNetbirdLibrary) }

dependencies {
    implementation(project(":netbirdaddon-api"))
    implementation(files(netbirdLibrary))
    testImplementation("junit:junit:4.13.2")
}

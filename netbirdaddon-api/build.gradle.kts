// The interface between RexPocket and the Netbird add-on: the AIDL of the bound service, the names
// and version both sides check, and the signature permission that guards it. RexPocket's default
// build and the add-on both depend on it; the build with Netbird embedded does not.
// See docs/design/tailnet-backends.md.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.leaver.rexpocket.netbird.api"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    // ScriptedRex and Desk live in src/testFixtures, so the app's screen tests can run on the same
    // fake Rex as the scenarios here. See docs/design/testing.md.
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
    // The SSH transport: JSch for the protocol and the pure-Java eddsa for Ed25519, which Android's
    // own security providers do not offer to JSch.
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // A real SSH server for the host dialog's Test connection: host keys, sign-in and the exec channel.
    testImplementation("org.apache.sshd:sshd-core:2.14.0")
    testFixturesApi("com.squareup.okhttp3:mockwebserver:4.12.0")
    testFixturesImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testFixturesImplementation("org.json:json:20240303")
}

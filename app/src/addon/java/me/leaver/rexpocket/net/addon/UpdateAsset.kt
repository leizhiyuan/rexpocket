package me.leaver.rexpocket.net.addon

/**
 * Which APK of a release the updater installs in this build, the one with Netbird through the
 * add-on: rexpocket.apk, never the embedded build (rexpocket-netbird.apk), which would replace this
 * one, nor the add-on itself (rexpocket-netbird-addon.apk), which is a separate app.
 */
fun isUpdateAsset(name: String): Boolean = !name.contains("netbird", ignoreCase = true)

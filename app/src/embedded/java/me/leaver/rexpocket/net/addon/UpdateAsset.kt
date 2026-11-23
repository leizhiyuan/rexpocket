package me.leaver.rexpocket.net.addon

/**
 * Which APK of a release the updater installs in this build, the one with Netbird embedded:
 * rexpocket-netbird.apk, never rexpocket.apk, which would drop Netbird, nor the add-on
 * (rexpocket-netbird-addon.apk), which this build has no use for.
 */
fun isUpdateAsset(name: String): Boolean =
    name.contains("netbird", ignoreCase = true) && !name.contains("addon", ignoreCase = true) &&
        !name.contains("add-on", ignoreCase = true)

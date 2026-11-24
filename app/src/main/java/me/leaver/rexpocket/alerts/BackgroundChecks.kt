package me.leaver.rexpocket.alerts

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * What Android lets background mode do, as Settings › Background's checklist shows it, and where
 * each is fixed. The app is installed from its GitHub releases, not a store, so it asks for the
 * battery exemption directly (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, the dialog with Allow), as
 * ntfy's own builds do; Google Play restricts that permission to some kinds of app, which does not
 * apply here. See docs/design/notifications.md, "Background mode".
 */
data class BackgroundChecks(
    /** Notifications are on for the app (and, on Android 13+, allowed). */
    val notifications: Boolean,
    /** The app is exempt from battery optimization: Doze lets its network through, restarts are allowed. */
    val battery: Boolean,
    /** "Restricted" battery use (Android 9+): Android stops the app's background work altogether. */
    val restricted: Boolean,
    /** Data Saver keeps the app off mobile data in the background. */
    val dataSaver: Boolean,
    /**
     * The watch notification can show: notifications allowed and its channel not switched off.
     * When it cannot, background mode still runs, with its notification and buttons hidden.
     */
    val watch: Boolean = notifications,
) {
    val allGood: Boolean get() = notifications && battery && !restricted && !dataSaver

    companion object {
        fun read(context: Context): BackgroundChecks {
            val power = context.getSystemService(PowerManager::class.java)
            val activity = context.getSystemService(ActivityManager::class.java)
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return BackgroundChecks(
                notifications = Alerts.allowed(context),
                battery = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
                restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activity?.isBackgroundRestricted == true,
                dataSaver = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                watch = !Alerts.watchHidden(context),
            )
        }

        /** Asks for the battery exemption: Android's own dialog, or its list where there is none. */
        @SuppressLint("BatteryLife")
        fun askBattery(context: Context): Boolean = open(
            context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context),
        )

        /** The app's page in Android's settings, where its battery use is set to Unrestricted. */
        fun appDetails(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

        /** Data Saver's page for the app: unrestricted data. */
        fun openDataSaver(context: Context): Boolean = open(
            context,
            Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}")),
            appDetails(context),
        )

        /** Opens the first of [pages] this phone has; some makers leave a settings page out. */
        fun open(context: Context, vararg pages: Intent): Boolean = pages.any { page ->
            runCatching { context.startActivity(page.newTask()) }.isSuccess
        }

        /** dontkillmyapp.com's page for this phone's maker, which lists what each one does besides Android. */
        fun oemTips(): Intent {
            val maker = Build.MANUFACTURER.lowercase().replace(' ', '-')
            val page = if (maker in KNOWN_MAKERS) maker else ""
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$page"))
        }

        private fun Intent.newTask() = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The makers dontkillmyapp.com has a page for, by the name Android reports. */
        private val KNOWN_MAKERS = setOf(
            "samsung", "xiaomi", "huawei", "honor", "oneplus", "oppo", "vivo", "realme", "meizu", "asus",
            "sony", "nokia", "motorola", "lenovo", "google", "wiko", "htc", "blackview", "tecno", "unihertz",
        )
    }
}

package me.leaver.rexpocket.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.diagnostics.Diagnostics

/**
 * Starts background mode again after the phone restarts or the app is updated, when it was on.
 * Both are moments Android 12 and later let an app start a foreground service from the background
 * (BOOT_COMPLETED and MY_PACKAGE_REPLACED are on its list of exemptions), and the special-use type
 * the watch has is not one of the types Android 15 keeps from starting at boot.
 *
 * Not direct-boot aware: the hosts, their keys and the setting itself are in credential-encrypted
 * storage, which is closed until the person unlocks the phone once, so the watch waits for
 * BOOT_COMPLETED, which comes after that unlock, rather than LOCKED_BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // Never before the first unlock, should a locked boot reach this anyway: nothing is readable.
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        if (!unlocked) return
        val on = HostStore(context).alerts()
        Diagnostics.event("RexPocketWatch", "${intent.action?.substringAfterLast('.')}: background ${if (on) "on, starting" else "off"}")
        if (on) Alerts.restore(context.applicationContext)
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // Some phones' fast boot sends this instead of BOOT_COMPLETED.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}

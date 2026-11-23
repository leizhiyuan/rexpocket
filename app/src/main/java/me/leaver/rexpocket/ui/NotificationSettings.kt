package me.leaver.rexpocket.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.core.AlertKind
import me.leaver.rexpocket.core.AlertPrefs

/**
 * Settings › Notifications: Background alerts (the connection kept up while the app is away), what
 * Android allows, a switch for each kind of alert, and each channel's own page in Android's
 * settings for its sound and vibration. See docs/design/notifications.md.
 */
@Composable
internal fun NotificationsSheet(
    onDismiss: () -> Unit,
    alerts: Boolean,
    /** Turns Background alerts on or off; on Android 13 and later, turning them on asks for the permission first. */
    onAlerts: () -> Unit,
    prefs: AlertPrefs,
    onPrefs: (AlertPrefs) -> Unit,
) {
    val context = LocalContext.current
    // Read again whenever the app comes back: the person may have changed them in Android's settings.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val permitted = remember(resumed, alerts) { Alerts.allowed(context) }
    val blocked = remember(resumed) { AlertKind.entries.associateWith { Alerts.channelBlocked(context, it) } }
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    fun state(value: Boolean) = if (value) on else off
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.notifications_title)) {
        Group {
            SheetItem(
                label = stringResource(R.string.alerts_title),
                icon = R.drawable.ic_notifications,
                supporting = stringResource(R.string.notifications_background_body),
                detail = state(alerts),
                keepOpen = true,
                onClick = onAlerts,
            )
            SheetItem(
                label = stringResource(R.string.notifications_android),
                icon = R.drawable.ic_lock,
                supporting = if (permitted) null else stringResource(R.string.notifications_not_allowed_body),
                detail = stringResource(if (permitted) R.string.notifications_allowed else R.string.notifications_not_allowed),
                keepOpen = true,
                opens = true,
                onClick = { context.startActivity(Alerts.systemSettings(context)) },
            )
        }
        Group(stringResource(R.string.notifications_group_when), icons = false) {
            // Each switch stays usable with alerts off: the choice is kept for when they come on.
            SheetItem(stringResource(R.string.notifications_waiting), detail = state(prefs.waiting), keepOpen = true) {
                onPrefs(prefs.copy(waiting = !prefs.waiting))
            }
            SheetItem(stringResource(R.string.notifications_failed), detail = state(prefs.failed), keepOpen = true) {
                onPrefs(prefs.copy(failed = !prefs.failed))
            }
            SheetItem(stringResource(R.string.notifications_done), detail = state(prefs.done), keepOpen = true) {
                onPrefs(prefs.copy(done = !prefs.done))
            }
            SheetItem(stringResource(R.string.notifications_messages), detail = state(prefs.messages), keepOpen = true) {
                onPrefs(prefs.copy(messages = !prefs.messages))
            }
            SheetItem(stringResource(R.string.notifications_bells), detail = state(prefs.bells), keepOpen = true) {
                onPrefs(prefs.copy(bells = !prefs.bells))
            }
        }
        Group(stringResource(R.string.notifications_group_screen), icons = false) {
            SheetItem(stringResource(R.string.notifications_vibrate), detail = state(prefs.vibrate), keepOpen = true) {
                onPrefs(prefs.copy(vibrate = !prefs.vibrate))
            }
        }
        // Sound, vibration, the lock screen and Do Not Disturb are Android's, per channel.
        Group(stringResource(R.string.notifications_group_channels), icons = false) {
            for ((kind, label) in listOf(
                AlertKind.Waiting to R.string.alerts_channel_attention,
                AlertKind.Done to R.string.alerts_channel_done,
                AlertKind.Message to R.string.alerts_channel_messages,
            )) {
                SheetItem(
                    label = stringResource(label),
                    detail = if (blocked[kind] == true) stringResource(R.string.notifications_channel_off) else null,
                    keepOpen = true,
                    opens = true,
                ) { context.startActivity(Alerts.systemSettings(context, kind)) }
            }
        }
    }
}

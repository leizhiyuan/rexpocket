package me.leaver.rexpocket.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.alerts.BackgroundChecks
import me.leaver.rexpocket.core.AlertEvent
import me.leaver.rexpocket.core.AlertKind
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.BackgroundHosts
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.on
import me.leaver.rexpocket.core.with
import me.leaver.rexpocket.data.SavedHost

/** The Notifications page's switches, each with its label. */
internal val EVENTS = listOf(
    AlertEvent.Waiting to R.string.notifications_waiting,
    AlertEvent.Failed to R.string.notifications_failed,
    AlertEvent.Done to R.string.notifications_done,
    AlertEvent.Message to R.string.notifications_messages,
    AlertEvent.Bell to R.string.notifications_bells,
)

/**
 * Settings › Background and notifications: background mode (the connections kept up while the app
 * is away, past a swipe from recents and a restart), a checklist of what Android lets it do with a
 * Fix for each, each host's page (kept connected, always connected, its own notifications), the
 * switch for each kind of alert every host starts from, and each channel's own page in Android's
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
    hosts: List<SavedHost> = emptyList(),
    background: BackgroundHosts = BackgroundHosts(),
    /** The tailnets joined, each with its page like a host's. */
    tailnets: List<TailnetView> = emptyList(),
    /** Opens a host's or a tailnet's page. */
    onPage: (Connectable) -> Unit = {},
) {
    val context = LocalContext.current
    // Read again whenever the app comes back: the person may have changed them in Android's settings.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val checks = remember(resumed, alerts) { BackgroundChecks.read(context) }
    val blocked = remember(resumed) { AlertKind.entries.associateWith { Alerts.channelBlocked(context, it) } }
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    val fix = stringResource(R.string.bg_fix)
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
        }
        // What Android lets background mode do, and a Fix for each that is not as it should be.
        Group(stringResource(R.string.bg_group_checks)) {
            SheetItem(
                label = stringResource(R.string.notifications_android),
                icon = R.drawable.ic_lock,
                supporting = if (checks.notifications) null else stringResource(R.string.notifications_not_allowed_body),
                detail = if (checks.notifications) stringResource(R.string.notifications_allowed) else fix,
                keepOpen = true,
                opens = true,
                onClick = { context.startActivity(Alerts.systemSettings(context)) },
            )
            SheetItem(
                label = stringResource(R.string.bg_battery),
                icon = R.drawable.ic_timer,
                supporting = if (checks.battery) null else stringResource(R.string.bg_battery_body),
                detail = if (checks.battery) stringResource(R.string.bg_battery_ok) else fix,
                keepOpen = true,
                opens = true,
                onClick = { if (checks.battery) BackgroundChecks.open(context, BackgroundChecks.appDetails(context)) else BackgroundChecks.askBattery(context) },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SheetItem(
                    label = stringResource(R.string.bg_restricted),
                    icon = R.drawable.ic_block,
                    supporting = if (checks.restricted) stringResource(R.string.bg_restricted_body) else null,
                    detail = if (checks.restricted) fix else stringResource(R.string.bg_allowed),
                    keepOpen = true,
                    opens = true,
                    onClick = { BackgroundChecks.open(context, BackgroundChecks.appDetails(context)) },
                )
            }
            SheetItem(
                label = stringResource(R.string.bg_data),
                icon = R.drawable.ic_swap,
                supporting = if (checks.dataSaver) stringResource(R.string.bg_data_body) else null,
                detail = if (checks.dataSaver) fix else stringResource(R.string.bg_allowed),
                keepOpen = true,
                opens = true,
                onClick = { BackgroundChecks.openDataSaver(context) },
            )
            SheetItem(
                label = stringResource(R.string.bg_oem),
                icon = R.drawable.ic_open_in_new,
                supporting = stringResource(R.string.bg_oem_body),
                keepOpen = true,
                opens = true,
                onClick = { BackgroundChecks.open(context, BackgroundChecks.oemTips()) },
            )
        }
        // Each tailnet and each host has its page, the same for both: what it does in the background.
        if (tailnets.isNotEmpty()) {
            Group(stringResource(R.string.bg_group_tailnets)) {
                for (t in tailnets) {
                    SheetItem(
                        label = t.name,
                        icon = R.drawable.ic_tailnet,
                        detail = stringResource(tailnetKeepWord(t.policy.keep)),
                        opens = true,
                    ) { onPage(Connectable.tailnet(t.id)) }
                }
            }
        }
        if (hosts.isNotEmpty()) {
            Group(stringResource(R.string.bg_group_hosts)) {
                for (host in hosts) {
                    SheetItem(
                        label = host.label.ifBlank { host.host },
                        icon = R.drawable.ic_host,
                        detail = hostSummary(background.of(host.id)),
                        opens = true,
                    ) { onPage(Connectable.host(host.id)) }
                }
            }
        }
        Group(stringResource(R.string.notifications_group_when), icons = false) {
            // Each switch stays usable with alerts off: the choice is kept for when they come on.
            for ((event, label) in EVENTS) {
                SheetItem(stringResource(label), detail = state(prefs.on(event)), keepOpen = true) {
                    onPrefs(prefs.with(event, !prefs.on(event)))
                }
            }
        }
        Group(stringResource(R.string.notifications_group_screen), icons = false) {
            SheetItem(stringResource(R.string.notifications_vibrate), detail = state(prefs.vibrate), keepOpen = true) {
                onPrefs(prefs.copy(vibrate = !prefs.vibrate))
            }
        }
        // A program's progress bar as a notification of its own while away: off by default, as it
        // is a notification that stays while the work goes on.
        Group(stringResource(R.string.notifications_group_progress), icons = false) {
            SheetItem(
                stringResource(R.string.notifications_progress),
                supporting = stringResource(R.string.notifications_progress_body),
                detail = state(prefs.progress),
                keepOpen = true,
            ) { onPrefs(prefs.copy(progress = !prefs.progress)) }
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

/** A host's row on the page: whether it stays connected, and whether it is silent. */
@Composable
internal fun hostSummary(choices: HostBackground): String {
    val stays = stringResource(
        when {
            choices.always -> R.string.bg_always
            choices.stay -> R.string.bg_host_kept
            else -> R.string.bg_host_not_kept
        },
    )
    return if (choices.notify) stays else stays + " · " + stringResource(R.string.bg_host_silent)
}

/** A tailnet's background choice in a word, for its row. */
internal fun tailnetKeepWord(keep: BackgroundKeep): Int = when (keep) {
    BackgroundKeep.Follow -> R.string.bg_keep_tailnet_follow
    BackgroundKeep.Always -> R.string.bg_keep_tailnet_always
    BackgroundKeep.Off -> R.string.bg_keep_tailnet_off
}

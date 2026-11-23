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
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.on
import me.leaver.rexpocket.core.with
import me.leaver.rexpocket.data.SavedHost

/** The Notifications page's switches, each with its label. */
private val EVENTS = listOf(
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
    onHostBackground: (String, HostBackground) -> Unit = { _, _ -> },
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
    var hostPage by rememberSaveable { mutableStateOf<String?>(null) }
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    val fix = stringResource(R.string.bg_fix)
    fun state(value: Boolean) = if (value) on else off

    val pageHost = hostPage?.let { id -> hosts.firstOrNull { it.id == id } }
    if (pageHost != null) {
        HostBackgroundSheet(
            host = pageHost,
            choices = background.of(pageHost.id),
            defaults = prefs,
            onChoices = { onHostBackground(pageHost.id, it) },
            onBack = { hostPage = null },
            onDismiss = onDismiss,
        )
        return
    }
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
        if (hosts.isNotEmpty()) {
            Group(stringResource(R.string.bg_group_hosts)) {
                for (host in hosts) {
                    SheetItem(
                        label = host.label.ifBlank { host.host },
                        icon = R.drawable.ic_host,
                        detail = hostSummary(background.of(host.id)),
                        keepOpen = true,
                        opens = true,
                    ) { hostPage = host.id }
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
private fun hostSummary(choices: HostBackground): String {
    val stays = stringResource(
        when {
            choices.always -> R.string.bg_always
            choices.stay -> R.string.bg_host_kept
            else -> R.string.bg_host_not_kept
        },
    )
    return if (choices.notify) stays else stays + " · " + stringResource(R.string.bg_host_silent)
}

/**
 * One host's page: kept connected in the background, always connected, and its notifications,
 * each kind following the page's default until it is set here.
 */
@Composable
private fun HostBackgroundSheet(
    host: SavedHost,
    choices: HostBackground,
    defaults: AlertPrefs,
    onChoices: (HostBackground) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    fun state(value: Boolean) = if (value) on else off
    ActionSheet(onDismiss = onDismiss, title = host.label.ifBlank { host.host }, onBack = onBack) {
        Group {
            SheetItem(
                label = stringResource(R.string.bg_stay),
                supporting = stringResource(R.string.bg_stay_body),
                // Always connected keeps it whatever this says.
                detail = state(choices.kept),
                enabled = !choices.always,
                keepOpen = true,
            ) { onChoices(choices.copy(stay = !choices.stay)) }
            SheetItem(
                label = stringResource(R.string.bg_always),
                supporting = stringResource(R.string.bg_always_body),
                detail = state(choices.always),
                keepOpen = true,
            ) { onChoices(choices.copy(always = !choices.always)) }
            SheetItem(
                label = stringResource(R.string.bg_notify),
                detail = state(choices.notify),
                keepOpen = true,
            ) { onChoices(choices.copy(notify = !choices.notify)) }
        }
        Group(stringResource(R.string.notifications_group_when), icons = false) {
            val effective = choices.copy(notify = true).prefs(defaults)
            for ((event, label) in EVENTS) {
                val value = effective.on(event)
                val own = event in choices.overrides
                SheetItem(
                    label = stringResource(label),
                    detail = if (own) state(value) else stringResource(R.string.bg_default, state(value)),
                    enabled = choices.notify,
                    keepOpen = true,
                ) { onChoices(choices.with(event, !value, defaults)) }
            }
            if (choices.overrides.isNotEmpty()) {
                SheetItem(stringResource(R.string.bg_reset), enabled = choices.notify, keepOpen = true) {
                    onChoices(choices.copy(overrides = emptyMap()))
                }
            }
        }
    }
}

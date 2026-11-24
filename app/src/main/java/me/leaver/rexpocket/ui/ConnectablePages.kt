package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.LinkState
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.TailnetUse
import me.leaver.rexpocket.core.hostLinkState
import me.leaver.rexpocket.core.linkState
import me.leaver.rexpocket.core.on
import me.leaver.rexpocket.data.SavedHost

// A host's page and a tailnet's page, one layout for both: the state and its one action on top,
// then while the app is open (a tailnet's only), in the background, notifications, and its Quick
// Settings tile. See docs/design/tailnet-control.md, "One model for hosts and tailnets".

/** The tag of a page's state row, for the screen tests. */
internal const val CONN_STATE_TAG = "conn-state"

/** The words for [state], the same for a host and a tailnet. */
@Composable
internal fun linkStateText(state: LinkState): String = stringResource(
    when (state) {
        LinkState.Off -> R.string.conn_state_off
        LinkState.Connecting -> R.string.conn_state_connecting
        LinkState.Connected -> R.string.conn_state_connected
        LinkState.NeedsSignIn -> R.string.conn_state_sign_in
        LinkState.Failed -> R.string.conn_state_failed
    },
)

internal fun linkStateColor(state: LinkState): Color = when (state) {
    LinkState.Connected -> Colors.ok
    LinkState.Off -> Colors.muted
    LinkState.Connecting -> Colors.accent
    LinkState.NeedsSignIn -> Colors.amber
    LinkState.Failed -> Colors.danger
}

/** The state of the thing a page is about: its dot, its word, and a line under it. */
@Composable
private fun StateRow(state: LinkState, supporting: String?) {
    val word = linkStateText(state)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = word }.testTag(CONN_STATE_TAG)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(linkStateColor(state), 10.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(word, color = Colors.ink, fontSize = TextStyles.body)
            if (supporting != null) Text(supporting, color = Colors.muted, fontSize = TextStyles.label)
        }
    }
}

/**
 * The one action a host or a tailnet in [state] offers, in the place and words the host's sheet
 * has them: Connect when off, Cancel on its way, Retry now and Disconnect after a failure,
 * Disconnect when connected or waiting for a sign-in.
 */
@Composable
internal fun connectionActions(
    state: LinkState,
    kind: ConnectableKind,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    /** A tailnet's Restart, while its node runs or starts: stopped and started again. */
    onRestart: (() -> Unit)? = null,
): List<RowAction> {
    val icon = if (kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host
    val connect = RowAction(stringResource(R.string.action_connect), icon, run = onConnect)
    val retry = RowAction(stringResource(R.string.action_retry_now), R.drawable.ic_restart, run = onRetry)
    val disconnect = RowAction(stringResource(R.string.action_disconnect), R.drawable.ic_disconnect, danger = true, run = onDisconnect)
    val restart = onRestart?.let { RowAction(stringResource(R.string.tailnet_restart_item), R.drawable.ic_restart, run = it) }
    return when (state) {
        LinkState.Off -> listOf(connect)
        LinkState.Connecting -> listOfNotNull(restart, RowAction(stringResource(R.string.action_cancel), R.drawable.ic_close, run = onDisconnect))
        LinkState.Failed -> listOf(retry, disconnect)
        LinkState.Connected, LinkState.NeedsSignIn -> listOfNotNull(restart, disconnect)
    }
}

@Composable
private fun SheetScope.Actions(actions: List<RowAction>) {
    for (a in actions) {
        // The page stays: its state row shows what the tap did.
        SheetItem(a.label, color = if (a.danger) Colors.danger else Colors.ink, icon = a.icon, keepOpen = true, onClick = a.run)
    }
}

/** The background choice's three options, in the words of a host or a tailnet. */
private fun keepChoice(kind: ConnectableKind): (BackgroundKeep) -> Choice = { keep ->
    when (kind) {
        ConnectableKind.Host -> when (keep) {
            BackgroundKeep.Follow -> Choice(R.string.bg_keep_host_follow, R.drawable.ic_host, R.string.bg_keep_host_follow_body)
            BackgroundKeep.Always -> Choice(R.string.bg_always, R.drawable.ic_history, R.string.bg_always_body)
            BackgroundKeep.Off -> Choice(R.string.bg_keep_host_off, R.drawable.ic_disconnect, R.string.bg_stay_body)
        }
        ConnectableKind.Tailnet -> when (keep) {
            BackgroundKeep.Follow -> Choice(R.string.bg_keep_tailnet_follow, R.drawable.ic_host, R.string.bg_keep_tailnet_follow_body)
            BackgroundKeep.Always -> Choice(R.string.bg_keep_tailnet_always, R.drawable.ic_history, R.string.bg_keep_tailnet_always_body)
            BackgroundKeep.Off -> Choice(R.string.bg_keep_tailnet_off, R.drawable.ic_disconnect, R.string.bg_keep_tailnet_off_body)
        }
    }
}

private fun useChoice(use: TailnetUse): Choice = when (use) {
    TailnetUse.WhileOpen -> Choice(R.string.tailnet_use_open, R.drawable.ic_tailnet, R.string.tailnet_use_open_body)
    TailnetUse.WhenNeeded -> Choice(R.string.tailnet_use_needed, R.drawable.ic_timer, R.string.tailnet_use_needed_body)
}

private fun lingerChoice(ms: Long?): Choice = Choice(
    when (ms) {
        60_000L -> R.string.linger_1
        5 * 60_000L -> R.string.linger_5
        15 * 60_000L -> R.string.linger_15
        null -> R.string.linger_never
        else -> R.string.linger_5
    },
    R.drawable.ic_hourglass,
    R.string.tailnet_linger_choice_body,
)

/**
 * The background group: the choice, and while background mode is off, a line saying it applies
 * to nothing yet, and that the watch notification and its buttons show only with it on, with a
 * tap that turns it on ([onBackgroundMode]; null leaves it out).
 */
@Composable
private fun SheetScope.BackgroundGroup(
    kind: ConnectableKind,
    keep: BackgroundKeep,
    backgroundMode: Boolean,
    onBackgroundMode: (() -> Unit)?,
    onKeep: (BackgroundKeep) -> Unit,
) {
    Group(stringResource(R.string.conn_group_background)) {
        ChoiceRow(
            label = stringResource(R.string.bg_keep),
            options = BackgroundKeep.entries,
            current = keep,
            choice = keepChoice(kind),
            supporting = if (backgroundMode) null else stringResource(R.string.bg_keep_mode_off),
            onPick = onKeep,
        )
        if (!backgroundMode && onBackgroundMode != null) {
            SheetItem(
                label = stringResource(R.string.bg_turn_on),
                icon = R.drawable.ic_notifications,
                supporting = stringResource(R.string.bg_watch_only_with_mode),
                color = Colors.accent,
                keepOpen = true,
                modifier = Modifier.testTag(BG_TURN_ON_TAG),
                onClick = onBackgroundMode,
            )
        }
    }
}

/**
 * A host's page: its state and Connect or Disconnect, what it does in the background, and its
 * notifications, each kind following the defaults until it is set here.
 */
@Composable
internal fun HostPage(
    host: SavedHost,
    live: HostLive?,
    choices: HostBackground,
    defaults: AlertPrefs,
    backgroundMode: Boolean,
    onChoices: (HostBackground) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    tile: @Composable SheetScope.() -> Unit = {},
    /** Turns background mode on, from the line that says what it brings; null leaves it out. */
    onBackgroundMode: (() -> Unit)? = null,
) {
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    fun state(value: Boolean) = if (value) on else off
    val linkState = hostLinkState(live?.state?.phase, live?.state?.problem)
    ActionSheet(onDismiss = onDismiss, title = host.label.ifBlank { host.host }, onBack = onBack) {
        Group {
            StateRow(linkState, "${host.host}:${host.port}")
            Actions(connectionActions(linkState, ConnectableKind.Host, onConnect, onDisconnect, onRetry))
        }
        BackgroundGroup(ConnectableKind.Host, choices.keep, backgroundMode, onBackgroundMode) { onChoices(choices.withKeep(it)) }
        Group(stringResource(R.string.conn_group_notifications), icons = false) {
            SheetItem(
                label = stringResource(R.string.bg_notify),
                detail = state(choices.notify),
                keepOpen = true,
            ) { onChoices(choices.copy(notify = !choices.notify)) }
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
        tile()
    }
}

/**
 * A tailnet's page, laid out as a host's: its state and Connect or Disconnect, when it is up while
 * the app is open and how long it stays once no host uses it, what it does in the background, and
 * whether it tells about a sign-in or a failure while away.
 */
@Composable
internal fun TailnetPage(
    view: TailnetView,
    stage: TailnetStage,
    backgroundMode: Boolean,
    onPolicy: (TailnetPolicy) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
    tile: @Composable SheetScope.() -> Unit = {},
    accounts: TailnetAccountOps? = null,
    /** Turns background mode on, from the line that says what it brings; null leaves it out. */
    onBackgroundMode: (() -> Unit)? = null,
) {
    val policy = view.policy
    val on = stringResource(R.string.alerts_on)
    val off = stringResource(R.string.alerts_off)
    val linkState = stage.linkState()
    ActionSheet(onDismiss = onDismiss, title = view.name, onBack = onBack) {
        Group {
            StateRow(linkState, tailnetSupporting(view, stage))
            Actions(connectionActions(linkState, ConnectableKind.Tailnet, onConnect, onDisconnect, onRetry))
        }
        me.leaver.rexpocket.net.addon.TailnetBackendRow(this, view.backend)
        if (accounts != null && view.accounts.isNotEmpty()) AccountsGroup(view, accounts)
        Group(stringResource(R.string.conn_group_open)) {
            ChoiceRow(
                label = stringResource(R.string.tailnet_use),
                options = TailnetUse.entries,
                current = policy.use,
                choice = ::useChoice,
                onPick = { onPolicy(policy.copy(use = it)) },
            )
            ChoiceRow(
                label = stringResource(R.string.tailnet_linger),
                options = TailnetPolicy.LINGER_CHOICES,
                current = policy.lingerMs,
                choice = ::lingerChoice,
                supporting = stringResource(R.string.tailnet_linger_body),
                onPick = { onPolicy(policy.copy(lingerMs = it)) },
            )
        }
        BackgroundGroup(ConnectableKind.Tailnet, policy.keep, backgroundMode, onBackgroundMode) { onPolicy(policy.copy(keep = it)) }
        Group(stringResource(R.string.conn_group_notifications), icons = false) {
            SheetItem(
                label = stringResource(R.string.tailnet_notify),
                supporting = stringResource(R.string.tailnet_notify_body),
                detail = if (policy.notify) on else off,
                keepOpen = true,
            ) { onPolicy(policy.copy(notify = !policy.notify)) }
        }
        tile()
    }
}

/** The line under a tailnet's state: how many hosts use it, or when it stops while it lingers. */
@Composable
internal fun tailnetSupporting(view: TailnetView, stage: TailnetStage): String? {
    val until = view.lingerUntil
    if (until != null && view.hosts.isEmpty()) {
        // Worked out when the view changes, not on a timer: a ticking effect would keep the
        // screen from ever being idle, and the minutes only need to be right when it opens.
        val now = remember(until) { System.currentTimeMillis() }
        val minutes = (((until - now).coerceAtLeast(0) + 59_999) / 60_000).toInt()
        return pluralStringResource(R.plurals.tailnet_stops_in, minutes, minutes)
    }
    if (view.hosts.isNotEmpty()) return pluralStringResource(R.plurals.tailnet_used_by, view.hosts.size, view.hosts.size)
    return if (stage is TailnetStage.Disconnected || stage is TailnetStage.Failed) tailnetStageText(stage, view.backend) else null
}

/**
 * Confirms a tailnet's Disconnect while hosts are connected through it, naming them: they go too.
 * The same words a host's own Disconnect would be told in, once per host.
 */
@Composable
internal fun TailnetDisconnectDialog(view: TailnetView, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val names = view.hosts.joinToString(", ") { it.label.ifBlank { it.host } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.tailnet_disconnect_title, view.name), color = Colors.ink) },
        text = { Text(pluralStringResource(R.plurals.tailnet_disconnect_body, view.hosts.size, view.hosts.size, names), color = Colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_disconnect), color = Colors.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
        },
    )
}

internal const val TILE_CHANGE_TAG = "tile-change"
internal const val TILE_REMOVE_TAG = "tile-remove"
internal const val TILE_REPLACE_TAG = "tile-replace-"
internal const val BG_TURN_ON_TAG = "background-turn-on"

/**
 * A page's Quick Settings group, the same for a host and a tailnet. On a tile: "On tile N" with
 * Change… (the picker, for that tile) and Remove from tile. On none: Add a tile, which binds a free
 * one and, on Android 13 and later, asks to put it in Quick Settings; with all three taken, a
 * Replace row per tile, naming what it shows now. [nameOf] names a tile's item.
 */
@Composable
internal fun SheetScope.TileGroup(item: me.leaver.rexpocket.core.Connectable, name: String, nameOf: (me.leaver.rexpocket.core.Connectable) -> String? = { null }) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version by me.leaver.rexpocket.alerts.TileStore.changes.collectAsState()
    val slots = remember(item, version) { me.leaver.rexpocket.alerts.TileStore(context).all() }
    val slot = slots.indexOf(item).takeIf { it >= 0 }?.plus(1)
    var full by remember(item) { androidx.compose.runtime.mutableStateOf(false) }
    Group(stringResource(R.string.conn_group_tile)) {
        if (slot != null) {
            SheetItem(
                label = stringResource(R.string.tile_added, slot),
                icon = R.drawable.ic_check,
                supporting = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) stringResource(R.string.tile_add_manual, slot) else stringResource(R.string.tile_add_body),
                detail = stringResource(R.string.tile_change),
                keepOpen = true,
                opens = true,
                modifier = Modifier.testTag(TILE_CHANGE_TAG),
            ) { context.startActivity(me.leaver.rexpocket.alerts.TileStore.pickerIntent(context, slot).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SheetItem(
                label = stringResource(R.string.tile_remove),
                icon = R.drawable.ic_close,
                keepOpen = true,
                modifier = Modifier.testTag(TILE_REMOVE_TAG),
            ) { me.leaver.rexpocket.alerts.TileStore.remove(context, item) }
        } else {
            SheetItem(
                label = stringResource(R.string.tile_add),
                icon = R.drawable.ic_add,
                supporting = stringResource(if (full) R.string.tile_slots_full else R.string.tile_add_body),
                keepOpen = true,
            ) {
                full = me.leaver.rexpocket.alerts.TileStore.add(context, item, name) == null
            }
            if (full || slots.none { it == null }) {
                for (n in 1..me.leaver.rexpocket.alerts.TileStore.SLOTS) {
                    val now = slots[n - 1]?.let(nameOf) ?: stringResource(R.string.tile_item_gone)
                    SheetItem(
                        label = stringResource(R.string.tile_replace, n, now),
                        icon = R.drawable.ic_swap,
                        keepOpen = true,
                        modifier = Modifier.testTag(TILE_REPLACE_TAG + n),
                    ) {
                        me.leaver.rexpocket.alerts.TileStore.assign(context, n, item)
                        me.leaver.rexpocket.alerts.TileStore.requestAdd(context, n, item, name)
                        full = false
                    }
                }
            }
        }
    }
}

/**
 * Settings › Background and notifications › Quick Settings tiles: the three tiles, each with what
 * it shows, Change… (the picker) and Clear.
 */
@Composable
internal fun SheetScope.TileSlotsGroup(nameOf: (me.leaver.rexpocket.core.Connectable) -> String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version by me.leaver.rexpocket.alerts.TileStore.changes.collectAsState()
    val slots = remember(version) { me.leaver.rexpocket.alerts.TileStore(context).all() }
    Group(stringResource(R.string.tile_group_all)) {
        for (n in 1..me.leaver.rexpocket.alerts.TileStore.SLOTS) {
            val on = slots[n - 1]
            SheetItem(
                label = stringResource(R.string.tile_slot_item, n, on?.let { nameOf(it) ?: stringResource(R.string.tile_item_gone) } ?: stringResource(R.string.tile_free)),
                icon = if (on?.kind == ConnectableKind.Tailnet) R.drawable.ic_tailnet else R.drawable.ic_host,
                detail = stringResource(R.string.tile_change),
                keepOpen = true,
                opens = true,
                modifier = Modifier.testTag(TILE_CHANGE_TAG + n),
            ) { context.startActivity(me.leaver.rexpocket.alerts.TileStore.pickerIntent(context, n).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (on != null) {
                SheetItem(
                    label = stringResource(R.string.tile_clear_n, n),
                    icon = R.drawable.ic_close,
                    keepOpen = true,
                    modifier = Modifier.testTag(TILE_REMOVE_TAG + n),
                ) { me.leaver.rexpocket.alerts.TileStore.assign(context, n, null) }
            }
        }
    }
}

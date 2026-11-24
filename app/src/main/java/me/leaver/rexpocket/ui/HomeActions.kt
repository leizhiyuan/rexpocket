package me.leaver.rexpocket.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.ProgramRecord
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.paneRecords
import me.leaver.rexpocket.core.markable
import me.leaver.rexpocket.core.tabDetails
import me.leaver.rexpocket.data.HomeFolds
import me.leaver.rexpocket.data.SavedHost

// The home's standard sheets - a host's, a session's, a tab's - and the same items as each row's
// TalkBack actions. Each sheet is a list of [RowAction]s built in one place, so the ⋮, a long press
// and TalkBack always offer the same things in the same order. See docs/design/home.md.

/** The host, session or tab whose sheet is open on the home. */
internal sealed interface ManageTarget {
    val hostId: String

    data class Host(override val hostId: String) : ManageTarget
    data class Session(override val hostId: String, val sessionId: String) : ManageTarget
    data class Tab(override val hostId: String, val sessionId: String, val windowId: String) : ManageTarget
}

/** A dialog a sheet item leads to: a new name, or a confirmation of something no undo brings back. */
internal sealed interface HomeDialog {
    val target: ManageTarget

    data class Rename(override val target: ManageTarget, val current: String) : HomeDialog

    /** End a session or close a tab. */
    data class Confirm(override val target: ManageTarget, val name: String) : HomeDialog

    /** Remove a saved host; Undo brings it back, not connected. */
    data class RemoveHost(override val target: ManageTarget.Host, val name: String) : HomeDialog

    /** A tab's Status details: every report Rex has of its programs, and what the phone made of them. */
    data class StatusDetails(override val target: ManageTarget.Tab) : HomeDialog
}

/**
 * One item of a row's sheet, and, while [enabled], one of its TalkBack actions. An item that needs a
 * live host stays in place greyed out, its [detail] saying why ("Connect first", "Reconnecting…").
 */
internal class RowAction(
    val label: String,
    val icon: Int? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    val detail: String? = null,
    /** Keeps the sheet open: Move up and Move down, so a row can be moved several places in a row. */
    val keepOpen: Boolean = false,
    val run: () -> Unit,
)

/** A card of a sheet's items, with the caption above it if any (a move's "2 of 7"). */
internal class ActionGroup(val actions: List<RowAction>, val caption: String? = null)

/** The enabled items of a sheet, in its order, as a row's TalkBack actions. */
internal fun List<ActionGroup>.talkBack(): List<CustomAccessibilityAction> =
    flatMap { it.actions }.filter { it.enabled }.map { action -> CustomAccessibilityAction(action.label) { action.run(); true } }

/** What the home's rows and sheets ask of the app: the view model's calls, by host. */
internal class HomeOps(
    val connect: (SavedHost) -> Unit,
    val retry: (hostId: String) -> Unit,
    /** Stops a connection still on its way, as Disconnect does once it is up. */
    val cancel: (hostId: String) -> Unit,
    val disconnect: (hostId: String) -> Unit,
    val newSession: (SavedHost) -> Unit,
    val newTab: (SavedHost, sessionId: String) -> Unit,
    val openSession: (SavedHost, sessionId: String) -> Unit,
    val openTab: (SavedHost, sessionId: String, windowId: String) -> Unit,
    val openPane: (SavedHost, sessionId: String, windowId: String, blockId: String) -> Unit,
    val manage: (hostId: String, HomeAction) -> Unit,
    /** Opens or folds a host: the person's choice, which wins over the default from then on. */
    val foldHost: (hostId: String, open: Boolean) -> Unit,
    val foldSession: (hostId: String, sessionId: String, folded: Boolean) -> Unit,
    val foldSessions: (hostId: String, sessionIds: List<String>, folded: Boolean) -> Unit,
    val editHost: (hostId: String) -> Unit,
    val copyAddress: (SavedHost) -> Unit,
    val tailnetDetails: () -> Unit,
    /** Whether a done or failed report of a pane was seen: on this phone, from here, or at a desk. */
    val seen: (hostId: String, blockId: String, ProgramRecord) -> Boolean = { _, _, _ -> false },
    /** Marks panes seen, with an Undo. */
    val markSeen: (hostId: String, blockIds: List<String>) -> Unit = { _, _ -> },
    /** A pane's seen mark on this phone ([me.leaver.rexpocket.core.seenMark]), for Status details. */
    val markOf: (hostId: String, blockId: String) -> String? = { _, _ -> null },
    /** Moves a host to place [to] in the phone's own order of its hosts. */
    val moveHost: (hostId: String, to: Int) -> Unit = { _, _ -> },
    /** Removes a host, once confirmed, with an Undo. */
    val removeHost: (hostId: String) -> Unit = {},
    /** Opens a host's page: the background and its notifications, as a tailnet's page. */
    val page: (hostId: String) -> Unit = {},
)

/** The panes of [windows] with a done, failed or idle report not seen yet. */
internal fun HomeOps.unseen(hostId: String, state: PocketState, windows: List<WindowInfo>): List<String> =
    windows.flatMap { it.blocks }.mapNotNull { block ->
        block.id.takeIf {
            paneRecords(state, block.id).any { record -> markable(record) && !seen(hostId, block.id, record) }
        }
    }

/**
 * The home's open sheet and dialog. Hoisted out of the sheets so a row's TalkBack action can open the
 * same rename or confirm dialog the sheet's item does.
 */
@Stable
internal class HomeSheets {
    var sheet by mutableStateOf<ManageTarget?>(null)
    var dialog by mutableStateOf<HomeDialog?>(null)

    /** Sessions this phone ended, by host and session, so their going is not told as someone else's doing. */
    val endedHere: MutableSet<String> = HashSet()

    companion object {
        /** The open sheet survives a rotation and a trip to the terminal, as the other sheets do. */
        val Saver: Saver<HomeSheets, List<String>> = Saver(
            save = { sheets ->
                when (val t = sheets.sheet) {
                    is ManageTarget.Host -> listOf(t.hostId)
                    is ManageTarget.Session -> listOf(t.hostId, t.sessionId)
                    is ManageTarget.Tab -> listOf(t.hostId, t.sessionId, t.windowId)
                    null -> emptyList()
                }
            },
            restore = { saved ->
                HomeSheets().apply {
                    sheet = when (saved.size) {
                        1 -> ManageTarget.Host(saved[0])
                        2 -> ManageTarget.Session(saved[0], saved[1])
                        3 -> ManageTarget.Tab(saved[0], saved[1], saved[2])
                        else -> null
                    }
                }
            },
        )
    }
}

/** Everything a sheet item needs to know about one host: its saved entry and, when connected, its connection. */
internal class HostRef(val host: SavedHost, val live: HostLive?) {
    val state: PocketState get() = live?.state ?: PocketState()
    val connected: Boolean get() = live != null
    val isLive: Boolean get() = live?.state?.phase == Phase.Live
}

/** Why an item that needs a live host is greyed out: not connected, or connected and not back yet. */
@Composable
private fun needsLive(ref: HostRef): String? = when {
    ref.isLive -> null
    !ref.connected -> stringResource(R.string.home_needs_connect)
    else -> stringResource(R.string.status_reconnecting)
}

/**
 * Connect, Retry now, Cancel or Disconnect: what fits the host's connection now. A connection that
 * failed or is redialing offers Retry now and Disconnect, so a host that cannot be reached can be let go.
 */
@Composable
private fun connectionItems(ref: HostRef, ops: HomeOps): List<RowAction> {
    val id = ref.host.id
    val connect = RowAction(stringResource(R.string.action_connect), R.drawable.ic_host) { ops.connect(ref.host) }
    val retry = RowAction(stringResource(R.string.action_retry_now), R.drawable.ic_restart) { ops.retry(id) }
    val disconnect = RowAction(stringResource(R.string.action_disconnect), R.drawable.ic_disconnect, danger = true) { ops.disconnect(id) }
    return when (ref.live?.state?.phase) {
        null, Phase.Idle -> listOf(connect)
        Phase.Live -> listOf(disconnect)
        Phase.Connecting -> listOf(RowAction(stringResource(R.string.action_cancel), R.drawable.ic_close) { ops.cancel(id) })
        Phase.Reconnecting, Phase.Failed -> listOf(retry, disconnect)
    }
}

/** The sessions a host's sheet folds and unfolds: the live list, or the one it had last time. */
internal fun HostRef.sessionsShown(cached: List<ListedSession>): List<ListedSession> =
    if (connected && state.sessions.isNotEmpty()) state.sessions else cached

/** A host's sheet: its connection, its sessions and folds, its place, its settings. */
@Composable
internal fun hostActions(
    ref: HostRef,
    expanded: Boolean,
    sessions: List<ListedSession>,
    folds: HomeFolds,
    ops: HomeOps,
    /** The host's place in the phone's list, and the list's length, for Move up and Move down. */
    place: Int,
    count: Int,
    sheets: HomeSheets,
): List<ActionGroup> {
    val id = ref.host.id
    val why = needsLive(ref)
    val folded = sessions.count { HomeFolds.sessionKey(id, it.id) in folds.sessions }
    return listOfNotNull(
        ActionGroup(connectionItems(ref, ops)),
        ActionGroup(
            listOfNotNull(
                RowAction(stringResource(R.string.session_new), R.drawable.ic_add, enabled = why == null, detail = why) { ops.newSession(ref.host) },
                if (expanded) {
                    RowAction(stringResource(R.string.home_action_collapse), R.drawable.ic_expand) { ops.foldHost(id, false) }
                } else {
                    RowAction(stringResource(R.string.home_action_expand), R.drawable.ic_expand) { ops.foldHost(id, true) }
                },
                RowAction(stringResource(R.string.home_collapse_sessions), R.drawable.ic_expand, enabled = folded < sessions.size) {
                    ops.foldSessions(id, sessions.map { it.id }, true)
                }.takeIf { sessions.isNotEmpty() },
                RowAction(stringResource(R.string.home_expand_sessions), R.drawable.ic_expand, enabled = folded > 0) {
                    ops.foldSessions(id, sessions.map { it.id }, false)
                }.takeIf { sessions.isNotEmpty() },
            ),
        ),
        // The phone's own order: hosts are not Rex's, so this order is kept here only.
        moveGroup(place, count, null) { to -> ops.moveHost(id, to) },
        ActionGroup(
            listOfNotNull(
                RowAction(stringResource(R.string.action_edit_host), R.drawable.ic_edit) { ops.editHost(id) },
                RowAction(stringResource(R.string.conn_settings), R.drawable.ic_notifications) { ops.page(id) },
                RowAction(stringResource(R.string.home_copy_address), R.drawable.ic_copy) { ops.copyAddress(ref.host) },
                RowAction(stringResource(R.string.home_tailnet_details), R.drawable.ic_tailnet) { ops.tailnetDetails() }.takeIf { ref.host.viaTailscale },
            ),
        ),
        ActionGroup(
            listOf(
                RowAction(stringResource(R.string.home_remove_host), R.drawable.ic_delete, danger = true) {
                    sheets.dialog = HomeDialog.RemoveHost(ManageTarget.Host(id), ref.host.label)
                },
            ),
        ),
    )
}

/**
 * Move up and Move down, under a caption that says where the row is now. They keep the sheet open,
 * and stay in place greyed out at the ends so the buttons do not jump under the finger. [index] is
 * the row's place in Rex's whole order, whatever a filter or the search shows.
 */
@Composable
internal fun moveGroup(index: Int, count: Int, why: String?, onMove: (to: Int) -> Unit): ActionGroup? {
    if (index < 0 || count < 2) return null
    return ActionGroup(
        listOf(
            RowAction(stringResource(R.string.action_move_up), R.drawable.ic_arrow_up, enabled = why == null && index > 0, detail = why, keepOpen = true) {
                onMove(index - 1)
            },
            RowAction(stringResource(R.string.action_move_down), R.drawable.ic_arrow_down, enabled = why == null && index < count - 1, detail = why, keepOpen = true) {
                onMove(index + 1)
            },
        ),
        caption = stringResource(R.string.order_place, index + 1, count),
    )
}

/**
 * A session's sheet. While its host is not live, Connect or Retry comes first and the items that
 * need Rex are greyed out; Open stays, as opening connects (or waits for the connection).
 */
@Composable
internal fun sessionActions(
    ref: HostRef,
    session: ListedSession,
    expanded: Boolean,
    ops: HomeOps,
    sheets: HomeSheets,
): List<ActionGroup> {
    val id = ref.host.id
    val why = needsLive(ref)
    val target = ManageTarget.Session(id, session.id)
    val label = ref.state.views[session.id]?.label ?: session.label
    val order = ref.state.sessions
    val top = when {
        ref.isLive -> emptyList()
        !ref.connected -> listOf(RowAction(stringResource(R.string.action_connect), R.drawable.ic_host) { ops.connect(ref.host) })
        ref.state.phase == Phase.Connecting -> emptyList()
        else -> listOf(RowAction(stringResource(R.string.action_retry_now), R.drawable.ic_restart) { ops.retry(id) })
    }
    return listOfNotNull(
        ActionGroup(
            top + listOfNotNull(
                RowAction(stringResource(R.string.home_action_open), R.drawable.ic_open_in_new) { ops.openSession(ref.host, session.id) }
                    .takeIf { ref.connected },
                RowAction(stringResource(R.string.menu_new_tab), R.drawable.ic_add, enabled = why == null, detail = why) {
                    ops.manage(id, HomeAction.NewTab(session.id))
                },
                RowAction(stringResource(R.string.home_rename), R.drawable.ic_edit, enabled = why == null, detail = why) {
                    sheets.dialog = HomeDialog.Rename(target, label)
                },
            ),
        ),
        ActionGroup(
            listOfNotNull(
                if (expanded) {
                    RowAction(stringResource(R.string.home_action_collapse), R.drawable.ic_expand) { ops.foldSession(id, session.id, true) }
                } else {
                    RowAction(stringResource(R.string.home_action_expand), R.drawable.ic_expand) { ops.foldSession(id, session.id, false) }
                },
                ops.unseen(id, ref.state, ref.state.views[session.id]?.windows.orEmpty()).takeIf { it.isNotEmpty() }?.let { panes ->
                    RowAction(stringResource(R.string.home_mark_all_seen), R.drawable.ic_check) { ops.markSeen(id, panes) }
                },
            ),
        ),
        moveGroup(order.indexOfFirst { it.id == session.id }, order.size, why) { to -> ops.manage(id, HomeAction.MoveSession(session.id, to)) },
        ActionGroup(
            listOf(
                RowAction(stringResource(R.string.home_end_session), R.drawable.ic_close, danger = true, enabled = why == null, detail = why) {
                    sheets.dialog = HomeDialog.Confirm(target, label)
                },
            ),
        ),
    )
}

/** A tab's sheet: open it, rename it, move it in its session's order, close it. */
@Composable
internal fun tabActions(
    ref: HostRef,
    sessionId: String,
    windowId: String,
    ops: HomeOps,
    sheets: HomeSheets,
): List<ActionGroup> {
    val id = ref.host.id
    val why = needsLive(ref)
    val view = ref.state.views[sessionId] ?: return emptyList()
    val window = view.windows.firstOrNull { it.id == windowId } ?: return emptyList()
    val target = ManageTarget.Tab(id, sessionId, windowId)
    val title = tabTitle(view, window, ref.state)
    return listOfNotNull(
        ActionGroup(
            listOf(
                RowAction(stringResource(R.string.home_action_open), R.drawable.ic_open_in_new) { ops.openTab(ref.host, sessionId, windowId) },
                RowAction(stringResource(R.string.home_rename), R.drawable.ic_edit, enabled = why == null, detail = why) {
                    sheets.dialog = HomeDialog.Rename(target, window.label)
                },
            ),
        ),
        ActionGroup(
            listOfNotNull(
                ops.unseen(id, ref.state, listOf(window)).takeIf { it.isNotEmpty() }?.let { panes ->
                    RowAction(stringResource(R.string.home_mark_seen), R.drawable.ic_check) { ops.markSeen(id, panes) }
                },
                RowAction(stringResource(R.string.status_details), R.drawable.ic_eye) { sheets.dialog = HomeDialog.StatusDetails(target) },
            ),
        ),
        moveGroup(view.windows.indexOf(window), view.windows.size, why) { to -> ops.manage(id, HomeAction.MoveTab(sessionId, windowId, to)) },
        ActionGroup(
            listOf(
                RowAction(stringResource(R.string.home_close_tab), R.drawable.ic_close, danger = true, enabled = why == null, detail = why) {
                    sheets.dialog = HomeDialog.Confirm(target, title)
                },
            ),
        ),
    )
}

/** The items of [groups] as a sheet, titled [title]. */
@Composable
internal fun RowActionsSheet(title: String?, groups: List<ActionGroup>, onDismiss: () -> Unit) {
    ActionSheet(onDismiss = onDismiss, title = title) {
        for (group in groups) {
            if (group.actions.isEmpty()) continue
            Group(group.caption) {
                for (action in group.actions) {
                    SheetItem(
                        action.label,
                        color = if (action.danger) Colors.danger else Colors.ink,
                        detail = action.detail,
                        icon = action.icon,
                        keepOpen = action.keepOpen,
                        enabled = action.enabled,
                        onClick = action.run,
                    )
                }
            }
        }
    }
}

/**
 * The sheet that is open, if any, built from the same items as its row's TalkBack actions; and the
 * dialog an item opened. A sheet goes when what it is about goes, on any device. A confirm dialog
 * whose host is not live cannot confirm until it is back.
 */
@Composable
internal fun HomeSheetsHost(
    sheets: HomeSheets,
    hosts: List<SavedHost>,
    lives: Map<String, HostLive>,
    folds: HomeFolds,
    cachedSessions: (String) -> List<ListedSession>,
    ops: HomeOps,
    /** A session whose sheet or dialog was open was ended elsewhere: its name, and its host's. */
    onEndedElsewhere: (session: String, host: String) -> Unit = { _, _ -> },
) {
    // The last name each open session was seen by, for saying which one went.
    val labels = androidx.compose.runtime.remember { HashMap<String, String>() }
    fun endedElsewhere(hostId: String, sessionId: String) {
        val key = HomeFolds.sessionKey(hostId, sessionId)
        if (sheets.endedHere.remove(key)) return
        val host = hosts.firstOrNull { it.id == hostId }?.label ?: return
        onEndedElsewhere(labels[key] ?: return, host)
    }
    fun refOf(id: String) = hosts.firstOrNull { it.id == id }?.let { HostRef(it, lives[it.id]) }
    when (val target = sheets.sheet) {
        is ManageTarget.Host -> {
            val ref = refOf(target.hostId)
            if (ref == null) {
                LaunchedEffect(target) { sheets.sheet = null }
            } else {
                val sessions = ref.sessionsShown(cachedSessions(ref.host.id))
                val place = hosts.indexOfFirst { it.id == ref.host.id }
                val groups = hostActions(ref, folds.hostOpen(ref.host.id, ref.connected), sessions, folds, ops, place, hosts.size, sheets)
                RowActionsSheet(ref.host.label, groups) { sheets.sheet = null }
            }
        }
        is ManageTarget.Session -> {
            val ref = refOf(target.hostId)
            val session = ref?.sessionsShown(cachedSessions(target.hostId))?.firstOrNull { it.id == target.sessionId }
            if (ref == null || session == null) {
                LaunchedEffect(target) {
                    sheets.sheet = null
                    endedElsewhere(target.hostId, target.sessionId)
                }
            } else {
                labels[HomeFolds.sessionKey(target.hostId, session.id)] = ref.state.views[session.id]?.label ?: session.label
                val expanded = HomeFolds.sessionKey(ref.host.id, session.id) !in folds.sessions
                val title = ref.state.views[session.id]?.label ?: session.label
                RowActionsSheet(title, sessionActions(ref, session, expanded, ops, sheets)) { sheets.sheet = null }
            }
        }
        is ManageTarget.Tab -> {
            val ref = refOf(target.hostId)
            val view = ref?.state?.views?.get(target.sessionId)
            val window = view?.windows?.firstOrNull { it.id == target.windowId }
            if (ref == null || window == null) {
                LaunchedEffect(target) { sheets.sheet = null }
            } else {
                RowActionsSheet(tabTitle(view, window, ref.state), tabActions(ref, target.sessionId, target.windowId, ops, sheets)) { sheets.sheet = null }
            }
        }
        null -> Unit
    }

    val open = sheets.dialog ?: return
    val ref = refOf(open.target.hostId)
    // What the dialog is about is gone: the session was ended, the tab closed, on any device.
    val gone = ref == null || when (val t = open.target) {
        is ManageTarget.Host -> false
        is ManageTarget.Session -> ref.connected && ref.state.phase == Phase.Live && ref.state.sessions.none { it.id == t.sessionId }
        is ManageTarget.Tab -> ref.state.phase == Phase.Live && ref.state.views[t.sessionId]?.windows?.none { it.id == t.windowId } != false
    }
    if (gone) {
        LaunchedEffect(open) {
            sheets.dialog = null
            (open.target as? ManageTarget.Session)?.let { endedElsewhere(it.hostId, it.sessionId) }
        }
        return
    }
    (open.target as? ManageTarget.Session)?.let { t ->
        ref!!.state.views[t.sessionId]?.label?.let { labels[HomeFolds.sessionKey(t.hostId, t.sessionId)] = it }
    }
    val live = ref!!.isLive
    when (open) {
        is HomeDialog.Rename -> RenameDialog(
            title = stringResource(if (open.target is ManageTarget.Tab) R.string.menu_rename_tab else R.string.menu_rename_session),
            current = open.current,
            onDismiss = { sheets.dialog = null },
        ) { label ->
            sheets.dialog = null
            when (val t = open.target) {
                is ManageTarget.Session -> ops.manage(t.hostId, HomeAction.RenameSession(t.sessionId, label))
                is ManageTarget.Tab -> ops.manage(t.hostId, HomeAction.RenameTab(t.sessionId, t.windowId, label))
                is ManageTarget.Host -> Unit
            }
        }
        is HomeDialog.RemoveHost -> AlertDialog(
            onDismissRequest = { sheets.dialog = null },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.home_confirm_remove, open.name), color = Colors.ink) },
            text = { Text(stringResource(R.string.confirm_delete_host_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    sheets.dialog = null
                    sheets.sheet = null
                    ops.removeHost(open.target.hostId)
                }) { Text(stringResource(R.string.home_remove), color = Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { sheets.dialog = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
        is HomeDialog.StatusDetails -> {
            val t = open.target
            val details = tabDetails(ref.state, t.windowId) { block -> ops.markOf(t.hostId, block) }
            val view = ref.state.views[t.sessionId]
            if (details == null || view == null) {
                LaunchedEffect(open) { sheets.dialog = null }
            } else {
                StatusDetailsSheet(tabTitle(view, details.window, ref.state), details) { sheets.dialog = null }
            }
        }
        is HomeDialog.Confirm -> {
            val tab = open.target is ManageTarget.Tab
            AlertDialog(
                onDismissRequest = { sheets.dialog = null },
                containerColor = Colors.surface,
                title = {
                    Text(stringResource(if (tab) R.string.confirm_close_tab else R.string.confirm_kill_session, open.name), color = Colors.ink)
                },
                text = {
                    Text(
                        if (live) stringResource(if (tab) R.string.confirm_close_tab_body else R.string.confirm_kill_session_body)
                        else stringResource(R.string.status_reconnecting),
                        color = Colors.muted,
                    )
                },
                confirmButton = {
                    TextButton(enabled = live, onClick = {
                        sheets.dialog = null
                        when (val t = open.target) {
                            is ManageTarget.Session -> {
                                sheets.endedHere += HomeFolds.sessionKey(t.hostId, t.sessionId)
                                ops.manage(t.hostId, HomeAction.EndSession(t.sessionId))
                            }
                            is ManageTarget.Tab -> ops.manage(t.hostId, HomeAction.CloseTab(t.sessionId, t.windowId))
                            is ManageTarget.Host -> Unit
                        }
                    }) { Text(stringResource(if (tab) R.string.action_close else R.string.action_end), color = if (live) Colors.danger else Colors.muted) }
                },
                dismissButton = {
                    TextButton(onClick = { sheets.dialog = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
                },
            )
        }
    }
}

/** The pane a Needs you row's Reply answers, and the name the dialog calls it by. */
internal data class ReplyTarget(val hostId: String, val sessionId: String, val blockId: String, val title: String)

/**
 * A reply to a waiting program: what is typed, then Enter, as the notification's Reply sends it. The
 * quick replies under the field put their text in it; Compose takes what is there to the composer,
 * for a longer prompt.
 */
@Composable
internal fun ReplyDialog(
    title: String,
    onDismiss: () -> Unit,
    quickReplies: List<String> = emptyList(),
    onCompose: ((String) -> Unit)? = null,
    onSend: (String) -> Unit,
) {
    var text by androidx.compose.runtime.remember { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank()) onSend(text) else onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            androidx.compose.foundation.layout.Column {
                Field(stringResource(R.string.alerts_reply), text, autoFocus = true, imeAction = androidx.compose.ui.text.input.ImeAction.Done, onDone = { send() }) { text = it }
                if (quickReplies.isNotEmpty()) QuickReplyChips(quickReplies, insert = true) { text = joinReply(text, it) }
            }
        },
        confirmButton = { TextButton(onClick = { send() }) { Text(stringResource(R.string.home_reply_send), color = Colors.accent) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onCompose != null) TextButton(onClick = { onCompose(text) }) { Text(stringResource(R.string.home_reply_compose), color = Colors.accent) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
}

/** How a failed sheet action is said: "Couldn't rename: …". */
internal fun failedText(action: HomeAction): Int = when (action) {
    is HomeAction.RenameSession, is HomeAction.RenameTab -> R.string.home_failed_rename
    is HomeAction.NewTab -> R.string.home_failed_new_tab
    is HomeAction.EndSession -> R.string.home_failed_end
    is HomeAction.CloseTab -> R.string.home_failed_close
    is HomeAction.MoveSession, is HomeAction.MoveTab -> R.string.home_failed_move
}

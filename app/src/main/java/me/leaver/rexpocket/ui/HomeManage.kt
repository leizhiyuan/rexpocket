package me.leaver.rexpocket.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.input.ImeAction
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.data.SavedHost

/**
 * What the home's session and tab sheets ask of the connected host. None of it attaches the session
 * or takes a pane, so a desktop showing it keeps its sizes: Rex lets a connection that bootstrapped
 * rename, add, move and close tabs, and move and end sessions, it is not attached to (rex-parity.md,
 * "Managing sessions from the home" and "Ordering").
 */
sealed interface HomeAction {
    data class RenameSession(val sessionId: String, val label: String) : HomeAction
    data class NewTab(val sessionId: String) : HomeAction
    data class EndSession(val sessionId: String) : HomeAction
    data class RenameTab(val sessionId: String, val windowId: String, val label: String) : HomeAction
    data class CloseTab(val sessionId: String, val windowId: String) : HomeAction

    /** Moves a session to place [to] (0 is first) in Rex's order, the one every client shows. */
    data class MoveSession(val sessionId: String, val to: Int) : HomeAction

    /** Moves a tab to place [to] in its session's tab order, as Rex's tab bar has it. */
    data class MoveTab(val sessionId: String, val windowId: String, val to: Int) : HomeAction
}

/**
 * Move up and Move down for a row at [index] of [count], for TalkBack's actions menu: the same moves
 * the sheet offers, without opening it. None where the row cannot move that way.
 */
@Composable
internal fun moveActions(index: Int, count: Int, onMove: (to: Int) -> Unit): List<CustomAccessibilityAction> {
    val up = stringResource(R.string.action_move_up)
    val down = stringResource(R.string.action_move_down)
    if (index < 0) return emptyList()
    return buildList {
        if (index > 0) add(CustomAccessibilityAction(up) { onMove(index - 1); true })
        if (index < count - 1) add(CustomAccessibilityAction(down) { onMove(index + 1); true })
    }
}

/**
 * The sheet's Move up and Move down, under a caption that says where the row is now. They keep the
 * sheet open, so a row can be moved several places in a row, and stay in place greyed out at the
 * ends so the buttons do not jump under the finger.
 */
@Composable
private fun SheetScope.MoveRows(index: Int, count: Int, onMove: (to: Int) -> Unit) {
    if (index < 0 || count < 2) return
    Group(label = stringResource(R.string.order_place, index + 1, count)) {
        SheetItem(stringResource(R.string.action_move_up), icon = R.drawable.ic_arrow_up, keepOpen = true, enabled = index > 0) {
            onMove(index - 1)
        }
        SheetItem(stringResource(R.string.action_move_down), icon = R.drawable.ic_arrow_down, keepOpen = true, enabled = index < count - 1) {
            onMove(index + 1)
        }
    }
}

/** The session or tab whose sheet is open on the home. */
internal sealed interface ManageTarget {
    data class Session(val sessionId: String) : ManageTarget
    data class Tab(val sessionId: String, val windowId: String) : ManageTarget
}

private sealed interface ManageDialog {
    data class Rename(val target: ManageTarget, val current: String) : ManageDialog
    data class Confirm(val target: ManageTarget, val name: String) : ManageDialog
}

/**
 * The sheet a long press or the ⋮ of a session or tab opens on the home, and the rename and close
 * dialogs it leads to. A destructive action is confirmed: closing a tab or ending a session ends
 * its programs, which no undo brings back. The sheet goes when what it is about goes, on any device.
 */
@Composable
internal fun HomeManageSheet(target: ManageTarget?, state: PocketState, onDismiss: () -> Unit, onAction: (HomeAction) -> Unit) {
    var dialog by remember { mutableStateOf<ManageDialog?>(null) }
    when (target) {
        is ManageTarget.Session -> {
            val label = state.views[target.sessionId]?.label ?: state.sessions.firstOrNull { it.id == target.sessionId }?.label
            if (label == null) {
                LaunchedEffect(target) { onDismiss() }
            } else {
                ActionSheet(onDismiss = onDismiss, title = label) {
                    Group {
                        SheetItem(stringResource(R.string.menu_rename_session), icon = R.drawable.ic_edit) {
                            dialog = ManageDialog.Rename(target, label)
                        }
                        SheetItem(stringResource(R.string.menu_new_tab), icon = R.drawable.ic_add) {
                            onAction(HomeAction.NewTab(target.sessionId))
                        }
                        SheetItem(stringResource(R.string.menu_kill_session), Colors.danger, icon = R.drawable.ic_close) {
                            dialog = ManageDialog.Confirm(target, label)
                        }
                    }
                    MoveRows(state.sessions.indexOfFirst { it.id == target.sessionId }, state.sessions.size) { to ->
                        onAction(HomeAction.MoveSession(target.sessionId, to))
                    }
                }
            }
        }
        is ManageTarget.Tab -> {
            val view = state.views[target.sessionId]
            val window = view?.windows?.firstOrNull { it.id == target.windowId }
            if (view == null || window == null) {
                LaunchedEffect(target) { onDismiss() }
            } else {
                val title = tabTitle(view, window, state)
                ActionSheet(onDismiss = onDismiss, title = title) {
                    Group {
                        SheetItem(stringResource(R.string.menu_rename_tab), icon = R.drawable.ic_edit) {
                            dialog = ManageDialog.Rename(target, window.label)
                        }
                        SheetItem(stringResource(R.string.menu_close_tab), Colors.danger, icon = R.drawable.ic_close) {
                            dialog = ManageDialog.Confirm(target, title)
                        }
                    }
                    MoveRows(view.windows.indexOf(window), view.windows.size) { to ->
                        onAction(HomeAction.MoveTab(target.sessionId, target.windowId, to))
                    }
                }
            }
        }
        null -> Unit
    }

    when (val open = dialog) {
        is ManageDialog.Rename -> RenameDialog(
            title = stringResource(if (open.target is ManageTarget.Tab) R.string.menu_rename_tab else R.string.menu_rename_session),
            current = open.current,
            onDismiss = { dialog = null },
        ) { label ->
            dialog = null
            onAction(
                when (val t = open.target) {
                    is ManageTarget.Session -> HomeAction.RenameSession(t.sessionId, label)
                    is ManageTarget.Tab -> HomeAction.RenameTab(t.sessionId, t.windowId, label)
                },
            )
        }
        is ManageDialog.Confirm -> {
            val tab = open.target is ManageTarget.Tab
            AlertDialog(
                onDismissRequest = { dialog = null },
                containerColor = Colors.surface,
                title = {
                    Text(
                        stringResource(if (tab) R.string.confirm_close_tab else R.string.confirm_kill_session, open.name),
                        color = Colors.ink,
                    )
                },
                text = {
                    Text(stringResource(if (tab) R.string.confirm_close_tab_body else R.string.confirm_kill_session_body), color = Colors.muted)
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        onAction(
                            when (val t = open.target) {
                                is ManageTarget.Session -> HomeAction.EndSession(t.sessionId)
                                is ManageTarget.Tab -> HomeAction.CloseTab(t.sessionId, t.windowId)
                            },
                        )
                    }) { Text(stringResource(if (tab) R.string.action_close else R.string.action_end), color = Colors.danger) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
                },
            )
        }
        null -> Unit
    }
}

/** A name to change: Save (or Enter) sends it when it is new and not blank. */
@Composable
internal fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    fun save() {
        val label = text.trim()
        if (label.isEmpty() || label == current) onDismiss() else onSave(label)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = { Field(stringResource(R.string.field_name), text, autoFocus = true, imeAction = ImeAction.Done, onDone = { save() }) { text = it } },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save), color = Colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}

/**
 * A host's ⋮ sheet: a new session, edit, and Connect or Disconnect. Disconnecting one host leaves
 * every other connected; a host that is not connected connects only from here or its Connect button.
 */
@Composable
internal fun HostSheet(
    host: SavedHost,
    connected: Boolean,
    onDismiss: () -> Unit,
    onNewSession: () -> Unit,
    onEdit: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ActionSheet(onDismiss = onDismiss, title = host.label) {
        Group {
            SheetItem(stringResource(R.string.session_new), icon = R.drawable.ic_add) {
                onDismiss()
                onNewSession()
            }
            SheetItem(stringResource(R.string.action_edit_host), icon = R.drawable.ic_edit) {
                onDismiss()
                onEdit()
            }
            if (connected) {
                SheetItem(stringResource(R.string.action_disconnect), Colors.danger, icon = R.drawable.ic_disconnect) {
                    onDismiss()
                    onDisconnect()
                }
            } else {
                SheetItem(stringResource(R.string.action_connect), icon = R.drawable.ic_host) {
                    onDismiss()
                    onConnect()
                }
            }
        }
    }
}

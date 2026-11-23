package me.leaver.rexpocket.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketState

/**
 * What the home's session and tab sheets ask of the connected host. None of it attaches the session
 * or takes a pane, so a desktop showing it keeps its sizes: Rex lets a connection that bootstrapped
 * rename, add and close tabs and end sessions it is not attached to (rex-parity.md, "Managing
 * sessions from the home").
 */
sealed interface HomeAction {
    data class RenameSession(val sessionId: String, val label: String) : HomeAction
    data class NewTab(val sessionId: String) : HomeAction
    data class EndSession(val sessionId: String) : HomeAction
    data class RenameTab(val sessionId: String, val windowId: String, val label: String) : HomeAction
    data class CloseTab(val sessionId: String, val windowId: String) : HomeAction
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

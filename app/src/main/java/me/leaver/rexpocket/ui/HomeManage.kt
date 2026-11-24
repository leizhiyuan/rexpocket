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


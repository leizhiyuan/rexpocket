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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
 * A name to change: Save (or Enter) sends it when it is new. An empty name, or Automatic name, gives
 * the session or tab back the name Rex chose for it (`set_label` and `set_window_label` with an
 * empty label restore it: "quiet-meadow", "Window 89abcdef"), so a rename can always be undone.
 */
@Composable
internal fun RenameDialog(title: String, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(current) { mutableStateOf(current) }
    fun save() {
        val label = text.trim()
        if (label == current) onDismiss() else onSave(label)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            androidx.compose.foundation.layout.Column {
                Field(stringResource(R.string.field_name), text, autoFocus = true, imeAction = ImeAction.Done, onDone = { save() }) { text = it }
                Text(
                    stringResource(R.string.rename_empty_hint),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    modifier = androidx.compose.ui.Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text(stringResource(R.string.action_save), color = Colors.accent) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.rename_automatic), color = Colors.accent) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
}


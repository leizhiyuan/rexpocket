package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.TailnetProfile
import me.leaver.rexpocket.data.SavedHost

// A tailnet's accounts on its page: several sign-ins to one backend, one running at a time. See
// docs/design/tailnet-control.md, "Accounts".

/** What a tailnet's Accounts group does: run another account, add, rename, remove one. */
internal class TailnetAccountOps(
    val switch: (id: String) -> Unit,
    val add: (name: String) -> Unit,
    val rename: (id: String, name: String) -> Unit,
    val remove: (id: String) -> Unit,
    /** The saved hosts that name an account, which its Remove names. */
    val hostsNaming: (id: String) -> List<SavedHost>,
    /** Opens an account's dialog ([ADD_ACCOUNT] for Add an account), over the page. */
    val edit: (String) -> Unit = {},
)

/** [TailnetAccountOps.edit]'s key for Add an account. */
internal const val ADD_ACCOUNT = "+"


/** The tag of an account's dialog, for the screen tests. */
internal const val ACCOUNT_DIALOG_TAG = "tailnet-account-dialog"

/**
 * A tailnet's accounts: each one, the one running marked, and Add an account under them. A tap
 * opens the account: Use this account (the hosts through the tailnet reconnect on it), its name,
 * and Remove.
 */
@Composable
internal fun SheetScope.AccountsGroup(view: TailnetView, ops: TailnetAccountOps) {
    val inUse = stringResource(R.string.tailnet_account_in_use)
    Group(stringResource(R.string.tailnet_group_accounts)) {
        for (a in view.accounts) {
            SheetItem(
                label = a.name,
                icon = R.drawable.ic_tailnet,
                marked = a.id == view.account,
                detail = inUse.takeIf { a.id == view.account },
                keepOpen = true,
                opens = true,
            ) { ops.edit(a.id) }
        }
        SheetItem(
            stringResource(R.string.tailnet_account_add),
            icon = R.drawable.ic_add,
            supporting = stringResource(R.string.tailnet_account_add_body),
            keepOpen = true,
        ) { ops.edit(ADD_ACCOUNT) }
    }
}

/** The dialog [edit] names ([TailnetAccountOps.edit]). */
@Composable
internal fun TailnetAccountDialog(view: TailnetView, ops: TailnetAccountOps, edit: String, onDone: () -> Unit) {
    view.accounts.firstOrNull { it.id == edit }?.let { a ->
        AccountDialog(a, active = a.id == view.account, view = view, ops = ops, onDismiss = onDone)
    }
    if (edit == ADD_ACCOUNT) {
        NameDialog(
            title = stringResource(R.string.tailnet_account_add),
            action = stringResource(R.string.tailnet_account_add_action),
            body = stringResource(
                if (view.backend.signsIn) R.string.tailnet_account_sign_in_note else R.string.tailnet_account_key_note,
                view.backend.displayName,
            ),
            onDismiss = onDone,
        ) { name ->
            onDone()
            ops.add(name)
        }
    }
}

private fun names(hosts: List<SavedHost>) = hosts.joinToString(", ") { it.label.ifBlank { it.host } }

@Composable
private fun AccountDialog(account: TailnetProfile, active: Boolean, view: TailnetView, ops: TailnetAccountOps, onDismiss: () -> Unit) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    var removing by remember(account.id) { mutableStateOf(false) }
    if (removing) {
        val naming = ops.hostsNaming(account.id)
        val body = stringResource(R.string.tailnet_account_remove_body) +
            if (naming.isEmpty()) "" else " " + pluralStringResource(R.plurals.tailnet_account_remove_hosts, naming.size, naming.size, names(naming))
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.tailnet_account_remove_title, account.name), color = Colors.ink) },
            text = { Text(body, color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    ops.remove(account.id)
                    onDismiss()
                }) { Text(stringResource(R.string.tailnet_account_remove), color = Colors.danger) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        modifier = Modifier.testTag(ACCOUNT_DIALOG_TAG),
        title = { Text(account.name, color = Colors.ink) },
        text = {
            Column {
                Field(stringResource(R.string.tailnet_account_name), name) { name = it }
                if (!active) {
                    Spacer(Modifier.height(8.dp))
                    val through = view.hosts
                    Text(
                        if (through.isEmpty()) stringResource(R.string.tailnet_account_switch_body)
                        else pluralStringResource(R.plurals.tailnet_account_switch_hosts, through.size, through.size, names(through)),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && name.trim() != account.name) ops.rename(account.id, name)
                if (!active) ops.switch(account.id)
                onDismiss()
            }) {
                Text(stringResource(if (active) R.string.action_save else R.string.tailnet_account_use), color = Colors.accent)
            }
        },
        dismissButton = {
            Row {
                if (!active) TextButton(onClick = { removing = true }) { Text(stringResource(R.string.tailnet_account_remove), color = Colors.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, action: String, body: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(title, color = Colors.ink) },
        text = {
            Column {
                Field(stringResource(R.string.tailnet_account_name), name) { name = it }
                Spacer(Modifier.height(8.dp))
                Text(body, color = Colors.muted, fontSize = TextStyles.label)
            }
        },
        confirmButton = { TextButton(onClick = { onDone(name) }) { Text(action, color = Colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}

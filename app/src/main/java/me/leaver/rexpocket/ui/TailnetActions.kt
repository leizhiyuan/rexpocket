package me.leaver.rexpocket.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.TailnetUse
import me.leaver.rexpocket.data.SavedHost

// What a tailnet's screens say about why it is up, why a host on it is off, and the confirm of
// Leave tailnet. See docs/design/tailnet-control.md, "Screens".

/** The tag of Leave tailnet's confirm, and of its Forget the sign-in box, for the screen tests. */
internal const val LEAVE_DIALOG_TAG = "tailnet-leave-dialog"
internal const val LEAVE_FORGET_TAG = "tailnet-leave-forget"

/**
 * Leave tailnet's confirm: what leaving does, the hosts connected through it that disconnect with
 * it, and Forget the sign-in on this phone, after which joining again asks for one. [onLeave] is
 * told whether to forget it.
 */
@Composable
internal fun LeaveTailnetDialog(view: TailnetView, onDismiss: () -> Unit, onLeave: (forgetSignIn: Boolean) -> Unit) {
    var forget by rememberSaveable { mutableStateOf(false) }
    val hosts = view.hosts
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        modifier = Modifier.testTag(LEAVE_DIALOG_TAG),
        title = { Text(stringResource(R.string.tailnet_leave_title, view.name), color = Colors.ink) },
        text = {
            Column {
                Text(stringResource(R.string.tailnet_leave_body), color = Colors.muted)
                if (hosts.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(pluralStringResource(R.plurals.tailnet_leave_hosts, hosts.size, hosts.size, hostNames(hosts)), color = Colors.muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = forget, role = Role.Checkbox) { forget = it }
                        .testTag(LEAVE_FORGET_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = forget, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.tailnet_leave_forget), color = Colors.ink, fontSize = TextStyles.body)
                        Text(stringResource(R.string.tailnet_leave_forget_body, view.name), color = Colors.muted, fontSize = TextStyles.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLeave(forget) }) {
                Text(stringResource(if (forget) R.string.tailnet_leave_and_forget else R.string.tailscale_leave), color = Colors.danger)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
    )
}

private fun hostNames(hosts: List<SavedHost>) = hosts.joinToString(", ") { it.label.ifBlank { it.host } }

/** Why a host on the tailnet is off though nothing is wrong with it: nothing connects it on its own. */
sealed interface HostParked {
    /** Its tailnet was disconnected by hand. */
    data class TailnetOff(val network: String) : HostParked

    /** It names an account its tailnet does not run now. */
    data class OtherAccount(val account: String) : HostParked
}

/** Why [host] stays off with [view] as it is, or null when nothing of the tailnet keeps it off. */
internal fun hostParked(host: SavedHost, view: TailnetView): HostParked? {
    if (!host.viaTailscale || !view.joined) return null
    val named = host.tailnetProfile.takeIf { it.isNotBlank() && it != view.account }
        ?.let { id -> view.accounts.firstOrNull { it.id == id } }
    return when {
        named != null -> HostParked.OtherAccount(named.name)
        view.off -> HostParked.TailnetOff(view.name)
        else -> null
    }
}

/** The line that says why: "Tailscale is off" or "On account Work". */
@Composable
internal fun parkedText(parked: HostParked): String = when (parked) {
    is HostParked.TailnetOff -> stringResource(R.string.host_parked_tailnet_off, parked.network)
    is HostParked.OtherAccount -> stringResource(R.string.host_parked_other_account, parked.account)
}

/** The action that clears it: Connect Tailscale, or Use Work. */
@Composable
internal fun parkedAction(parked: HostParked): String = when (parked) {
    is HostParked.TailnetOff -> stringResource(R.string.host_parked_connect_tailnet, parked.network)
    is HostParked.OtherAccount -> stringResource(R.string.host_parked_use_account, parked.account)
}

/**
 * Why a running tailnet stays up, after who uses it: kept up while the app is open, until a while
 * after its last host, never stopped, or Always in the background. Null while it is not up, and
 * while it lingers (the line says when it stops then).
 */
@Composable
internal fun tailnetKeptReason(view: TailnetView, backgroundMode: Boolean): String? {
    if (!view.running || view.off || (view.lingerUntil != null && view.hosts.isEmpty())) return null
    val policy = view.policy
    val linger = policy.lingerMs
    val open = when {
        policy.use == TailnetUse.WhileOpen -> stringResource(R.string.tailnet_kept_open)
        linger == null -> stringResource(R.string.tailnet_kept_never)
        view.hosts.isNotEmpty() -> (linger / 60_000L).toInt().let { pluralStringResource(R.plurals.tailnet_stops_after_last, it, it) }
        else -> null
    }
    val away = if (backgroundMode && policy.keep == BackgroundKeep.Always) stringResource(R.string.tailnet_kept_always) else null
    return listOfNotNull(open, away).joinToString(" · ").ifBlank { null }
}

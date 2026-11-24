package me.leaver.rexpocket.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.HostRoute
import me.leaver.rexpocket.core.HostRoutes
import me.leaver.rexpocket.core.PublicKeyList
import me.leaver.rexpocket.core.RouteSkip
import me.leaver.rexpocket.core.SshKeys

internal const val OTHER_ADDRESSES_TAG = "other-addresses"
internal const val TRY_OTHERS_TAG = "try-other-addresses"
internal const val ADD_ADDRESS_TAG = "add-other-address"
internal const val GENERATE_KEY_TAG = "generate-key"
internal const val SHOW_PUBLIC_KEY_TAG = "show-public-key"
internal const val PUBLIC_KEY_TAG = "public-key"

/** The words for why one of a host's other addresses is not dialed. */
@Composable
internal fun skipText(skip: RouteSkip): String = stringResource(
    when (skip) {
        RouteSkip.Unreadable -> R.string.other_address_unreadable
        RouteSkip.OtherKind -> R.string.other_address_other_kind
        RouteSkip.Weaker -> R.string.other_address_weaker
        RouteSkip.Same -> R.string.other_address_same
    },
)

/**
 * The host dialog's Other addresses: Try other addresses, then each address in the order it is
 * tried, with Move up, Move down and Remove (buttons, and TalkBack actions on the row), and a field
 * to add one. [main] is the host's own address as typed, which decides what the others may be.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OtherAddressesEditor(
    fallbacks: List<String>,
    tryOthers: Boolean,
    main: HostRoute?,
    sshUser: String,
    onChange: (List<String>) -> Unit,
    onTryOthers: (Boolean) -> Unit,
) {
    var adding by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag(OTHER_ADDRESSES_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.other_addresses_title), color = Colors.ink, fontSize = TextStyles.body)
        if (fallbacks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Radius.card))
                    .toggleable(value = tryOthers, role = Role.Switch) { onTryOthers(it) }
                    .testTag(TRY_OTHERS_TAG)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.other_addresses_try), color = Colors.ink, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
                Switch(checked = tryOthers, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Colors.accent))
            }
        }
        Text(
            stringResource(if (main?.endpoint?.viaSsh == true) R.string.other_addresses_hint_ssh else R.string.other_addresses_hint),
            color = Colors.muted,
            fontSize = TextStyles.label,
        )
        val up = stringResource(R.string.other_address_up)
        val down = stringResource(R.string.other_address_down)
        val remove = stringResource(R.string.other_address_remove)
        fallbacks.forEachIndexed { i, raw ->
            fun move(to: Int) = onChange(fallbacks.toMutableList().apply { add(to, removeAt(i)) })
            val skip = main?.let { HostRoutes.fallback(raw, it, sshUser).second }
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    customActions = listOfNotNull(
                        CustomAccessibilityAction(up) { move(i - 1); true }.takeIf { i > 0 },
                        CustomAccessibilityAction(down) { move(i + 1); true }.takeIf { i < fallbacks.lastIndex },
                        CustomAccessibilityAction(remove) { onChange(fallbacks.filterIndexed { j, _ -> j != i }); true },
                    )
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${i + 1}. $raw", color = if (skip == null && tryOthers) Colors.ink else Colors.muted, fontSize = TextStyles.label)
                    if (skip != null) Text(skipText(skip), color = Colors.amber, fontSize = TextStyles.label)
                }
                IconButton(onClick = { move(i - 1) }, enabled = i > 0, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = up, tint = if (i > 0) Colors.accent else Colors.muted)
                }
                IconButton(onClick = { move(i + 1) }, enabled = i < fallbacks.lastIndex, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = down, tint = if (i < fallbacks.lastIndex) Colors.accent else Colors.muted)
                }
                IconButton(onClick = { onChange(fallbacks.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = remove, tint = Colors.danger)
                }
            }
        }
        val parsedNew = adding.trim().takeIf { it.isNotEmpty() }?.let { text -> main?.let { HostRoutes.fallback(text, it, sshUser) } }
        val canAdd = adding.isNotBlank() && fallbacks.none { it.equals(adding.trim(), ignoreCase = true) } && parsedNew?.second != RouteSkip.Unreadable
        fun add() {
            if (!canAdd) return
            // Kept as the phone writes addresses, so the list reads the same as an import's.
            onChange(fallbacks + (parsedNew?.first?.key ?: adding.trim()))
            adding = ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Field(
                    stringResource(R.string.other_address_field),
                    adding,
                    placeholder = stringResource(if (main?.endpoint?.viaSsh == true) R.string.other_address_placeholder_ssh else R.string.other_address_placeholder),
                    keyboardType = KeyboardType.Uri,
                    autoCorrect = false,
                    imeAction = ImeAction.Done,
                    onDone = { add() },
                ) { adding = it }
            }
            TextButton(onClick = { add() }, enabled = canAdd, modifier = Modifier.heightIn(min = 48.dp).testTag(ADD_ADDRESS_TAG)) {
                Text(stringResource(R.string.other_address_add), color = if (canAdd) Colors.accent else Colors.muted)
            }
        }
        parsedNew?.second?.let { Text(skipText(it), color = Colors.amber, fontSize = TextStyles.label) }
    }
}

/**
 * Generate key and Show public key, for an SSH host signing in with a key: a fresh Ed25519 key
 * (replacing one asks first), and the public half of the key the host has, with Copy and Share in
 * the `# name` and key line form Import hosts uses. The private key never leaves the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SshKeyActions(
    hostName: String,
    privateKey: String,
    passphrase: String,
    onGenerated: (String) -> Unit,
) {
    val context = LocalContext.current
    var confirmReplace by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    val name = hostName.ifBlank { "host" }

    fun generate() {
        val key = SshKeys.generate("rexpocket:$name")
        onGenerated(key.privateKey)
        unreadable = false
        shown = key.publicKey
    }

    FlowRow(verticalArrangement = Arrangement.Center) {
        TextButton(
            onClick = { if (privateKey.isNotBlank()) confirmReplace = true else generate() },
            modifier = Modifier.heightIn(min = 48.dp).testTag(GENERATE_KEY_TAG),
        ) { Text(stringResource(R.string.ssh_key_generate), color = Colors.accent) }
        if (privateKey.isNotBlank()) {
            TextButton(
                onClick = {
                    val line = SshKeys.publicKeyOf(privateKey, passphrase, "rexpocket:$name")
                    unreadable = line == null
                    shown = line
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag(SHOW_PUBLIC_KEY_TAG),
            ) { Text(stringResource(R.string.ssh_key_show_public), color = Colors.accent) }
        }
    }
    if (unreadable) Text(stringResource(R.string.ssh_key_public_unreadable), color = Colors.amber, fontSize = TextStyles.label)
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.ssh_key_replace_title), color = Colors.ink) },
            text = { Text(stringResource(R.string.ssh_key_replace_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmReplace = false; generate() }) { Text(stringResource(R.string.ssh_key_replace), color = Colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) } },
        )
    }
    shown?.let { line ->
        // The form Import hosts gives: a `# name` line over the key line, which authorized_keys reads as a comment.
        val text = PublicKeyList.authorizedKeys(listOf(PublicKeyList.Entry(name, line))).trimEnd()
        AlertDialog(
            onDismissRequest = { shown = null },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.ssh_key_public_title, name), color = Colors.ink) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ssh_key_public_hint), color = Colors.muted, fontSize = TextStyles.label)
                    SelectionContainer {
                        Text(text, color = Colors.ink, fontSize = TextStyles.label, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(PUBLIC_KEY_TAG))
                    }
                    FlowRow {
                        TextButton(onClick = { clipboardSet(context, text); toast(context, context.getString(R.string.keys_copied)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.keys_copy), color = Colors.accent)
                        }
                        TextButton(onClick = { shareText(context, text) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.keys_share), color = Colors.accent)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.keys_done), color = Colors.accent) } },
        )
    }
}

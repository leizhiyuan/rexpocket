package me.leaver.rexpocket.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.leaver.rexpocket.core.HostAddress
import me.leaver.rexpocket.core.HostAddressParser
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailscaleSettings
import me.leaver.rexpocket.net.TailscaleStatus

// The host list, the tailnet row, and the dialogs that add a host or join the tailnet.

@Composable
internal fun HomeScreen(
    hosts: List<SavedHost>,
    tailscale: TailscaleSettings,
    tailscaleStatus: TailscaleStatus,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (SavedHost) -> Unit,
    onTailscale: () -> Unit,
) {
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(Space.xxl))
        Text(
            stringResource(R.string.home_hosts),
            color = Colors.ink,
            fontSize = TextStyles.display,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Space.s))
        Text(
            stringResource(R.string.home_subtitle),
            color = Colors.muted,
            fontSize = TextStyles.body,
            lineHeight = bodyLeading,
        )
        Spacer(Modifier.height(Space.xl))
        TailscaleRow(tailscale, tailscaleStatus, onOpen = onTailscale)
        Spacer(Modifier.height(Space.l))
        if (hosts.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Colors.surface).padding(16.dp),
            ) {
                Text(stringResource(R.string.home_no_hosts), color = Colors.ink, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.home_no_hosts_body),
                    color = Colors.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = TextStyles.label,
                    lineHeight = labelLeading,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(hosts, key = { it.id }) { host ->
                    EntryCard(
                        title = host.label,
                        detail = "${host.host}:${host.port}",
                        tags = listOfNotNull(
                            stringResource(
                                if (host.plaintext) R.string.host_transport_plain else R.string.host_transport_tls,
                            ),
                            stringResource(R.string.tailscale_title).takeIf { host.viaTailscale },
                        ),
                        trailing = stringResource(R.string.action_edit),
                        onOpen = { onOpen(host) },
                        onTrailing = { editing = host },
                    )
                }
            }
        }
        if (hosts.isEmpty()) Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.action_add_host),
            color = Colors.onAccent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp)
                .clip(RoundedCornerShape(Radius.card)).background(Colors.accent).clickable { creating = true }
                .padding(vertical = 14.dp),
            fontWeight = FontWeight.SemiBold,
            fontSize = TextStyles.body,
        )
    }
    if (creating || editing != null) {
        HostDialog(
            initial = editing,
            tailscaleEnabled = tailscale.enabled,
            onDismiss = { creating = false; editing = null },
            onDelete = editing?.let { host -> { onDelete(host.id); creating = false; editing = null } },
            onSave = {
                onSave(it)
                creating = false
                editing = null
            },
            onJoinTailnet = {
                creating = false
                editing = null
                onTailscale()
            },
        )
    }
}

/**
 * The tailnet row: joining is a thing you do on purpose, so the row says whether the phone is in a
 * tailnet and under which name, and tapping it asks for that name once.
 */
@Composable
internal fun TailscaleRow(settings: TailscaleSettings, status: TailscaleStatus, onOpen: () -> Unit) {
    val state = tailnetState(status)
    val joined = settings.enabled && (status.backendState == "Running")
    val detail = when {
        !settings.enabled -> stringResource(R.string.tailscale_not_joined)
        state.isNotEmpty() -> state
        // Joined, but the node is not up right now: say so instead of pretending to start.
        else -> stringResource(R.string.tailscale_state_stopped)
    }
    // The phone's own node is not a destination, so it carries no card: it is a line of state that
    // opens the node's settings, and it sits on the paper instead of above it.
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onOpen)
            .padding(horizontal = Space.s, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tailscale_title), color = Colors.muted, fontSize = TextStyles.label)
            Spacer(Modifier.height(2.dp))
            Text(
                settings.hostname.ifBlank { status.networkName }.ifBlank { stringResource(R.string.tailscale_no_name) },
                color = Colors.ink,
                fontSize = TextStyles.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            detail,
            color = if (joined) Colors.accent else if (settings.enabled) Colors.amber else Colors.muted,
            fontSize = TextStyles.label,
        )
        Spacer(Modifier.width(Space.xs))
        Text("›", color = Colors.muted, fontSize = TextStyles.body)
    }
}

/** Tailscale's backend state in the app's own words; empty while nothing is running. */
@Composable
internal fun tailnetState(status: TailscaleStatus): String = when (status.backendState) {
    "" -> ""
    "Running" -> stringResource(R.string.tailscale_state_running)
    "Starting" -> stringResource(R.string.tailscale_state_starting)
    "NeedsLogin" -> stringResource(R.string.tailscale_state_needs_login)
    "NeedsMachineAuth" -> stringResource(R.string.tailscale_state_needs_approval)
    "Stopped", "NoState" -> stringResource(R.string.tailscale_state_stopped)
    else -> status.text.ifBlank { status.backendState }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TailscaleDialog(
    initial: TailscaleSettings,
    status: TailscaleStatus,
    error: String?,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    var hostname by remember { mutableStateOf(initial.hostname) }
    val state = tailnetState(status)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.tailscale_title), color = Colors.ink) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Field(stringResource(R.string.tailscale_hostname), hostname) { hostname = it }
                if (initial.enabled) {
                    Text(
                        listOfNotNull(
                            state.ifBlank { null },
                            status.networkName.ifBlank { null },
                        ).joinToString(" · ").ifBlank { stringResource(R.string.tailscale_state_starting) },
                        color = if (status.backendState == "Running") Colors.accent else Colors.amber,
                        fontSize = TextStyles.label,
                    )
                }
                error?.let { Text(it, color = Colors.danger, fontSize = TextStyles.label) }
                if (status.authUrl.isNotBlank()) {
                    Text(
                        status.authUrl,
                        color = Colors.accent,
                        fontSize = TextStyles.label,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.combinedClickable(
                            onClick = { openLink(context, status.authUrl) },
                            onLongClick = {
                                clipboardSet(context, status.authUrl)
                                toast(context, context.getString(R.string.tailscale_login_copied))
                            },
                        ),
                    )
                    Text(stringResource(R.string.tailscale_login_hint), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                }
                Text(stringResource(R.string.tailscale_hint), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(hostname) }) {
                Text(
                    stringResource(if (initial.enabled) R.string.action_save else R.string.tailscale_join),
                    color = Colors.accent,
                )
            }
        },
        dismissButton = {
            Row {
                if (initial.enabled) {
                    TextButton(onClick = onLeave) { Text(stringResource(R.string.tailscale_leave), color = Colors.danger) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
}

/**
 * Adding a host is a name and an address. The port, the scheme and whether the address has to go
 * through the built-in Tailscale node all follow from the address, and the line under the field
 * says what the app made of it.
 */
@Composable
internal fun HostDialog(
    initial: SavedHost?,
    tailscaleEnabled: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
    onJoinTailnet: () -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var address by remember {
        mutableStateOf(
            initial?.let { host ->
                val scheme = if (host.plaintext) "ws" else "wss"
                "$scheme://${host.host}:${host.port}"
            } ?: "",
        )
    }
    val parsed = remember(address) { HostAddressParser.parse(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = {
            Text(
                if (initial == null) stringResource(R.string.action_add_host) else stringResource(R.string.action_edit_host),
                color = Colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.field_name), label) { label = it }
                Field(stringResource(R.string.field_address), address) { address = it }
                if (parsed != null) {
                    Text(
                        listOfNotNull(
                            "${parsed.host}:${parsed.port}",
                            stringResource(if (parsed.plaintext) R.string.host_transport_plain else R.string.host_transport_tls),
                            stringResource(R.string.tailscale_title).takeIf { parsed.viaTailscale },
                        ).joinToString(" · "),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                } else if (address.isNotBlank()) {
                    Text(stringResource(R.string.host_address_invalid), color = Colors.danger, fontSize = TextStyles.label)
                }
                val tailnetTarget = parsed
                if (tailnetTarget != null && tailnetTarget.viaTailscale && !tailscaleEnabled) {
                    Text(
                        stringResource(R.string.tailscale_need_join_host),
                        color = Colors.amber,
                        fontSize = TextStyles.label,
                        modifier = Modifier.clickable {
                            onSave(asSavedHost(initial, label, tailnetTarget, viaTailscale = true))
                            onJoinTailnet()
                        },
                    )
                }
                if (parsed?.plaintext == true) {
                    Text(stringResource(R.string.field_plaintext_warning), color = Colors.amber, fontSize = TextStyles.label)
                }
            }
        },
        confirmButton = {
            // Nothing to save until the address says where to connect.
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let { onSave(asSavedHost(initial, label, it, viaTailscale = it.viaTailscale)) } },
            ) { Text(stringResource(R.string.action_save), color = if (parsed != null) Colors.accent else Colors.muted) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = Colors.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
    if (confirmDelete && onDelete != null && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.confirm_delete_host, initial.label), color = Colors.ink) },
            text = { Text(stringResource(R.string.confirm_delete_host_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = Colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

internal fun asSavedHost(
    initial: SavedHost?,
    label: String,
    parsed: HostAddress,
    viaTailscale: Boolean,
) = SavedHost(
    id = initial?.id ?: HostStore.newId(),
    label = label.trim().ifBlank { parsed.host },
    host = parsed.host,
    port = parsed.port,
    plaintext = parsed.plaintext,
    viaTailscale = viaTailscale,
)

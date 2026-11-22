package me.leaver.rexpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ConnectProgress
import me.leaver.rexpocket.core.ConnectStep
import me.leaver.rexpocket.core.PeerLink
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetPeer
import me.leaver.rexpocket.core.TailnetStage
import me.leaver.rexpocket.core.needsAttention
import me.leaver.rexpocket.core.step
import me.leaver.rexpocket.core.tailnetSteps
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.net.TailnetStatus

// The tailnet as the home screen shows it: one line at the top of the list that stays quiet while
// the phone is on the network and gets loud only when the person has something to do, a sheet with
// the node's details and the network's devices, and the steps of a connection to a host.

/** The tag of the tailnet line on the home screen, for the screen tests. */
internal const val TAILNET_CARD_TAG = "tailnet-card"

/** The tag of the device list in the tailnet sheet. */
internal const val TAILNET_DEVICE_TAG = "tailnet-device"

/** The stage in the app's words, as one short line. */
@Composable
internal fun tailnetStageText(stage: TailnetStage, backend: TailnetBackend): String {
    val network = tailnetName(backend)
    return when (stage) {
        TailnetStage.Off -> stringResource(R.string.tailnet_stage_off)
        TailnetStage.Starting -> stringResource(R.string.tailnet_stage_starting, network)
        is TailnetStage.SigningIn ->
            if (stage.authUrl.isBlank()) stringResource(R.string.tailnet_sign_in_waiting)
            else stringResource(R.string.tailnet_stage_signing_in, network)
        TailnetStage.NeedsApproval -> stringResource(R.string.tailnet_stage_needs_approval)
        TailnetStage.Connecting -> stringResource(R.string.tailnet_stage_connecting, network)
        is TailnetStage.Connected ->
            if (stage.network.isBlank()) stringResource(R.string.tailnet_stage_connected, stage.device)
            else stringResource(R.string.tailnet_stage_connected_on, stage.device, stage.network)
        TailnetStage.Stopped -> stringResource(R.string.tailnet_stage_stopped, network)
        is TailnetStage.Failed -> stringResource(R.string.tailnet_stage_failed, network)
    }
}

/** "Step 2 of 3" for a stage on its way, or null. */
@Composable
private fun stepText(stage: TailnetStage, backend: TailnetBackend): String? {
    val steps = tailnetSteps(backend)
    val at = stage.step()?.let { steps.indexOf(it) }?.takeIf { it >= 0 } ?: return null
    return stringResource(R.string.tailnet_step, at + 1, steps.size)
}

/** How many devices are online, or how many there are where the node cannot tell. */
@Composable
private fun devicesText(peers: List<TailnetPeer>): String {
    return if (peers.any { it.online != null }) {
        stringResource(R.string.tailnet_devices_online, peers.count { it.online == true }, peers.size)
    } else {
        pluralStringResource(R.plurals.tailnet_devices, peers.size, peers.size)
    }
}

/**
 * The tailnet's line at the top of the home list. On the network it is one quiet row: a green dot,
 * the device this phone is, and how many devices are online; a tap opens the details. On its way it
 * says which step it is on. When the person has something to do - sign in, wait for an admin, fix a
 * start that failed - it is a card with the action on it. Nothing at all when the phone has not
 * joined and no saved host needs the tailnet.
 */
@Composable
internal fun TailnetStatusCard(
    backend: TailnetBackend,
    stage: TailnetStage,
    status: TailnetStatus,
    hostsNeedingTailnet: Int,
    onDetails: () -> Unit,
    onSignIn: (String) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (stage == TailnetStage.Off && hostsNeedingTailnet == 0) return
    val context = LocalContext.current
    val network = tailnetName(backend)
    val title = tailnetStageText(stage, backend)
    val shape = RoundedCornerShape(Radius.card)
    val loud = stage.needsAttention
    val tint = when (stage) {
        is TailnetStage.Failed -> Colors.danger
        else -> Colors.amber
    }
    Column(
        modifier.fillMaxWidth().clip(shape).background(Colors.surface)
            .then(if (loud) Modifier.border(1.dp, tint, shape) else Modifier)
            .testTag(TAILNET_CARD_TAG),
    ) {
        when {
            stage == TailnetStage.Off -> QuietRow(
                lead = { Glyph(R.drawable.ic_tailnet, Colors.muted, size = 20.dp) },
                title = stringResource(R.string.tailnet_title) + " · " + title,
                supporting = pluralStringResource(R.plurals.tailnet_hosts_need_join, hostsNeedingTailnet, hostsNeedingTailnet),
                supportingColor = Colors.muted,
                onClick = onSettings,
                trailing = { TextButton(onClick = onSettings) { Text(stringResource(R.string.action_join), color = Colors.accent) } },
            )
            stage is TailnetStage.Connected -> {
                val trouble = status.health.firstOrNull()
                QuietRow(
                    lead = { Dot(if (trouble != null) Colors.amber else Colors.ok, 10.dp) },
                    title = "$network · $title",
                    supporting = trouble ?: devicesText(status.peers),
                    supportingColor = if (trouble != null) Colors.amber else Colors.muted,
                    onClick = onDetails,
                    trailing = { Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 20.dp) },
                    spoken = stringResource(R.string.tailnet_open_details),
                )
            }
            !loud -> {
                // On its way: starting, connecting, or waiting for the node's sign-in link.
                val steps = tailnetSteps(backend)
                val at = stage.step()?.let { steps.indexOf(it) } ?: 0
                QuietRow(
                    lead = { CircularProgressIndicator(Modifier.size(18.dp), color = Colors.accent, strokeWidth = 2.dp, trackColor = Colors.line) },
                    title = title,
                    supporting = listOfNotNull(stepText(stage, backend), status.health.firstOrNull()).joinToString(" · "),
                    supportingColor = Colors.muted,
                    onClick = onDetails,
                    trailing = { Glyph(R.drawable.ic_chevron_right, Colors.muted, size = 20.dp) },
                )
                LinearProgressIndicator(
                    progress = { (at + 0.5f) / steps.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Colors.accent,
                    trackColor = Colors.line,
                    drawStopIndicator = {},
                )
            }
            else -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(R.drawable.ic_tailnet, tint, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(title, color = Colors.ink, fontSize = TextStyles.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    stepText(stage, backend)?.let { Text(it, color = Colors.muted, fontSize = TextStyles.label) }
                }
                Spacer(Modifier.height(6.dp))
                val body = when (stage) {
                    is TailnetStage.SigningIn -> stringResource(R.string.tailnet_sign_in_body)
                    TailnetStage.NeedsApproval -> stringResource(R.string.tailnet_approval_body)
                    is TailnetStage.Failed -> stage.message.ifBlank { stringResource(R.string.tailnet_start_failed) }
                    else -> stringResource(R.string.tailnet_stopped_body)
                }
                Text(body, color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading, modifier = Modifier.padding(start = 32.dp))
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (stage) {
                        is TailnetStage.SigningIn -> {
                            TextButton(onClick = {
                                clipboardSet(context, stage.authUrl)
                                toast(context, context.getString(R.string.tailscale_login_copied))
                            }) { Text(stringResource(R.string.action_copy_link), color = Colors.accent) }
                            FilledAction(stringResource(R.string.action_sign_in)) { onSignIn(stage.authUrl) }
                        }
                        TailnetStage.NeedsApproval -> TextButton(onClick = onDetails) {
                            Text(stringResource(R.string.tailnet_open_details), color = Colors.accent)
                        }
                        is TailnetStage.Failed -> {
                            TextButton(onClick = onSettings) { Text(stringResource(R.string.action_tailnet_settings), color = Colors.accent) }
                            FilledAction(stringResource(R.string.action_try_again), onRetry)
                        }
                        else -> FilledAction(stringResource(R.string.action_tailnet_settings), onSettings)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilledAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Colors.accent, contentColor = Colors.onAccent),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun QuietRow(
    lead: @Composable () -> Unit,
    title: String,
    supporting: String,
    supportingColor: Color,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    spoken: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = "$title. $supporting. $spoken" } else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Colors.ink, fontSize = TextStyles.subhead, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting.isNotBlank()) {
                Text(supporting, color = supportingColor, fontSize = TextStyles.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** What a device's dot and status say: online, offline since when, or how the node reaches it. */
private data class PeerLook(val color: Color, val text: String)

@Composable
private fun peerLook(peer: TailnetPeer): PeerLook {
    val link = linkText(peer)
    return when (peer.online) {
        true -> PeerLook(Colors.ok, listOfNotNull(stringResource(R.string.tailnet_peer_online), link).joinToString(" · "))
        false -> PeerLook(
            Colors.line,
            peer.lastSeen?.let { stringResource(R.string.tailnet_peer_last_seen, relativeTime(it)) }
                ?: stringResource(R.string.tailnet_peer_offline),
        )
        // Netbird cannot say whether a device is online, only how its own connection to it stands.
        null -> PeerLook(
            when (peer.link) {
                PeerLink.Direct, PeerLink.Relay, PeerLink.PeerRelay, PeerLink.Derp -> Colors.ok
                PeerLink.Connecting -> Colors.amber
                else -> Colors.muted
            },
            link.orEmpty(),
        )
    }
}

/** How the node's traffic reaches the device right now, when it has sent any. */
@Composable
private fun linkText(peer: TailnetPeer): String? {
    val ms = peer.latencyMs?.let { " ${it.toInt()} ms" }.orEmpty()
    return when (peer.link) {
        PeerLink.Direct -> stringResource(R.string.tailnet_link_direct) + ms
        PeerLink.PeerRelay -> stringResource(R.string.tailnet_link_peer_relay) + ms
        PeerLink.Derp -> stringResource(R.string.tailnet_link_derp, peer.linkDetail) + ms
        PeerLink.Relay -> stringResource(R.string.tailnet_link_relay) + ms
        PeerLink.Connecting -> stringResource(R.string.tailnet_link_connecting)
        PeerLink.Idle, PeerLink.Unknown -> null
    }
}

/** An operating system as the node names it, the way people write it. */
internal fun osName(os: String): String = when (os.lowercase()) {
    "linux" -> "Linux"
    "windows" -> "Windows"
    "android" -> "Android"
    "macos" -> "macOS"
    "ios" -> "iOS"
    "freebsd" -> "FreeBSD"
    else -> os
}

/** The path the last probe measured, in the words the host's line uses. */
@Composable
internal fun pathText(path: TailnetPath, network: String): Pair<String, Color> {
    val ms = path.latencyMs?.let { " · ${it.toInt()} ms" }.orEmpty()
    return when (path) {
        is TailnetPath.Direct -> stringResource(R.string.tailnet_path_direct, network) + ms to Colors.ok
        is TailnetPath.PeerRelay -> stringResource(R.string.tailnet_path_peer_relay, network) + ms to Colors.amber
        is TailnetPath.Derp ->
            stringResource(R.string.tailnet_path_derp, network, path.regionCode.ifBlank { path.regionId.toString() }) + ms to Colors.amber
        is TailnetPath.Relay -> stringResource(R.string.tailnet_path_relay, network) + ms to Colors.amber
        is TailnetPath.Unreachable -> stringResource(R.string.tailnet_path_unreachable, network, path.reason) to Colors.danger
    }
}

/**
 * The tailnet's details: where the node is, the device this phone is on the network as - its name,
 * account, addresses, home relay and key expiry - the route to the open host, what the node says is
 * wrong, and the network's devices. A device that is a saved host connects on a tap; any other is
 * offered as a new host.
 */
@Composable
internal fun TailnetSheet(
    backend: TailnetBackend,
    stage: TailnetStage,
    status: TailnetStatus,
    hosts: List<SavedHost>,
    openHost: SavedHost?,
    path: TailnetPath?,
    onDismiss: () -> Unit,
    onSignIn: (String) -> Unit,
    onSettings: () -> Unit,
    onRetry: () -> Unit,
    onProbe: () -> Unit,
    onPeer: (TailnetPeer, SavedHost?) -> Unit,
) {
    val context = LocalContext.current
    val network = tailnetName(backend)
    val copied = stringResource(R.string.tailnet_copied)
    val copy: (String) -> Unit = { text ->
        clipboardSet(context, text)
        toast(context, copied)
    }
    ActionSheet(onDismiss = onDismiss, title = network) {
        Group(stringResource(R.string.tailnet_section_this_phone), icons = false) {
            val (dot, detail) = when {
                stage is TailnetStage.Connected -> Colors.ok to null
                stage is TailnetStage.Failed -> Colors.danger to stage.message.ifBlank { null }
                stage.needsAttention -> Colors.amber to stepText(stage, backend)
                else -> Colors.muted to stepText(stage, backend)
            }
            InfoRow(stringResource(R.string.tailnet_field_status), tailnetStageText(stage, backend), supporting = detail, dot = dot)
            if (stage is TailnetStage.SigningIn && stage.authUrl.isNotBlank()) {
                SheetItem(stringResource(R.string.action_sign_in), Colors.accent) { onSignIn(stage.authUrl) }
            }
            if (stage is TailnetStage.Failed) SheetItem(stringResource(R.string.action_try_again), Colors.accent, onClick = onRetry)
            status.networkName.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.tailnet_field_network), it, onClick = { copy(it) }) }
            status.self?.let { self ->
                InfoRow(stringResource(R.string.tailnet_field_device), self.name, supporting = self.dnsName.takeIf { it.isNotBlank() }, onClick = { copy(self.dnsName.ifBlank { self.name }) })
                self.user.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.tailnet_field_user), it) }
                if (self.ips.isNotEmpty()) {
                    InfoRow(stringResource(R.string.tailnet_field_addresses), self.ips.joinToString("\n"), mono = true, onClick = { copy(self.ips.first()) })
                }
                self.homeRelay.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.tailnet_field_relay), "DERP $it") }
                self.keyExpiry?.let { InfoRow(stringResource(R.string.tailnet_field_key_expiry), relativeTime(it)) }
            }
            if (openHost != null && openHost.viaTailscale && stage is TailnetStage.Connected) {
                val (text, color) = path?.let { pathText(it, network) } ?: (stringResource(R.string.tailnet_route_measure) to Colors.accent)
                InfoRow(stringResource(R.string.tailnet_field_route, openHost.label), text, valueColor = color, onClick = onProbe)
            }
            if (status.health.isNotEmpty()) {
                InfoRow(stringResource(R.string.tailnet_field_health), status.health.joinToString("\n"), valueColor = Colors.amber)
            }
            SheetItem(stringResource(R.string.action_tailnet_settings), onClick = onSettings)
        }
        val peers = status.peers.sortedWith(
            compareBy<TailnetPeer>(
                { p -> if (hosts.any { p.answersTo(it.host) }) 0 else 1 },
                { p -> when (p.online) { true -> 0; null -> 1; false -> 2 } },
                { it.name.lowercase() },
            ),
        )
        Group(
            if (peers.isEmpty()) stringResource(R.string.tailnet_section_devices)
            else stringResource(R.string.tailnet_section_devices_count, devicesText(peers)),
            icons = true,
        ) {
            when {
                peers.isNotEmpty() -> peers.forEach { peer ->
                    val saved = hosts.firstOrNull { peer.answersTo(it.host) }
                    PeerRow(peer, saved) { onPeer(peer, saved) }
                }
                else -> Text(
                    stringResource(if (status.peersKnown) R.string.tailnet_no_devices else R.string.tailnet_devices_waiting),
                    color = Colors.muted,
                    fontSize = TextStyles.label,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One fact about the node: its name above, its value below, copied on a tap where that helps. */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    supporting: String? = null,
    dot: Color? = null,
    mono: Boolean = false,
    valueColor: Color = Colors.ink,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Colors.muted, fontSize = TextStyles.label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Dot(dot, 8.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    value,
                    color = valueColor,
                    fontSize = TextStyles.body,
                    fontFamily = if (mono) FontFamily.Monospace else null,
                )
            }
            if (supporting != null) Text(supporting, color = Colors.muted, fontSize = TextStyles.label)
        }
    }
}

/** A device of the tailnet: its dot, name, system and status, and its MagicDNS name and address. */
@Composable
private fun SheetScope.PeerRow(peer: TailnetPeer, saved: SavedHost?, onClick: () -> Unit) {
    val look = peerLook(peer)
    val action = stringResource(if (saved != null) R.string.tailnet_peer_connect else R.string.tailnet_peer_add)
    val rex = stringResource(R.string.tailnet_peer_rex_host)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { close(onClick) }
            .semantics(mergeDescendants = true) {}
            .testTag(TAILNET_DEVICE_TAG)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Dot(look.color, 10.dp) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    peer.name,
                    color = if (peer.online == false) Colors.muted else Colors.ink,
                    fontSize = TextStyles.body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (saved != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rex,
                        color = Colors.accent,
                        fontSize = TextStyles.label,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Colors.accent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val line = listOf(osName(peer.os), look.text).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text(line, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(peer.dnsName.trimEnd('.'), peer.ips.firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                color = Colors.muted,
                fontSize = TextStyles.label,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(action, color = Colors.accent, fontSize = TextStyles.label)
        }
    }
}

/**
 * The step a connection to the open host is on, with how long it has taken so far, and a bar of the
 * steps: "Reaching mac over Tailscale… 1.2 s". Shown under the host while it connects.
 */
@Composable
internal fun ConnectProgressLine(progress: ConnectProgress, stage: TailnetStage, network: String, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.stepStartedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val host = progress.host.substringBefore('.').takeIf { it.isNotBlank() && !progress.host.first().isDigit() } ?: progress.host
    val text = when (progress.step) {
        ConnectStep.StartTailnet ->
            if (stage is TailnetStage.SigningIn || stage == TailnetStage.NeedsApproval) stringResource(R.string.connect_step_sign_in, network)
            else stringResource(R.string.connect_step_start_tailnet, network)
        ConnectStep.FindPeer -> stringResource(R.string.connect_step_find_peer, host, network)
        ConnectStep.Reach ->
            if (progress.viaTailnet) stringResource(R.string.connect_step_reach_tailnet, host, network)
            else stringResource(R.string.connect_step_reach, host)
        ConnectStep.OpenRex -> stringResource(R.string.connect_step_rex)
    }
    val seconds = ((now - progress.stepStartedAt).coerceAtLeast(0)) / 1000.0
    val steps = progress.steps
    val at = steps.indexOf(progress.step)
    Column(modifier.padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(12.dp), color = Colors.accent, strokeWidth = 1.5.dp, trackColor = Colors.line)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Colors.muted, fontSize = TextStyles.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.connect_elapsed, seconds), color = Colors.muted, fontSize = TextStyles.label)
        }
        Row(Modifier.padding(start = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEachIndexed { i, _ ->
                Box(
                    Modifier.width(16.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i <= at) Colors.accent else Colors.line),
                )
            }
        }
    }
}

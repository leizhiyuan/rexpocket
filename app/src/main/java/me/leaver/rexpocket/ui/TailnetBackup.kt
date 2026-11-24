package me.leaver.rexpocket.ui

import android.app.Application
import me.leaver.rexpocket.alerts.TileStore
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.ConnectableKind
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetChoices
import me.leaver.rexpocket.core.TileBinding
import me.leaver.rexpocket.data.SavedHost
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.SettingChange
import me.leaver.rexpocket.core.SettingKey
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetUse

// The settings backup's tailnet parts on the phone: each account's choices and the tiles, read for
// an export and written by an import. Names and choices only: an account an import adds has no
// sign-in until it is used. See docs/design/settings-backup.md.

/**
 * Every tailnet account the phone has and its choices: each account kept by name, and a backend's
 * first account where it was changed from the defaults or its tailnet is joined ([all]: every one, as an import compares them).
 */
internal fun tailnetChoicesOf(connections: Connections, all: Boolean = false): List<TailnetChoices> {
    val profiles = connections.profiles.value
    val policies = connections.control.policies.value
    val joined = connections.tailnet.value.takeIf { it.enabled }?.backend
    return TailnetBackend.entries.flatMap { backend ->
        profiles.of(backend).mapNotNull { p ->
            val policy = policies.of(p.id)
            val kept = all || !p.isFirst || !policy.isDefault || backend == joined || profiles.profiles.any { it.id == p.id }
            if (kept) TailnetChoices(backend.storageKey, p.name, p.isFirst, policy) else null
        }
    }
}

/** The tiles' bindings, a host's by its name; a tile whose item is gone is left out. */
internal fun tileBindingsOf(app: Application, hosts: List<SavedHost>): List<TileBinding> =
    TileStore(app).all().mapIndexedNotNull { i, item ->
        when (item?.kind) {
            ConnectableKind.Host -> hosts.firstOrNull { it.id == item.id }?.let { TileBinding(i + 1, host = it.label) }
            ConnectableKind.Tailnet -> TileBinding(i + 1, tailnet = item.id)
            null -> null
        }
    }

/** Writes each account's choices, adding an account by its name where the phone has none. */
internal fun applyTailnetChoices(connections: Connections, tailnets: List<TailnetChoices>) {
    for (t in tailnets) {
        val backend = TailnetBackend.entries.firstOrNull { it.storageKey == t.backend } ?: continue
        val id = connections.accountFor(backend, t.account, t.first)
        connections.control.setAccountPolicy(id, t.policy)
    }
}

/** Binds each tile of [tiles]: a host by [idOf] its name (none: the tile is left as it is). */
internal fun applyTiles(app: Application, tiles: List<TileBinding>, idOf: (String) -> String?) {
    for (t in tiles) {
        val host = t.host
        val tailnet = t.tailnet
        val item = when {
            host != null -> idOf(host)?.let { Connectable.host(it) }
            tailnet != null -> Connectable.tailnet(tailnet)
            else -> null
        } ?: continue
        TileStore.assign(app, t.slot, item)
    }
}

/** A tailnet account's choices in one line, as the import's preview shows them. */
@Composable
private fun policyText(p: TailnetPolicy): String {
    val use = stringResource(if (p.use == TailnetUse.WhileOpen) R.string.tailnet_use_open else R.string.tailnet_use_needed)
    val linger = stringResource(R.string.tailnet_linger) + " " + stringResource(
        when (p.lingerMs) {
            60_000L -> R.string.linger_1
            15 * 60_000L -> R.string.linger_15
            null -> R.string.linger_never
            else -> R.string.linger_5
        },
    )
    val keep = stringResource(
        when (p.keep) {
            BackgroundKeep.Follow -> R.string.bg_keep_tailnet_follow
            BackgroundKeep.Always -> R.string.bg_keep_tailnet_always
            BackgroundKeep.Off -> R.string.bg_keep_tailnet_off
        },
    )
    val notify = stringResource(R.string.tailnet_notify) + " " + stringResource(if (p.notify) R.string.alerts_on else R.string.alerts_off)
    return listOf(use, linger, keep, notify).joinToString(" · ")
}

/** What a tile shows, as the import's preview names it. */
@Composable
private fun tileText(t: TileBinding?): String = when {
    t == null -> stringResource(R.string.tile_free)
    t.host != null -> t.host.orEmpty()
    else -> TailnetBackend.entries.firstOrNull { it.storageKey == t.tailnet }?.displayName ?: t.tailnet.orEmpty()
}

/** The preview's old and new for a tailnet account's choices or a tile. */
@Composable
internal fun tailnetChangeText(c: SettingChange): String = when (c.key) {
    SettingKey.TailnetChoices -> {
        val new = policyText(c.new as TailnetPolicy)
        val old = c.old as? TailnetPolicy
        if (old == null) stringResource(R.string.backup_tailnet_new_account, new)
        else stringResource(R.string.import_change_short, policyText(old), new)
    }
    else -> stringResource(R.string.import_change_short, tileText(c.old as? TileBinding), tileText(c.new as? TileBinding))
}

package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject

// The settings backup's tailnet parts: each account's choices (by backend and account name) and
// the Quick Settings tiles (by slot, naming a host of the file or a tailnet by its backend). Only
// names and choices: never a sign-in, a node key, or whether the person disconnected it last.
// See docs/design/settings-backup.md.

/**
 * One tailnet account's choices: [backend] is its storage key, [account] the account's name, and
 * [first] whether it is the backend's first account (the one the app had before accounts), which
 * a phone always has whatever its name.
 */
data class TailnetChoices(val backend: String, val account: String, val first: Boolean, val policy: TailnetPolicy) {
    /** How the preview names it: "Tailscale" or "Tailscale · Work". */
    fun label(displayName: String): String = if (first && account == displayName) displayName else "$displayName · $account"

    fun sameAccount(other: TailnetChoices): Boolean =
        backend == other.backend && (if (first || other.first) first == other.first else account.trim().equals(other.account.trim(), ignoreCase = true))
}

/** A tile's binding: [host] by its name in the file, or [tailnet] by its backend's storage key. */
data class TileBinding(val slot: Int, val host: String? = null, val tailnet: String? = null) {
    fun same(other: TileBinding): Boolean =
        slot == other.slot && tailnet == other.tailnet && (host == other.host || host != null && other.host != null && host.equals(other.host, ignoreCase = true))
}

internal object TailnetBackupFormat {
    /** A name as long as an account's can sensibly be. */
    private const val MAX_NAME = 64

    private fun known(backend: String): Boolean = TailnetBackend.entries.any { it.storageKey == backend }

    /**
     * The `tailnets` part. An account of a backend this build does not have is a newer (or other)
     * app's: left out, as an unknown field is.
     */
    fun readTailnets(root: JSONObject, refuse: (SettingsProblem.Kind, String, String?) -> Nothing): List<TailnetChoices>? {
        val part = root.opt("tailnets") ?: return null
        if (part == JSONObject.NULL) return null
        val o = part as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, "tailnets", null)
        val list = o.opt("accounts") ?: return emptyList()
        if (list == JSONObject.NULL) return emptyList()
        val arr = list as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "tailnets.accounts", null)
        val out = ArrayList<TailnetChoices>()
        for (i in 0 until arr.length()) {
            val field = "tailnets.accounts[${i + 1}]"
            val a = arr.opt(i) as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, field, null)
            val backend = a.opt("backend") as? String ?: refuse(SettingsProblem.Kind.BadValue, "$field.backend", null)
            val name = (a.opt("name") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
                ?: refuse(SettingsProblem.Kind.BadValue, "$field.name", null)
            val first = when (val v = a.opt("first")) {
                null, JSONObject.NULL -> false
                is Boolean -> v
                else -> refuse(SettingsProblem.Kind.BadValue, "$field.first", v.toString())
            }
            val d = TailnetPolicy()
            val use = when (val v = a.opt("use")) {
                null, JSONObject.NULL -> d.use
                else -> TailnetUse.entries.firstOrNull { it.storageKey == v } ?: refuse(SettingsProblem.Kind.BadValue, "$field.use", v.toString())
            }
            val linger = if (!a.has("stopAfterMs")) d.lingerMs else when (val v = a.opt("stopAfterMs")) {
                JSONObject.NULL -> null
                is Number -> v.toLong().takeIf { (it == v.toDouble().toLong()) && it in TailnetPolicy.LINGER_CHOICES }
                    ?: refuse(SettingsProblem.Kind.BadValue, "$field.stopAfterMs", v.toString())
                else -> refuse(SettingsProblem.Kind.BadValue, "$field.stopAfterMs", v.toString())
            }
            val keep = when (val v = a.opt("keep")) {
                null, JSONObject.NULL -> d.keep
                else -> BackgroundKeep.entries.firstOrNull { it.storageKey == v } ?: refuse(SettingsProblem.Kind.BadValue, "$field.keep", v.toString())
            }
            val notify = when (val v = a.opt("notify")) {
                null, JSONObject.NULL -> d.notify
                is Boolean -> v
                else -> refuse(SettingsProblem.Kind.BadValue, "$field.notify", v.toString())
            }
            val choices = TailnetChoices(backend, name, first, TailnetPolicy(use, linger, keep, notify))
            if (out.any { it.sameAccount(choices) }) refuse(SettingsProblem.Kind.BadValue, "$field.name", name)
            out += choices
        }
        return out.filter { known(it.backend) }
    }

    /** The `tiles` part: each slot once, each item once, a host the file has. */
    fun readTiles(
        root: JSONObject,
        refuse: (SettingsProblem.Kind, String, String?) -> Nothing,
        host: (field: String, value: Any?) -> String,
    ): List<TileBinding>? {
        val part = root.opt("tiles") ?: return null
        if (part == JSONObject.NULL) return null
        val arr = part as? JSONArray ?: refuse(SettingsProblem.Kind.BadValue, "tiles", null)
        val out = ArrayList<TileBinding>()
        for (i in 0 until arr.length()) {
            val field = "tiles[${i + 1}]"
            val t = arr.opt(i) as? JSONObject ?: refuse(SettingsProblem.Kind.BadValue, field, null)
            val slot = (t.opt("slot") as? Number)?.takeIf { it is Int || it is Long }?.toInt()?.takeIf { it in 1..TILE_SLOTS }
                ?: refuse(SettingsProblem.Kind.BadValue, "$field.slot", t.opt("slot")?.toString())
            val hasHost = t.has("host") && !t.isNull("host")
            val hasTailnet = t.has("tailnet") && !t.isNull("tailnet")
            if (hasHost == hasTailnet) refuse(SettingsProblem.Kind.BadValue, field, null)
            val binding = if (hasHost) {
                TileBinding(slot, host = host("$field.host", t.opt("host")))
            } else {
                TileBinding(slot, tailnet = t.opt("tailnet") as? String ?: refuse(SettingsProblem.Kind.BadValue, "$field.tailnet", null))
            }
            if (out.any { it.slot == slot }) refuse(SettingsProblem.Kind.BadValue, "$field.slot", slot.toString())
            if (out.any { it.copy(slot = slot).same(binding) }) refuse(SettingsProblem.Kind.BadValue, field, null)
            out += binding
        }
        return out.filter { it.tailnet == null || known(it.tailnet) }
    }

    fun writeTailnets(root: JSONObject, tailnets: List<TailnetChoices>?) {
        if (tailnets == null) return
        val arr = JSONArray()
        for (t in tailnets) {
            arr.put(
                JSONObject().put("backend", t.backend).put("name", t.account).put("first", t.first)
                    .put("use", t.policy.use.storageKey)
                    .put("stopAfterMs", t.policy.lingerMs ?: JSONObject.NULL)
                    .put("keep", t.policy.keep.storageKey)
                    .put("notify", t.policy.notify),
            )
        }
        root.put("tailnets", JSONObject().put("accounts", arr))
    }

    /** Only the tiles whose host went into the file, or that show a tailnet. */
    fun writeTiles(root: JSONObject, tiles: List<TileBinding>?, written: Set<String>) {
        if (tiles == null) return
        val arr = JSONArray()
        for (t in tiles.sortedBy { it.slot }) {
            when {
                t.host != null -> if (t.host in written) arr.put(JSONObject().put("slot", t.slot).put("host", t.host))
                t.tailnet != null -> arr.put(JSONObject().put("slot", t.slot).put("tailnet", t.tailnet))
            }
        }
        root.put("tiles", arr)
    }

    /** The Quick Settings tiles the app has. */
    const val TILE_SLOTS = 3
}

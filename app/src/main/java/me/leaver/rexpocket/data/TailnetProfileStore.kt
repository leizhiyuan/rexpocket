package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetProfile
import me.leaver.rexpocket.core.TailnetProfiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tailnet accounts by name, and which one each backend runs. Only names and ids: each
 * account's login and node key stay in its node's own directory, which no backup or export reads.
 * See docs/design/tailnet-control.md, "Accounts".
 */
class TailnetProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun read(): TailnetProfiles {
        val o = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return TailnetProfiles()
        val list = o.optJSONArray("profiles") ?: JSONArray()
        val profiles = (0 until list.length()).mapNotNull { i ->
            val p = list.optJSONObject(i) ?: return@mapNotNull null
            val id = p.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TailnetProfile(id, TailnetBackend.fromStorage(p.optString("backend")), p.optString("name"))
        }
        val active = o.optJSONObject("active") ?: JSONObject()
        val removed = o.optJSONArray("removedFirst") ?: JSONArray()
        return TailnetProfiles(
            profiles,
            active.keys().asSequence().associate { TailnetBackend.fromStorage(it) to active.optString(it) },
            (0 until removed.length()).mapNotNull { i -> TailnetBackend.entries.firstOrNull { it.storageKey == removed.optString(i) } }.toSet(),
        )
    }

    fun write(next: TailnetProfiles) {
        val list = JSONArray()
        for (p in next.profiles) list.put(JSONObject().put("id", p.id).put("backend", p.backend.storageKey).put("name", p.name))
        val active = JSONObject()
        for ((b, id) in next.active) active.put(b.storageKey, id)
        val o = JSONObject().put("profiles", list).put("active", active)
        if (next.removedFirst.isNotEmpty()) o.put("removedFirst", JSONArray(next.removedFirst.map { it.storageKey }.sorted()))
        // Committed: a restart without a screen starts the active account's node.
        prefs.edit().putString(KEY, o.toString()).commit()
    }

    private companion object {
        const val KEY = "tailnet.profiles"
    }
}

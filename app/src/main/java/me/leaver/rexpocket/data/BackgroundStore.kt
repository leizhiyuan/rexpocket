package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.AlertEvent
import me.leaver.rexpocket.core.BackgroundHosts
import me.leaver.rexpocket.core.HostBackground
import org.json.JSONObject

/**
 * Each host's background choices (Settings › Background, a host's page): kept beside the hosts, in
 * the same preferences, under a key of their own. A host with the defaults has no entry; a deleted
 * host's entry is dropped when it is next read against the list. See docs/design/multi-host.md.
 */
class BackgroundStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun hosts(): BackgroundHosts {
        val raw = prefs.getString(KEY, null) ?: return BackgroundHosts()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return BackgroundHosts()
        val hosts = buildMap {
            for (id in o.keys()) {
                val h = o.optJSONObject(id) ?: continue
                val events = h.optJSONObject("events")
                val overrides = buildMap {
                    if (events != null) for (name in events.keys()) {
                        val event = AlertEvent.entries.firstOrNull { it.name == name } ?: continue
                        put(event, events.optBoolean(name))
                    }
                }
                put(id, HostBackground(h.optBoolean("stay", true), h.optBoolean("always", false), h.optBoolean("notify", true), overrides))
            }
        }
        return BackgroundHosts(hosts)
    }

    fun setHosts(next: BackgroundHosts) {
        val o = JSONObject()
        for ((id, h) in next.hosts) {
            o.put(
                id,
                JSONObject()
                    .put("stay", h.stay)
                    .put("always", h.always)
                    .put("notify", h.notify)
                    .put("events", JSONObject().apply { h.overrides.forEach { (e, on) -> put(e.name, on) } }),
            )
        }
        // Committed, not applied: Android may start the app again without a screen at any moment,
        // and that start reads this to decide what to connect.
        prefs.edit().putString(KEY, o.toString()).commit()
    }

    private companion object {
        const val KEY = "background.hosts"
    }
}

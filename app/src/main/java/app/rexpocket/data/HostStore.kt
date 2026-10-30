package app.rexpocket.data

import android.content.Context
import app.rexpocket.core.ListedSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedHost(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val plaintext: Boolean,
    /** Reach this host through the built-in Tailscale node instead of the phone's network. */
    val viaTailscale: Boolean = false,
)

/** Settings for the Tailscale node the app runs itself: joined, and the name it goes by. */
data class TailscaleSettings(
    val enabled: Boolean = false,
    val hostname: String = "",
)

class HostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)

    fun list(): List<SavedHost> {
        val raw = prefs.getString(KEY_HOSTS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    SavedHost(
                        id = o.optString("id"),
                        label = o.optString("label"),
                        host = o.optString("host"),
                        port = o.optInt("port", 7297),
                        plaintext = o.optBoolean("plaintext", true),
                        viaTailscale = o.optBoolean("viaTailscale", false),
                    ),
                )
            }
        }.filter { it.id.isNotBlank() && it.host.isNotBlank() }
    }

    fun upsert(host: SavedHost) {
        val next = list().filter { it.id != host.id } + host
        write(next)
    }

    fun delete(id: String) {
        write(list().filter { it.id != id })
        prefs.edit().remove(lastKey(id)).remove(panesKey(id)).remove(sessionsKey(id)).apply()
    }

    /**
     * The session list this host had last time, so the picker can draw it while the connection is
     * still opening instead of showing an empty screen for a second.
     */
    fun cachedSessions(hostId: String): List<ListedSession> {
        val raw = prefs.getString(sessionsKey(hostId), null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(ListedSession(id, o.optString("label")))
            }
        }
    }

    fun setCachedSessions(hostId: String, sessions: List<ListedSession>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
        prefs.edit().putString(sessionsKey(hostId), arr.toString()).apply()
    }

    fun lastSession(hostId: String): String? = prefs.getString(lastKey(hostId), null)

    fun setLastSession(hostId: String, sessionId: String?) {
        prefs.edit().apply {
            if (sessionId.isNullOrBlank()) remove(lastKey(hostId)) else putString(lastKey(hostId), sessionId)
        }.apply()
    }

    /** Host the app should reconnect to when it starts again after being killed. */
    fun lastHost(): String? = prefs.getString(KEY_LAST_HOST, null)

    fun setLastHost(id: String?) {
        prefs.edit().apply {
            if (id.isNullOrBlank()) remove(KEY_LAST_HOST) else putString(KEY_LAST_HOST, id)
        }.apply()
    }

    fun find(id: String): SavedHost? = list().firstOrNull { it.id == id }

    /** The pane the phone last showed in [sessionId], so reopening the session goes back to it. */
    fun lastPane(hostId: String, sessionId: String): String? =
        panes(hostId).optString(sessionId).takeIf { it.isNotEmpty() }

    /**
     * Remembers the pane shown in [sessionId]. Entries for sessions missing from [alive] are
     * dropped, so destroyed sessions do not pile up.
     */
    fun setLastPane(hostId: String, sessionId: String, blockId: String, alive: Collection<String>) {
        val current = panes(hostId)
        val next = JSONObject()
        current.keys().forEach { key -> if (key in alive) next.put(key, current.getString(key)) }
        next.put(sessionId, blockId)
        prefs.edit().putString(panesKey(hostId), next.toString()).apply()
    }

    private fun panes(hostId: String): JSONObject =
        JSONObject(prefs.getString(panesKey(hostId), null) ?: "{}")

    fun tailscale(): TailscaleSettings = TailscaleSettings(
        enabled = prefs.getBoolean(KEY_TAILSCALE_ENABLED, false),
        hostname = prefs.getString(KEY_TAILSCALE_HOSTNAME, "").orEmpty(),
    )

    fun setTailscale(settings: TailscaleSettings) {
        prefs.edit()
            .putBoolean(KEY_TAILSCALE_ENABLED, settings.enabled)
            .putString(KEY_TAILSCALE_HOSTNAME, settings.hostname.trim())
            .apply()
    }

    fun fontScale(): Float = prefs.getFloat(KEY_FONT, 1f)

    fun setFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT, scale.coerceIn(0.7f, 1.8f)).apply()
    }

    private fun write(hosts: List<SavedHost>) {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(
                JSONObject()
                    .put("id", h.id)
                    .put("label", h.label)
                    .put("host", h.host)
                    .put("port", h.port)
                    .put("plaintext", h.plaintext)
                    .put("viaTailscale", h.viaTailscale),
            )
        }
        prefs.edit().putString(KEY_HOSTS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_HOSTS = "hosts"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_FONT = "font_scale"
        private const val KEY_TAILSCALE_ENABLED = "tailscale_enabled"
        private const val KEY_TAILSCALE_HOSTNAME = "tailscale_hostname"
        fun newId(): String = UUID.randomUUID().toString()
        private fun lastKey(id: String) = "last:$id"
        private fun panesKey(id: String) = "panes:$id"
        private fun sessionsKey(id: String) = "sessions:$id"
    }
}

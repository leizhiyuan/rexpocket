package me.leaver.rexpocket.data

import android.content.Context
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextSize
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.ListedSession
import me.leaver.rexpocket.core.TailnetBackend
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedHost(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val plaintext: Boolean,
    /** Reach this host through the built-in tailnet node instead of the phone's network. */
    val viaTailscale: Boolean = false,
    /** The SSH account when Rex is reached over SSH, empty when Rex listens on a port. */
    val sshUser: String = "",
    val sshPassword: String = "",
    val sshPrivateKey: String = "",
    val sshPassphrase: String = "",
    /** The `SHA256:...` the person confirmed, empty until the first connection asks. */
    val sshHostKey: String = "",
) {
    val viaSsh: Boolean get() = sshUser.isNotBlank()
}

/**
 * Settings for the tailnet node the app runs itself: which backend, whether it is joined, and the
 * name it goes by. [managementUrl] is the Netbird management server, blank for Netbird Cloud.
 */
data class TailnetSettings(
    val backend: TailnetBackend = TailnetBackend.Tailscale,
    val enabled: Boolean = false,
    val hostname: String = "",
    val managementUrl: String = "",
)

/**
 * Which hosts and sessions the home list has folded. A session is named with its host
 * ([sessionKey]): two hosts can each have a session with the same id.
 */
data class HomeFolds(val hosts: Set<String> = emptySet(), val sessions: Set<String> = emptySet()) {
    /** Keeps the session folds of [hostId] whose session is in [alive], and every other host's. */
    fun keepSessions(hostId: String, alive: Collection<String>): HomeFolds {
        val prefix = sessionKey(hostId, "")
        val keep = alive.map { sessionKey(hostId, it) }.toSet()
        return copy(sessions = sessions.filter { !it.startsWith(prefix) || it in keep }.toSet())
    }

    companion object {
        fun sessionKey(hostId: String, sessionId: String) = "$hostId/$sessionId"
    }
}

class HostStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)
    private val secrets = SecretBox()

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
                        sshUser = o.optString("sshUser", ""),
                        sshPassword = secrets.open(o.optString("sshPassword", "")),
                        sshPrivateKey = secrets.open(o.optString("sshPrivateKey", "")),
                        sshPassphrase = secrets.open(o.optString("sshPassphrase", "")),
                        sshHostKey = o.optString("sshHostKey", ""),
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

    /**
     * The tailnet settings. The enabled flag and the name keep the keys the Tailscale-only app
     * stored them under, so an installed app keeps its join; a missing backend is Tailscale.
     */
    fun tailnet(): TailnetSettings = TailnetSettings(
        backend = TailnetBackend.fromStorage(prefs.getString(KEY_TAILNET_BACKEND, null)),
        enabled = prefs.getBoolean(KEY_TAILSCALE_ENABLED, false),
        hostname = prefs.getString(KEY_TAILSCALE_HOSTNAME, "").orEmpty(),
        managementUrl = prefs.getString(KEY_NETBIRD_MANAGEMENT_URL, "").orEmpty(),
    )

    fun setTailnet(settings: TailnetSettings) {
        prefs.edit()
            .putString(KEY_TAILNET_BACKEND, settings.backend.storageKey)
            .putBoolean(KEY_TAILSCALE_ENABLED, settings.enabled)
            .putString(KEY_TAILSCALE_HOSTNAME, settings.hostname.trim())
            .putString(KEY_NETBIRD_MANAGEMENT_URL, settings.managementUrl.trim())
            .apply()
    }

    /** Whether the app watches the open host in the background and alerts when a program needs you. */
    fun alerts(): Boolean = prefs.getBoolean(KEY_ALERTS, false)

    fun setAlerts(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS, on).apply()
    }

    /** Which kinds of alert the Notifications page has on; see [AlertPrefs] for the defaults. */
    fun alertPrefs(): AlertPrefs {
        val d = AlertPrefs()
        return AlertPrefs(
            waiting = prefs.getBoolean(KEY_ALERT_WAITING, d.waiting),
            failed = prefs.getBoolean(KEY_ALERT_FAILED, d.failed),
            done = prefs.getBoolean(KEY_ALERT_DONE, d.done),
            messages = prefs.getBoolean(KEY_ALERT_MESSAGES, d.messages),
            bells = prefs.getBoolean(KEY_ALERT_BELLS, d.bells),
            vibrate = prefs.getBoolean(KEY_ALERT_VIBRATE, d.vibrate),
        )
    }

    fun setAlertPrefs(p: AlertPrefs) {
        prefs.edit()
            .putBoolean(KEY_ALERT_WAITING, p.waiting)
            .putBoolean(KEY_ALERT_FAILED, p.failed)
            .putBoolean(KEY_ALERT_DONE, p.done)
            .putBoolean(KEY_ALERT_MESSAGES, p.messages)
            .putBoolean(KEY_ALERT_BELLS, p.bells)
            .putBoolean(KEY_ALERT_VIBRATE, p.vibrate)
            .apply()
    }

    /**
     * Whether the key bar stays on screen with a keyboard attached. Off by default: the bar is the
     * screen keyboard's, and a desk keyboard already has every key on it.
     */
    fun keyBarAlways(): Boolean = prefs.getBoolean(KEY_KEY_BAR, false)

    fun setKeyBarAlways(on: Boolean) {
        prefs.edit().putBoolean(KEY_KEY_BAR, on).apply()
    }

    /**
     * The key bar's keys and its key groups (see docs/design/terminal-controls.md, 4.1). The first
     * read after an upgrade builds them from what came before - the Shift, Alt and C-c switches, or
     * the last version's order and hidden keys with today's Fn - and stores them, so the bar stays as
     * it was. The old keys are kept in their old form for an older build to read.
     */
    fun keyArrangement(): KeyArrangement {
        val order = prefs.getString(KEY_KEY_BAR_ORDER, null)
        val hidden = prefs.getString(KEY_KEY_BAR_HIDDEN, null)
        val groups = prefs.getString(KEY_KEY_BAR_GROUPS, null)
        val arrangement = KeyArrangement.fromStored(
            order = order,
            hidden = hidden,
            groups = groups,
            groupKeys = { id -> prefs.getString(groupKeysPref(id), null) },
            groupName = { id -> prefs.getString(groupNamePref(id), null) },
            legacyShift = prefs.getBoolean(KEY_KEY_BAR_SHIFT, false),
            legacyAlt = prefs.getBoolean(KEY_KEY_BAR_ALT, false),
            legacyCtrlCHidden = prefs.getString(KEY_KEY_BAR_CTRL_C, null) == CtrlCKey.Hidden.pref,
        )
        if (order == null || hidden == null || groups == null) setKeyArrangement(arrangement)
        return arrangement
    }

    fun setKeyArrangement(arrangement: KeyArrangement) {
        val before = prefs.getString(KEY_KEY_BAR_GROUPS, null)?.split(',').orEmpty()
        val edit = prefs.edit()
            .putString(KEY_KEY_BAR_ORDER, arrangement.storedOrder())
            .putString(KEY_KEY_BAR_HIDDEN, arrangement.storedHidden())
            .putString(KEY_KEY_BAR_GROUPS, arrangement.storedGroups())
        for (group in arrangement.groups) {
            edit.putString(groupKeysPref(group.id), arrangement.storedGroupKeys(group.id))
            edit.putString(groupNamePref(group.id), group.name)
        }
        // A group deleted leaves nothing behind, so one made again with its id starts afresh.
        for (id in before.map { it.trim() }.filter { id -> BarKey.validGroupId(id) && arrangement.group(id) == null }) {
            edit.remove(groupKeysPref(id)).remove(groupNamePref(id))
        }
        edit.apply()
    }

    private fun groupKeysPref(id: String) = "keybar_group_$id"
    private fun groupNamePref(id: String) = "keybar_group_${id}_name"

    /**
     * What a tap, a double tap and a long press on the terminal do, and their times. The double tap
     * was a switch for full screen before it had choices; that switch decides it until one is made,
     * and is left in place for an older build to read.
     */
    fun gestures(): GestureSettings = GestureSettings.fromStored(
        tap = prefs.getString(KEY_GESTURE_TAP, null),
        doubleTap = prefs.getString(KEY_GESTURE_DOUBLE_TAP, null),
        longPress = prefs.getString(KEY_GESTURE_LONG_PRESS, null),
        waitForDoubleTap = prefs.getBoolean(KEY_GESTURE_WAIT, false),
        doubleTapMs = prefs.getInt(KEY_GESTURE_DOUBLE_TAP_MS, 0),
        longPressMs = prefs.getInt(KEY_GESTURE_LONG_PRESS_MS, 0),
        legacyDoubleTapFullScreen = if (prefs.contains(KEY_DOUBLE_TAP_FULL_SCREEN)) prefs.getBoolean(KEY_DOUBLE_TAP_FULL_SCREEN, true) else null,
    )

    fun setGestures(gestures: GestureSettings) {
        prefs.edit()
            .putString(KEY_GESTURE_TAP, gestures.tap.pref)
            .putString(KEY_GESTURE_DOUBLE_TAP, gestures.doubleTap.pref)
            .putString(KEY_GESTURE_LONG_PRESS, gestures.longPress.pref)
            .putBoolean(KEY_GESTURE_WAIT, gestures.waitForDoubleTap)
            .putInt(KEY_GESTURE_DOUBLE_TAP_MS, gestures.doubleTapMs)
            .putInt(KEY_GESTURE_LONG_PRESS_MS, gestures.longPressMs)
            .apply()
    }

    /**
     * How the C-c key behaves; a tap sends it unless the person chose otherwise. Whether it shows
     * at all is the key arrangement's now: a "hidden" stored before that reads as a tap.
     */
    fun keyBarCtrlC(): CtrlCKey = CtrlCKey.fromPref(prefs.getString(KEY_KEY_BAR_CTRL_C, null))
        .let { if (it == CtrlCKey.Hidden) CtrlCKey.Tap else it }

    fun setKeyBarCtrlC(choice: CtrlCKey) {
        prefs.edit().putString(KEY_KEY_BAR_CTRL_C, choice.pref).apply()
    }

    /** The folds of the home list, kept across restarts like any other preference. */
    fun folds(): HomeFolds = HomeFolds(
        hosts = prefs.getStringSet(KEY_FOLDED_HOSTS, null).orEmpty().toSet(),
        sessions = prefs.getStringSet(KEY_FOLDED_SESSIONS, null).orEmpty().toSet(),
    )

    fun setFolds(folds: HomeFolds) {
        prefs.edit()
            .putStringSet(KEY_FOLDED_HOSTS, folds.hosts.toSet())
            .putStringSet(KEY_FOLDED_SESSIONS, folds.sessions.toSet())
            .apply()
    }

    /**
     * Whether every launch goes back to the tab that was open last. Off by default: a fresh start
     * shows the list, and only a process Android killed on the terminal comes back to it.
     */
    fun reopenLastTab(): Boolean = prefs.getBoolean(KEY_REOPEN_LAST_TAB, false)

    fun setReopenLastTab(on: Boolean) {
        prefs.edit().putBoolean(KEY_REOPEN_LAST_TAB, on).apply()
    }

    /**
     * Settings › Appearance: the app's theme, the terminal's colours and the app's text size; the
     * phone's, the app's and the phone's until set.
     */
    fun appearance(): Appearance = Appearance(
        app = AppTheme.fromPref(prefs.getString("app_theme", null)),
        terminal = TerminalColors.fromPref(prefs.getString("terminal_colors", null)),
        text = TextSize.fromPref(prefs.getString("text_size", null)),
    )

    fun setAppearance(appearance: Appearance) {
        prefs.edit()
            .putString("app_theme", appearance.app.pref)
            .putString("terminal_colors", appearance.terminal.pref)
            .putString("text_size", appearance.text.pref)
            .apply()
    }

    fun fontScale(): Float = prefs.getFloat(KEY_FONT, 1f)

    fun terminalFont(): String = prefs.getString("terminal_font", null)
        ?: if (prefs.getBoolean("downloaded_font", false)) "jetbrains-mono" else SYSTEM_FONT

    fun setTerminalFont(id: String) {
        prefs.edit().putString("terminal_font", id).apply()
    }

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
                    .put("viaTailscale", h.viaTailscale)
                    .put("sshUser", h.sshUser)
                    .put("sshPassword", secrets.seal(h.sshPassword))
                    .put("sshPrivateKey", secrets.seal(h.sshPrivateKey))
                    .put("sshPassphrase", secrets.seal(h.sshPassphrase))
                    .put("sshHostKey", h.sshHostKey),
            )
        }
        prefs.edit().putString(KEY_HOSTS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_HOSTS = "hosts"
        private const val KEY_FOLDED_HOSTS = "folded_hosts"
        private const val KEY_FOLDED_SESSIONS = "folded_sessions"
        private const val KEY_REOPEN_LAST_TAB = "reopen_last_tab"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_FONT = "font_scale"
        private const val KEY_ALERTS = "background_alerts"
        private const val KEY_ALERT_WAITING = "alerts.waiting"
        private const val KEY_ALERT_FAILED = "alerts.failed"
        private const val KEY_ALERT_DONE = "alerts.done"
        private const val KEY_ALERT_MESSAGES = "alerts.messages"
        private const val KEY_ALERT_BELLS = "alerts.bells"
        private const val KEY_ALERT_VIBRATE = "alerts.vibrate"
        private const val KEY_KEY_BAR = "key_bar_always"
        private const val KEY_KEY_BAR_SHIFT = "keybar_shift"
        private const val KEY_KEY_BAR_ALT = "keybar_alt"
        private const val KEY_KEY_BAR_CTRL_C = "keybar_ctrl_c"
        private const val KEY_KEY_BAR_ORDER = "keybar_order"
        private const val KEY_KEY_BAR_HIDDEN = "keybar_hidden"
        private const val KEY_KEY_BAR_GROUPS = "keybar_groups"
        private const val KEY_DOUBLE_TAP_FULL_SCREEN = "double_tap_full_screen"
        private const val KEY_GESTURE_TAP = "gesture_tap"
        private const val KEY_GESTURE_DOUBLE_TAP = "gesture_double_tap"
        private const val KEY_GESTURE_LONG_PRESS = "gesture_long_press"
        private const val KEY_GESTURE_WAIT = "gesture_wait_double_tap"
        private const val KEY_GESTURE_DOUBLE_TAP_MS = "gesture_double_tap_ms"
        private const val KEY_GESTURE_LONG_PRESS_MS = "gesture_long_press_ms"
        private const val KEY_TAILSCALE_ENABLED = "tailscale_enabled"
        private const val KEY_TAILSCALE_HOSTNAME = "tailscale_hostname"
        private const val KEY_TAILNET_BACKEND = "tailnet_backend"
        private const val KEY_NETBIRD_MANAGEMENT_URL = "netbird_management_url"
        fun newId(): String = UUID.randomUUID().toString()
        private fun lastKey(id: String) = "last:$id"
        private fun panesKey(id: String) = "panes:$id"
        private fun sessionsKey(id: String) = "sessions:$id"
    }
}

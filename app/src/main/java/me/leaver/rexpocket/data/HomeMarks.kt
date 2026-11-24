package me.leaver.rexpocket.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * What the home keeps of its own, in a file of its own: which done and failed panes were seen, and
 * the filter it shows. A seen mark is by host and pane, and holds the report it was for
 * (`seenStamp`), so a new report of the pane is news again. See docs/design/home.md, "Seen".
 */
class HomeMarks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("home_marks", Context.MODE_PRIVATE)

    private val _seen = MutableStateFlow(load())

    /** The seen marks, by [key]: the report each mark is for. */
    val seen: StateFlow<Map<String, String>> = _seen

    /** The file changed under this - cleared, as a test's fresh start does - and the marks follow it. */
    private val follow = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY_SEEN) _seen.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(follow)
    }

    private fun load(): Map<String, String> {
        val raw = prefs.getString(KEY_SEEN, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
    }

    private fun write(next: Map<String, String>) {
        if (next == _seen.value) return
        _seen.value = next
        prefs.edit().putString(KEY_SEEN, JSONObject(next).toString()).apply()
    }

    /** Marks each pane of [marks] seen, for the report given. */
    @Synchronized
    fun mark(marks: Map<String, String>) = write(_seen.value + marks)

    /** Puts marks back as they were: a null leaves the pane unseen. The Undo of Mark as seen. */
    @Synchronized
    fun restore(previous: Map<String, String?>) {
        val next = _seen.value.toMutableMap()
        for ((key, stamp) in previous) if (stamp == null) next.remove(key) else next[key] = stamp
        write(next)
    }

    /** Keeps [hostId]'s marks of the panes in [alive] only, and every other host's. */
    @Synchronized
    fun prune(hostId: String, alive: Set<String>) {
        val prefix = key(hostId, "")
        write(_seen.value.filterKeys { !it.startsWith(prefix) || it.removePrefix(prefix) in alive })
    }

    /** Forgets every mark of [hostId]: a host that was removed. */
    @Synchronized
    fun forget(hostId: String) = prune(hostId, emptySet())

    /** The Activity view's arrangement: by session (true) or by priority. */
    fun activityBySession(): Boolean = prefs.getBoolean(KEY_BY_SESSION, false)

    /** The Activity view's Include idle: idle tabs always, not only until seen. */
    fun activityIncludeIdle(): Boolean = prefs.getBoolean(KEY_INCLUDE_IDLE, false)

    fun setActivity(bySession: Boolean, includeIdle: Boolean) {
        prefs.edit().putBoolean(KEY_BY_SESSION, bySession).putBoolean(KEY_INCLUDE_IDLE, includeIdle).apply()
    }

    /** The home's filter, by name: All, Activity, NeedsYou or Running. */
    fun filter(): String? = prefs.getString(KEY_FILTER, null)

    fun setFilter(name: String) {
        prefs.edit().putString(KEY_FILTER, name).apply()
    }

    companion object {
        private const val KEY_SEEN = "seen"
        private const val KEY_FILTER = "filter"
        private const val KEY_BY_SESSION = "activity.bySession"
        private const val KEY_INCLUDE_IDLE = "activity.includeIdle"

        fun key(hostId: String, blockId: String) = "$hostId/$blockId"
    }
}

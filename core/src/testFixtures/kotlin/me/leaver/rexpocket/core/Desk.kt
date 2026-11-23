package me.leaver.rexpocket.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Another client of the same Rex: the desk, or a second device. It changes sessions only through
 * Rex's protocol, so a scenario that uses it means the same against [ScriptedRex] and a real Rex.
 */
class Desk(private val endpoint: Endpoint) : AutoCloseable {
    private val client = RexClient()
    private val made = CopyOnWriteArrayList<String>()

    val id: String get() = client.welcome!!.clientId

    suspend fun connect() {
        client.connect(endpoint)
    }

    /** A new session of [tabs] shell tabs; its tabs and their panes, in order. */
    suspend fun newSession(tabs: Int = 2): Pair<String, List<Pair<String, String>>> {
        val sid = client.call(
            "session.create",
            JSONObject()
                .put("label", "rexpocket-scenario-" + System.nanoTime())
                .put("initial_windows", JSONArray().put(JSONObject().put("layout", SHELL))),
        ).getString("session_id")
        made += sid
        attach(sid)
        repeat(tabs - 1) { newTab(sid) }
        return sid to view(sid).windows.map { it.id to it.blocks.first().id }
    }

    suspend fun attach(sid: String) {
        client.call("session.attach", JSONObject().put("session_id", sid))
    }

    suspend fun view(sid: String): SessionView = parseView(client.call("session.view", JSONObject().put("session_id", sid)))

    /** A tab made at the desk, which the desk does not move to. */
    suspend fun newTab(sid: String): Pair<String, String> {
        val answer = client.call(
            "session.new_window",
            JSONObject().put("session_id", sid).put("focus", false).put("layout", SHELL),
        )
        val block = answer.getJSONArray("block_ids").getString(0)
        val window = view(sid).windows.first { w -> w.blocks.any { it.id == block } }.id
        return window to block
    }

    suspend fun split(sid: String, anchor: String): String = client.call(
        "session.new_split",
        JSONObject()
            .put("session_id", sid)
            .put("anchor_id", anchor)
            .put("direction", "vertical")
            .put("side", "after")
            .put("ratio", 0.5)
            .put("focus", false)
            .put("layout", SHELL),
    ).getJSONArray("block_ids").getString(0)

    suspend fun closeTab(sid: String, window: String) {
        attach(sid)
        client.call("session.close_window", JSONObject().put("session_id", sid).put("window_id", window))
    }

    /** Closes a pane, as its program exiting or the desk's close does. */
    suspend fun closePane(sid: String, block: String) {
        attach(sid)
        client.call("block.close", JSONObject().put("session_id", sid).put("block_id", block))
    }

    /** The desk takes the pane, as Rex does when its window there is focused. */
    suspend fun take(sid: String, block: String) {
        attach(sid)
        client.call(
            "session.own_block",
            JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)).put("mode", "take"),
        )
    }

    /** Who Rex says holds [block] - asked of the terminal, which the view does not always follow. */
    suspend fun ownerOf(sid: String, block: String): String? {
        attach(sid)
        return client.call("com.superlogical.terminal.size", JSONObject().put("session_id", sid).put("block_id", block))
            .optNullableString("owner")
    }

    /** Types [text] into [block] as a person at the desk would: on a real Rex its shell runs it. */
    suspend fun type(sid: String, block: String, text: String) {
        attach(sid)
        client.call(
            "com.superlogical.terminal.write",
            JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject().put("data", text)),
        )
    }

    /** The records the program in [block] reported, as Rex lists them. */
    suspend fun programStatus(sid: String, block: String): JSONArray {
        attach(sid)
        return client.call("com.superlogical.terminal.program_status", JSONObject().put("session_id", sid).put("block_id", block))
            .optJSONArray("records") ?: JSONArray()
    }

    /** What [block] shows, screen and scrollback, as Rex's `format` gives it as plain text. */
    suspend fun text(sid: String, block: String): String {
        attach(sid)
        return client.call(
            "block.method",
            JSONObject().put("session_id", sid).put("block_id", block).put("method", "format").put("args", JSONObject().put("format", "text")),
        ).optString("content")
    }

    /** Moves [sid] just before [before] in Rex's session order, or to the end, as dragging it at the desk does. */
    suspend fun moveSession(sid: String, before: String?) {
        attach(sid)
        client.call("session.move", JSONObject().put("session_id", sid).apply { if (before != null) put("before_session_id", before) })
    }

    /** Moves tab [window] of [sid] just before [before], or to the end. */
    suspend fun moveTab(sid: String, window: String, before: String?) {
        attach(sid)
        client.call(
            "session.move_window",
            JSONObject().put("session_id", sid).put("window_id", window).apply { if (before != null) put("before_window_id", before) },
        )
    }

    suspend fun rename(sid: String, label: String) {
        attach(sid)
        client.call("session.set_label", JSONObject().put("session_id", sid).put("label", label))
    }

    /** Rex's sessions, in its order. */
    suspend fun sessions(): List<ListedSession> = parseSessions(client.call("session.list").optJSONArray("sessions"))

    suspend fun destroy(sid: String) {
        runCatching { client.call("session.destroy", JSONObject().put("session_id", sid)) }
    }

    override fun close() {
        runBlocking { made.forEach { destroy(it) } }
        client.close()
    }

    private companion object {
        val SHELL: JSONObject get() = JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell"))
    }
}

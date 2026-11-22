package me.leaver.rexpocket.core

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A Rex server for tests, speaking the protocol the app speaks: the control connection on
 * `/api/control` (hello and welcome, requests and responses, events) and terminal streams on
 * `/api/data` (HELLO with a ticket or RESUME, WELCOME, a `GHOSTSNP` snapshot, then output). The
 * message shapes are the ones [RexClient], [PocketSession], [parseView] and [TerminalStream] read,
 * which were taken from a real Rex; what Rex does with them follows the rules the integration
 * tests in [PocketSessionTest] check against one:
 *
 * - Every connection is a client of its own, with a new id, even one that resumes with its
 *   reconnect token. A client's panes are let go when its connection ends.
 * - A pane call - own, disown, size, resize, write, a ticket - is refused unless the pane is in the
 *   session this connection attached, with "block … is not in the session". So is any call on a
 *   pane that closed: there is no pane to act on. [paneRefusal] is that message.
 * - Every change is told to every client as an event, the asking one included, after its answer.
 * - A closed pane's streams are closed with 4000; a HELLO or RESUME for it is refused with 0x03.
 * - Closing a tab's last pane closes the tab; closing a session's last tab destroys the session.
 *
 * Any client - the phone under test or a [Desk] - changes it through the protocol, so a scenario
 * written against this runs unchanged against a real Rex. [refuse] makes one method fail, for the
 * errors that must still be shown.
 */
class ScriptedRex : AutoCloseable {
    private class Tab(val id: String, var label: String, val blocks: MutableList<String>, var focused: String?)

    private class Session(val id: String, var label: String, val tabs: MutableList<Tab>, var revision: Long = 1) {
        var activeTab: String? = null
        fun tabOf(block: String) = tabs.firstOrNull { block in it.blocks }
        fun blocks() = tabs.flatMap { it.blocks }
    }

    private class Client(val id: String, val socket: WebSocket) {
        @Volatile var attached: String? = null
    }

    private class Stream(val block: String, val token: ByteString, val nonce: Long) {
        @Volatile var socket: WebSocket? = null
    }

    private val ids = AtomicLong()
    private val sessions = LinkedHashMap<String, Session>()
    private val owners = HashMap<String, String>()
    private val clients = ConcurrentHashMap<WebSocket, Client>()
    private val tickets = ConcurrentHashMap<String, Pair<String, String>>()
    private val streams = CopyOnWriteArrayList<Stream>()
    private val tokens = ConcurrentHashMap.newKeySet<String>()
    private val refusals = ConcurrentHashMap<String, String>()

    /** Every request that reached this Rex, as `method block-or-session`, in order. */
    val calls = CopyOnWriteArrayList<String>()

    /** What Rex says when a pane call names a pane that is not in the attached session. */
    fun paneRefusal(block: String) = "block $block is not in the session"

    private val server = MockWebServer()
    val endpoint: Endpoint

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/control" -> MockResponse().withWebSocketUpgrade(control())
                "/api/data" -> MockResponse()
                    .setHeader("Sec-WebSocket-Protocol", TerminalStream.PROTOCOL)
                    .withWebSocketUpgrade(data())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        endpoint = Endpoint("127.0.0.1", server.port, plaintext = true)
    }

    /** Makes [method] fail with [message] until [allow]; Rex refusing a pane that is still there. */
    fun refuse(method: String, message: String = "the terminal is busy") {
        refusals[method] = message
    }

    fun allow(method: String) {
        refusals.remove(method)
    }

    /** Ends [clientId]'s control connection, as its network going away; it resumes on a new one. */
    fun drop(clientId: String) {
        clients.filterValues { it.id == clientId }.keys.forEach { runCatching { it.close(1001, "network gone") } }
    }

    /** The client that holds [block], as Rex has it. */
    fun ownerOf(block: String): String? = synchronized(this) { owners[block] }

    /** Streams open for [block] now. */
    fun openStreams(block: String): Int = streams.count { it.block == block && it.socket != null }

    private fun next(kind: String) = "$kind:${ids.incrementAndGet()}"

    // ---- what the server does -------------------------------------------------------------

    private fun view(s: Session): JSONObject = JSONObject()
        .put("revision", s.revision)
        .put("session_id", s.id)
        .put("label", s.label)
        .put("active_window_id", s.activeTab ?: s.tabs.firstOrNull()?.id)
        .put(
            "windows",
            JSONArray().apply {
                for (tab in s.tabs) {
                    put(
                        JSONObject()
                            .put("window_id", tab.id)
                            .put("label", tab.label)
                            .put("focused_block_id", tab.focused ?: tab.blocks.firstOrNull())
                            .put(
                                "layers",
                                JSONArray().put(
                                    JSONObject().put(
                                        "blocks",
                                        JSONArray().apply {
                                            tab.blocks.forEach { b ->
                                                put(
                                                    JSONObject()
                                                        .put("block_id", b)
                                                        .put("creator_name", "com.superlogical.terminal")
                                                        .put("flavor", "com.superlogical.terminal.shell")
                                                        .put("owner", owners[b] ?: JSONObject.NULL),
                                                )
                                            }
                                        },
                                    ),
                                ),
                            ),
                    )
                }
            },
        )

    private fun list(): JSONArray = JSONArray().apply {
        sessions.values.forEach { put(JSONObject().put("session_id", it.id).put("label", it.label)) }
    }

    private class Refused(val code: String, val status: Int, message: String) : Exception(message)

    /** The events an answer causes, sent after it. */
    private val pending = ThreadLocal<MutableList<JSONObject>>()

    private fun announce(event: JSONObject) {
        pending.get()?.add(event) ?: broadcast(event)
    }

    private fun sessionEvent(sid: String, type: String, payload: JSONObject = JSONObject()) = announce(
        JSONObject().put("type", "session_event").put(
            "payload",
            JSONObject().put("session_id", sid).put("event", JSONObject().put("type", type).put("payload", payload)),
        ),
    )

    private fun changed(s: Session) {
        s.revision++
        sessionEvent(s.id, "session_view_changed", JSONObject().put("revision", s.revision))
    }

    private fun broadcast(event: JSONObject) {
        val text = JSONObject().put("type", "event").put("payload", event).toString()
        clients.keys.forEach { it.send(text) }
    }

    private fun session(payload: JSONObject): Session =
        sessions[payload.optString("session_id")] ?: throw Refused("session_not_found", 404, "no such session")

    /** The pane a pane call names, which must be in the session this client attached. */
    private fun pane(client: Client, payload: JSONObject, block: String = payload.optString("block_id")): Pair<Session, String> {
        val s = sessions[client.attached]
        if (s == null || s.id != payload.optString("session_id") || s.tabOf(block) == null) {
            throw Refused("invalid_request", 400, paneRefusal(block))
        }
        return s to block
    }

    private fun newTab(s: Session): Tab {
        val tab = Tab(next("window"), "shell", mutableListOf(next("block")), null)
        s.tabs += tab
        return tab
    }

    private fun closeBlock(s: Session, block: String, alone: Boolean = true) {
        val tab = s.tabOf(block) ?: return
        tab.blocks -= block
        if (tab.focused == block) tab.focused = tab.blocks.firstOrNull()
        endPane(block)
        sessionEvent(s.id, "block_closed", JSONObject().put("block_id", block))
        if (!alone) return
        if (tab.blocks.isEmpty()) closeTab(s, tab.id) else changed(s)
    }

    /** The pane is gone: nobody holds it, and its streams end the way Rex ends them. */
    private fun endPane(block: String) {
        owners -= block
        streams.filter { it.block == block }.forEach { stream ->
            stream.socket?.close(4000, "block closed")
            streams -= stream
        }
    }

    private fun closeTab(s: Session, window: String) {
        val tab = s.tabs.firstOrNull { it.id == window } ?: return
        tab.blocks.toList().forEach { closeBlock(s, it, alone = false) }
        s.tabs.remove(tab)
        if (s.activeTab == window) s.activeTab = s.tabs.firstOrNull()?.id
        sessionEvent(s.id, "window_closed", JSONObject().put("window_id", window))
        if (s.tabs.isEmpty()) destroy(s) else changed(s)
    }

    private fun destroy(s: Session) {
        if (sessions.remove(s.id) == null) return
        s.blocks().forEach(::endPane)
        announce(JSONObject().put("type", "session_destroyed").put("payload", JSONObject().put("session_id", s.id)))
    }

    private fun answer(client: Client, method: String, payload: JSONObject): JSONObject = synchronized(this) {
        refusals[method]?.let { throw Refused("internal", 500, it) }
        when (method) {
            "session.bootstrap" -> {
                val s = sessions[payload.optString("preferred_session_id")] ?: sessions.values.firstOrNull()
                client.attached = s?.id
                JSONObject()
                    .put("attach_all", JSONObject().put("sessions", list()))
                    .put(
                        "selected_session",
                        if (s == null) JSONObject() else JSONObject().put("session", JSONObject().put("session_id", s.id)).put("view", view(s)),
                    )
            }
            "session.attach" -> {
                val s = session(payload)
                client.attached = s.id
                JSONObject().put("view", view(s))
            }
            "session.view" -> view(session(payload))
            "session.list" -> JSONObject().put("sessions", list())
            "session.create" -> {
                val s = Session(next("session"), payload.optString("label").ifEmpty { "shell" }, mutableListOf())
                val count = payload.optJSONArray("initial_windows")?.length()?.coerceAtLeast(1) ?: 1
                repeat(count) { newTab(s) }
                sessions[s.id] = s
                announce(JSONObject().put("type", "session_created").put("payload", JSONObject().put("session", JSONObject().put("session_id", s.id).put("label", s.label))))
                JSONObject().put("session_id", s.id)
            }
            "session.new_window" -> {
                val s = session(payload)
                val tab = newTab(s)
                if (payload.optBoolean("focus", true)) s.activeTab = tab.id
                sessionEvent(s.id, "window_created", JSONObject().put("window_id", tab.id))
                changed(s)
                JSONObject().put("window_id", tab.id).put("block_ids", JSONArray(tab.blocks))
            }
            "session.new_split" -> {
                val s = session(payload)
                val tab = payload.optString("anchor_id").let { s.tabOf(it) } ?: s.tabs.first()
                val block = next("block")
                tab.blocks += block
                changed(s)
                JSONObject().put("block_ids", JSONArray().put(block))
            }
            "session.close_window" -> {
                val s = session(payload)
                val window = payload.optString("window_id")
                if (s.tabs.none { it.id == window }) throw Refused("not_found", 404, "window $window is not in the session")
                closeTab(s, window)
                JSONObject()
            }
            "block.close" -> {
                val (s, block) = pane(client, payload)
                closeBlock(s, block)
                JSONObject()
            }
            "session.destroy" -> {
                destroy(session(payload))
                JSONObject()
            }
            "session.own_block", "session.disown_block" -> {
                val blocks = payload.optJSONArray("block_ids") ?: JSONArray()
                val s = sessions[client.attached]
                for (i in 0 until blocks.length()) pane(client, payload, blocks.getString(i))
                for (i in 0 until blocks.length()) {
                    val block = blocks.getString(i)
                    if (method == "session.own_block") owners[block] = client.id else if (owners[block] == client.id) owners -= block
                }
                if (s != null) {
                    sessionEvent(s.id, "block_owner_changed")
                    changed(s)
                }
                JSONObject()
            }
            "com.superlogical.terminal.size" -> {
                val (_, block) = pane(client, payload)
                JSONObject().put("owner", owners[block] ?: JSONObject.NULL).put("columns", 80).put("rows", 24)
            }
            "com.superlogical.terminal.resize", "com.superlogical.terminal.write", "client.key", "block.event",
            "session.set_block_label" -> {
                pane(client, payload)
                JSONObject()
            }
            "com.superlogical.terminal.program_status" -> {
                pane(client, payload)
                JSONObject().put("records", JSONArray())
            }
            "com.superlogical.terminal.pwd", "com.superlogical.terminal.process", "com.superlogical.terminal.title" -> {
                pane(client, payload)
                JSONObject()
            }
            "data.connect" -> {
                val (_, block) = pane(client, payload)
                val bytes = ByteArray(16).also { java.util.Random().nextBytes(it) }
                val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                tickets[bytes.toByteString().hex()] = payload.optString("session_id") to block
                JSONObject().put("ticket", ticket)
            }
            "session.set_label" -> {
                val s = session(payload)
                s.label = payload.optString("label")
                sessionEvent(s.id, "session_label_changed", JSONObject().put("label", s.label))
                JSONObject()
            }
            "session.set_window_label" -> {
                val s = session(payload)
                s.tabs.firstOrNull { it.id == payload.optString("window_id") }?.label = payload.optString("label")
                changed(s)
                JSONObject()
            }
            else -> JSONObject()
        }
    }

    private fun control() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = gone(webSocket)

        private fun gone(webSocket: WebSocket) {
            val client = clients.remove(webSocket) ?: return
            // A client's panes are let go with its connection.
            synchronized(this@ScriptedRex) {
                val held = owners.filterValues { it == client.id }.keys
                owners.keys.removeAll(held)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "hello" -> {
                    val resumed = message.optString("reconnect_token").let { it.isNotEmpty() && it in tokens }
                    val token = next("token").also { tokens += it }
                    val client = Client(next("client"), webSocket)
                    clients[webSocket] = client
                    webSocket.send(
                        JSONObject()
                            .put("type", "welcome")
                            .put("api_revision", 7)
                            .put("limits", JSONObject().put("max_message_bytes", 1_048_576))
                            .put("client", JSONObject().put("id", client.id).put("reconnect_token", token).put("resumed", resumed))
                            .toString(),
                    )
                }
                "request" -> {
                    val client = clients[webSocket] ?: return
                    val id = message.getString("id")
                    val method = message.getString("method")
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    calls += "$method ${payload.optString("block_id").ifEmpty { payload.optJSONArray("block_ids")?.optString(0) ?: payload.optString("session_id") }}"
                    val events = mutableListOf<JSONObject>()
                    pending.set(events)
                    val reply = JSONObject().put("type", "response").put("id", id)
                    try {
                        reply.put("payload", answer(client, method, payload))
                    } catch (e: Refused) {
                        reply.put("error", JSONObject().put("code", e.code).put("status", e.status).put("message", e.message))
                    } finally {
                        pending.remove()
                    }
                    webSocket.send(reply.toString())
                    events.forEach(::broadcast)
                }
            }
        }
    }

    private fun data() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = gone(webSocket)

        private fun gone(webSocket: WebSocket) {
            streams.filter { it.socket === webSocket }.forEach { it.socket = null }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val hello = bytes.toByteArray()
            when (hello.firstOrNull()?.toInt()) {
                0x01 -> {
                    val (sid, block) = tickets.remove(hello.copyOfRange(1, 17).toByteString().hex())
                        ?: return reject(webSocket, 0x01)
                    if (!alive(sid, block)) return reject(webSocket, 0x03)
                    val stream = Stream(block, ByteArray(16).also { java.util.Random().nextBytes(it) }.toByteString(), ids.incrementAndGet())
                    stream.socket = webSocket
                    streams += stream
                    welcome(webSocket, stream)
                    webSocket.send(SNAPSHOT.toByteString())
                    webSocket.send("$ ".toByteArray().toByteString())
                }
                0x02 -> {
                    val token = hello.copyOfRange(1, 17).toByteString()
                    val stream = streams.firstOrNull { it.token == token } ?: return reject(webSocket, 0x03)
                    runCatching { stream.socket?.close(1000, "replaced") }
                    stream.socket = webSocket
                    welcome(webSocket, stream)
                }
                else -> reject(webSocket, 0x05)
            }
        }

        private fun alive(sid: String, block: String) = synchronized(this@ScriptedRex) { sessions[sid]?.tabOf(block) != null }

        private fun welcome(webSocket: WebSocket, stream: Stream) {
            val out = ByteArray(32)
            out[0] = 0x81.toByte()
            stream.token.toByteArray().copyInto(out, 1)
            for (i in 0 until 8) out[24 + i] = ((stream.nonce ushr (8 * i)) and 0xff).toByte()
            webSocket.send(out.toByteString())
        }

        private fun reject(webSocket: WebSocket, reason: Int) {
            webSocket.send(byteArrayOf(0x82.toByte(), reason.toByte()).toByteString())
            webSocket.close(1000, "rejected")
        }
    }

    override fun close() {
        clients.keys.forEach { runCatching { it.close(1001, "bye") } }
        server.shutdown()
    }

    private companion object {
        /** The smallest snapshot there is: the magic, a version, and the record that ends it. */
        val SNAPSHOT = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) + byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

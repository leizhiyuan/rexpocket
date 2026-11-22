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
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A Rex server for tests, speaking the protocol the app speaks: the control connection on
 * `/api/control` (hello and welcome, requests and responses, events) and terminal streams on
 * `/api/data` (HELLO with a ticket or RESUME, WELCOME, a `GHOSTSNP` snapshot, then output).
 *
 * What it sends and what it does follow a real Rex, 0.1.0-preview.22 (API revision 19, spoken to at
 * the app's revision 7), as recorded in docs/design/rex-parity.md, "Real Rex". [RexShapeTest] runs
 * one script against this and against the shapes recorded from that Rex, so the two cannot drift
 * apart unnoticed. In short:
 *
 * - A client is its connection's, with an id. A connection that drops without a close frame leaves
 *   the client waiting: its panes pass at once to the next client in line (`block_owner_changed`,
 *   reason `lost`), and a hello with its reconnect token resumes it - the same id, `resumed: true`,
 *   still attached, and its panes back (reason `restore`) unless their owner changed again since. A close frame ends the client:
 *   its panes go (reason `disconnect`) and its streams close with 1001 `data provider canceled`.
 * - `session.attach` and `session.bootstrap` attach; after `session.bootstrap` the connection may
 *   call methods on any session, but a ticket and owning need the session attached. Unattached, a
 *   call is `conflict` "stream is not attached to session …"; owning is `bad_request` "the caller
 *   must be a client attached to the session".
 * - Events go to the clients that may see them: a session's to the clients attached to it and to
 *   those that bootstrapped with `event_scope` `all` (block events too) or `session` (no block
 *   events of sessions not attached). Each carries a `seq`, one up per connection; a response's
 *   `seq` is the last event queued for it before the request. A change's events come before the
 *   answer to the call that made it.
 * - `session.create`'s first panes have no owner; a pane made by `session.new_window` or
 *   `session.new_split` belongs to the client that made it (reason `create`). An owner change is
 *   `block_owner_changed` with `owner` (left out when none), `previous` and `reason`, then
 *   `session_view_changed`. Disowning a pane someone else holds changes nothing.
 * - A gone pane: `block not found: <id>` (`not_found`) for its methods, `block.close` and
 *   `data.connect`; `block <id> is not in the session` (`bad_request`) for own and disown, which is
 *   [paneRefusal]; a gone tab is `window <id> not found`.
 * - Closing a tab: `window_closed`, `active_window_changed` (when it was the active one),
 *   `session_view_changed`, then `block_closed` for each of its panes. Closing a pane: `block_closed`,
 *   then, when it was its tab's last, `window_closed` and `active_window_changed`, then
 *   `session_view_changed`. A session with no tabs stays, with no `windows` in its view; only
 *   `session.destroy` ends it: each pane's `block_closed` and `session_view_changed`, then
 *   `session_destroyed`.
 * - A closed pane's streams close with 4000 `block closed`; a HELLO for it is refused with 0x03. A
 *   second stream of the same pane for the same client is refused with 0x02 and closed with 1008.
 * - Program status ([report]): `program_status_changed` with `id` ("" for the root record, whose
 *   record has no `id`), `record` (with `updated_at`, and `effective_app` when there is an app) and
 *   `reason` `report`; `program_status_removed` with `ids` and `reason`. A program exiting ([exit])
 *   drops its working and blocked records (reason `child_exited`), sends `child_exited`, and closes
 *   its pane. A program's failure is only `child_exited`: Rex makes no status record of it.
 *
 * Any client - the phone under test or a [Desk] - changes it through the protocol, so a scenario
 * written against this runs unchanged against a real Rex. [refuse] makes one method fail, for the
 * errors that must still be shown.
 */
class ScriptedRex(private val clock: () -> Long = System::currentTimeMillis) : AutoCloseable {
    private class Tab(val id: String, var label: String, val blocks: MutableList<String>, var focused: String?)

    private class Session(val id: String, var label: String, val tabs: MutableList<Tab>, var revision: Long = 1) {
        var activeTab: String? = null
        fun tabOf(block: String) = tabs.firstOrNull { block in it.blocks }
        fun blocks() = tabs.flatMap { it.blocks }
    }

    private class Client(val id: String) {
        @Volatile var socket: WebSocket? = null

        /** The sessions this client joined with `session.attach` or `session.bootstrap`; it stays in each. */
        val attached: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** `all` or `session` after `session.bootstrap` or `session.attach_all`: it may call methods on any session. */
        @Volatile var scope: String? = null

        /** The panes it held when its connection dropped, with who had each after: given back on a resume. */
        val lost: MutableMap<String, String?> = ConcurrentHashMap()

        val seq = AtomicLong()
    }

    private class Stream(val client: String, val block: String, val token: ByteString, val nonce: Long) {
        @Volatile var socket: WebSocket? = null
    }

    private val ids = AtomicLong()
    private val sessions = LinkedHashMap<String, Session>()
    private val owners = HashMap<String, String>()

    /** Who claimed each pane, oldest first, with how: `take`, `request`, or `create` for its maker. */
    private val claims = HashMap<String, MutableList<Pair<String, String>>>()
    private val clients = ConcurrentHashMap<WebSocket, Client>()
    private val tokens = ConcurrentHashMap<String, Client>()
    private val tickets = ConcurrentHashMap<String, Triple<String, String, String>>()
    private val streams = CopyOnWriteArrayList<Stream>()
    private val refusals = ConcurrentHashMap<String, String>()
    private val holds = ConcurrentHashMap<String, CountDownLatch>()
    private val statuses = HashMap<String, LinkedHashMap<String, JSONObject>>()
    private val screens = HashMap<String, StringBuilder>()
    private val copies = HashMap<String, Pair<Long, String>>()
    private val blobs = HashMap<Long, String>()
    private val blobIds = java.util.concurrent.atomic.AtomicLong()

    /**
     * False for a Rex whose panes send no picture: a stream opens and is welcomed, then stays
     * quiet. For the app's screen tests on the JVM, where libghostty is not there to draw one.
     */
    @Volatile var pictures: Boolean = true

    /** Every request that reached this Rex, as `method block-or-session`, in order. */
    val calls = CopyOnWriteArrayList<String>()

    /** What Rex says when own or disown names a pane that is not in the session (`bad_request`). */
    fun paneRefusal(block: String) = "block $block is not in the session"

    /** What Rex says when a pane's own method, `block.close` or `data.connect` names a pane that is gone (`not_found`). */
    fun goneRefusal(block: String) = "block not found: $block"

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

    /**
     * Keeps every [method] call waiting for its answer until [release]: a slow network, so a test
     * can act while the connection is still on its way.
     */
    fun hold(method: String) {
        holds.putIfAbsent(method, CountDownLatch(1))
    }

    fun release(method: String) {
        holds.remove(method)?.countDown()
    }

    /**
     * [clientId]'s network goes away: its control connection and streams drop without a close frame.
     * Rex keeps the client for a resume with its reconnect token.
     */
    fun drop(clientId: String) {
        clients.filterValues { it.id == clientId }.keys.forEach { runCatching { it.cancel() } }
        streams.filter { it.client == clientId }.forEach { s -> s.socket?.let { runCatching { it.cancel() } } }
    }

    /**
     * The program in [block] reports [state] (`working`, `blocked`, `error`, `done`, `idle`) for
     * record [id] ("" is the root record), as a Rex-aware program does with OSC 7501: its record is
     * what `program_status` answers from now on, and every client that may see it is told with a
     * `program_status_changed` block event. Null [state] is the program clearing the record (and the
     * records under it), told with `program_status_removed`, reason `clear`.
     */
    fun report(
        block: String,
        state: String?,
        kind: String? = null,
        msg: String? = null,
        app: String? = null,
        title: String? = null,
        id: String = ROOT,
    ) {
        synchronized(this) {
            val session = sessions.values.firstOrNull { it.tabOf(block) != null } ?: return
            val records = statuses.getOrPut(block) { LinkedHashMap() }
            if (state == null) {
                val gone = records.keys.filter { id == ROOT || it == id || it.startsWith("$id/") }
                if (gone.isEmpty()) return
                gone.forEach(records::remove)
                blockEvent(session.id, block, "program_status_removed", JSONObject().put("ids", JSONArray(gone)).put("reason", "clear"))
                return
            }
            val record = JSONObject()
            if (id != ROOT) record.put("id", id)
            record.put("state", state)
            kind?.let { record.put("kind", it) }
            app?.let { record.put("app", it) }
            (app ?: inheritedApp(records, id))?.let { record.put("effective_app", it) }
            title?.let { record.put("title", it) }
            msg?.let { record.put("msg", it) }
            record.put("updated_at", Instant.ofEpochMilli(clock()).toString())
            record.put("owner", JSONObject().put("pid", 4242).put("name", "bash"))
            records[id] = record
            blockEvent(session.id, block, "program_status_changed", JSONObject().put("id", id).put("record", record).put("reason", "report"))
        }
    }

    /**
     * The program in [block] exits with [code]: its working and blocked records go (reason
     * `child_exited`), `child_exited` is told, and the pane closes, as a shell pane does by default
     * when its program ends. A failure leaves no status record behind: only `child_exited` says it.
     */
    fun exit(block: String, code: Int) {
        synchronized(this) {
            val session = sessions.values.firstOrNull { it.tabOf(block) != null } ?: return
            val records = statuses[block]
            val gone = records?.filterValues { it.optString("state") in setOf("working", "blocked") }?.keys?.toList().orEmpty()
            if (gone.isNotEmpty()) {
                gone.forEach { records!!.remove(it) }
                blockEvent(session.id, block, "program_status_removed", JSONObject().put("ids", JSONArray(gone)).put("reason", "child_exited"))
            }
            val exited = JSONObject().put("exit_code", code)
            if (code != 0) exited.put("error", "exit status $code")
            blockEvent(session.id, block, "child_exited", exited)
            closeBlock(session, block)
        }
    }

    /**
     * The program in [block] copies [text] with OSC 52: Rex keeps the copy for `clipboard_read` and
     * tells every client that may see the pane with `clipboard_written`, the text in it unless
     * [inline] is false (Rex leaves out a copy too large for an event).
     */
    fun copy(block: String, text: String, inline: Boolean = true) {
        synchronized(this) {
            val session = sessions.values.firstOrNull { it.tabOf(block) != null } ?: return
            val id = blobIds.incrementAndGet()
            copies[block] = id to text
            val event = JSONObject().put("id", id)
            if (text.isNotEmpty()) {
                event.put("bytes", text.toByteArray().size)
                if (inline) event.put("text", text)
            }
            blockEvent(session.id, block, "clipboard_written", event)
        }
    }

    /** What [block] shows, as Rex's `format` gives it as text: what was written to it, which a shell echoes. */
    fun screenOf(block: String): String = synchronized(this) { screens[block]?.toString().orEmpty() }

    /** The client that holds [block], as Rex has it. */
    fun ownerOf(block: String): String? = synchronized(this) { owners[block] }

    /** Streams open for [block] now. */
    fun openStreams(block: String): Int = streams.count { it.block == block && it.socket != null }

    /** An id the way Rex writes them: `kind:` and a UUID that is new every time. */
    private fun next(kind: String): String {
        val n = ids.incrementAndGet()
        return "$kind:%08x-0000-7000-8000-%012x".format(n shr 32, n)
    }

    private fun inheritedApp(records: Map<String, JSONObject>, id: String): String? {
        var parent = id
        while (parent.isNotEmpty()) {
            parent = parent.substringBeforeLast('/', "")
            records[parent]?.optString("app")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    // ---- what the server does -------------------------------------------------------------

    private fun view(s: Session): JSONObject {
        val v = JSONObject().put("revision", s.revision).put("session_id", s.id).put("label", s.label)
        if (s.tabs.isEmpty()) return v
        return v.put("active_window_id", s.activeTab ?: s.tabs.first().id).put(
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
                                    JSONObject().put("layer_id", "layer:" + tab.id.substringAfter(':')).put("kind", "tiled").put(
                                        "blocks",
                                        JSONArray().apply {
                                            tab.blocks.forEach { b ->
                                                val block = JSONObject()
                                                    .put("block_id", b)
                                                    .put("label", "shell")
                                                    .put("creator_name", "com.superlogical.terminal")
                                                    .put("flavor", "com.superlogical.terminal.shell")
                                                owners[b]?.let { block.put("owner", it) }
                                                put(block)
                                            }
                                        },
                                    ),
                                ),
                            ),
                    )
                }
            },
        )
    }

    private fun list(): JSONArray = JSONArray().apply {
        sessions.values.forEach { put(JSONObject().put("session_id", it.id).put("label", it.label)) }
    }

    private class Refused(val code: String, val status: Int, message: String) : Exception(message)

    /** Who may see an event: a session's, or one about the whole server. */
    private class Outgoing(val session: String?, val block: Boolean, val event: JSONObject)

    /** The events an answer causes, sent after it. */
    private val pending = ThreadLocal<MutableList<Outgoing>>()

    private fun announce(out: Outgoing) {
        pending.get()?.add(out) ?: broadcast(out)
    }

    private fun sessionEvent(sid: String, type: String, payload: JSONObject = JSONObject()) = announce(
        Outgoing(
            sid,
            block = type == "block_event",
            event = JSONObject().put("type", "session_event").put(
                "payload",
                JSONObject().put("session_id", sid).put("event", JSONObject().put("type", type).put("payload", payload)),
            ),
        ),
    )

    private fun blockEvent(sid: String, block: String, name: String, payload: JSONObject) = sessionEvent(
        sid,
        "block_event",
        JSONObject().put("block_id", block).put("creator_name", "com.superlogical.terminal").put("name", name).put("payload", payload),
    )

    private fun changed(s: Session) {
        s.revision++
        sessionEvent(s.id, "session_view_changed", JSONObject().put("revision", s.revision))
    }

    private fun mayHear(client: Client, out: Outgoing): Boolean {
        val sid = out.session ?: return client.scope != null
        if (sid in client.attached) return true
        return when (client.scope) {
            "all" -> true
            "session" -> !out.block
            else -> false
        }
    }

    private fun broadcast(out: Outgoing) {
        clients.forEach { (socket, client) ->
            if (!mayHear(client, out)) return@forEach
            val text = JSONObject().put("type", "event").put("seq", client.seq.incrementAndGet()).put("payload", out.event).toString()
            socket.send(text)
        }
    }

    private fun session(payload: JSONObject): Session =
        sessions[payload.optString("session_id")] ?: throw Refused("session_not_found", 404, "session not found")

    /** A session this connection may use: attached, or any after `session.bootstrap`. */
    private fun reached(client: Client, payload: JSONObject): Session {
        val s = session(payload)
        if (client.scope == null && s.id !in client.attached) throw notAttached(s)
        return s
    }

    private fun notAttached(s: Session) = Refused("conflict", 409, "stream is not attached to session ${s.id}")

    /** The pane a pane call names, which must be in the session the call names. */
    private fun pane(client: Client, payload: JSONObject, block: String = payload.optString("block_id")): Pair<Session, String> {
        val s = reached(client, payload)
        if (s.tabOf(block) == null) throw Refused("not_found", 404, goneRefusal(block))
        return s to block
    }

    private fun newTab(s: Session, owner: String?): Tab {
        val tab = Tab(next("window"), "", mutableListOf(next("block")), null)
        tab.label = "Window " + tab.id.takeLast(8)
        s.tabs += tab
        val block = tab.blocks.first()
        if (owner != null) {
            sessionEvent(s.id, "block_created", JSONObject().put("block_id", block))
            claim(block, owner, "create")
            setOwner(s, block, owner, "create", view = false)
        }
        return tab
    }

    private fun claim(block: String, client: String, mode: String) {
        val line = claims.getOrPut(block) { ArrayList() }
        line.removeAll { it.first == client }
        line += client to mode
    }

    /**
     * Who gets [block] when its owner lets it go: the connected client in line that claimed it most
     * recently, any `take` (or its maker) ahead of every `request` - as Rex's `session.own_block` says,
     * and as seen on 0.1.0-preview.22 when the phone's network dropped from a pane the desk made.
     */
    private fun nextInLine(block: String, leaving: String): String? {
        val connected = clients.values.map { it.id }.toSet()
        val line = claims[block].orEmpty().filter { it.first != leaving && it.first in connected }
        return (line.lastOrNull { it.second != "request" } ?: line.lastOrNull())?.first
    }

    /** [block] passes to [owner] (null: nobody) for [reason]; told only when it changes. */
    private fun setOwner(s: Session, block: String, owner: String?, reason: String, view: Boolean = true): Boolean {
        val previous = owners[block]
        if (previous == owner) return false
        if (owner == null) owners -= block else owners[block] = owner
        val event = JSONObject().put("block_id", block)
        owner?.let { event.put("owner", it) }
        previous?.let { event.put("previous", it) }
        sessionEvent(s.id, "block_owner_changed", event.put("reason", reason))
        if (view) changed(s)
        return true
    }

    private fun closeBlock(s: Session, block: String) {
        val tab = s.tabOf(block) ?: return
        tab.blocks -= block
        if (tab.focused == block) tab.focused = tab.blocks.firstOrNull()
        endPane(block)
        sessionEvent(s.id, "block_closed", JSONObject().put("block_id", block))
        if (tab.blocks.isEmpty()) {
            removeTab(s, tab)
        }
        changed(s)
    }

    /** The pane is gone: nobody holds it, and its streams end the way Rex ends them. */
    private fun endPane(block: String) {
        owners -= block
        claims -= block
        statuses -= block
        screens -= block
        copies -= block
        streams.filter { it.block == block }.forEach { stream ->
            stream.socket?.close(4000, "block closed")
            streams -= stream
        }
    }

    private fun removeTab(s: Session, tab: Tab) {
        s.tabs.remove(tab)
        sessionEvent(s.id, "window_closed", JSONObject().put("window_id", tab.id))
        if (s.activeTab == tab.id || s.activeTab == null) {
            s.activeTab = s.tabs.firstOrNull()?.id
            sessionEvent(s.id, "active_window_changed", JSONObject().apply { s.activeTab?.let { put("window_id", it) } })
        }
    }

    private fun closeTab(s: Session, tab: Tab) {
        // Rex tells the tab is gone first, then each of its panes.
        val blocks = tab.blocks.toList()
        removeTab(s, tab)
        changed(s)
        blocks.forEach { block ->
            endPane(block)
            sessionEvent(s.id, "block_closed", JSONObject().put("block_id", block))
        }
    }

    private fun destroy(s: Session) {
        if (sessions.remove(s.id) == null) return
        for (tab in s.tabs.toList()) {
            for (block in tab.blocks.toList()) {
                endPane(block)
                sessionEvent(s.id, "block_closed", JSONObject().put("block_id", block))
                changed(s)
            }
        }
        announce(Outgoing(s.id, block = false, JSONObject().put("type", "session_destroyed").put("payload", JSONObject().put("session_id", s.id))))
    }

    private fun answer(client: Client, method: String, payload: JSONObject): JSONObject = synchronized(this) {
        refusals[method]?.let { throw Refused("internal", 500, it) }
        when (method) {
            "session.bootstrap" -> {
                val s = sessions[payload.optString("preferred_session_id")] ?: sessions.values.firstOrNull()
                client.scope = payload.optString("event_scope").ifEmpty { "session" }
                s?.let { client.attached += it.id }
                JSONObject()
                    .put("client_id", client.id)
                    .put("server_status", JSONObject().put("session_count", sessions.size).put("api_revision", 19))
                    .put("attach_all", JSONObject().put("sessions", list()))
                    .put(
                        "selected_session",
                        if (s == null) JSONObject.NULL else JSONObject().put("session", JSONObject().put("session_id", s.id).put("label", s.label)).put("view", view(s)),
                    )
            }
            "session.attach" -> {
                val s = session(payload)
                client.attached += s.id
                JSONObject().put("client_id", client.id).put("view", view(s))
            }
            "session.view" -> view(reached(client, payload))
            "session.list" -> JSONObject().put("sessions", list())
            "session.create" -> {
                val s = Session(next("session"), payload.optString("label").ifEmpty { "quiet-meadow" }, mutableListOf())
                // Its first panes start with no owner: the caller cannot be attached to it yet.
                val count = payload.optJSONArray("initial_windows")?.length() ?: 0
                val made = JSONArray()
                repeat(count) {
                    val tab = newTab(s, owner = null)
                    made.put(JSONObject().put("window_id", tab.id).put("block_ids", JSONArray(tab.blocks)).put("revision", 1))
                }
                s.activeTab = s.tabs.lastOrNull()?.id
                sessions[s.id] = s
                announce(
                    Outgoing(
                        null,
                        block = false,
                        JSONObject().put("type", "session_created").put("payload", JSONObject().put("session", JSONObject().put("session_id", s.id).put("label", s.label))),
                    ),
                )
                JSONObject().put("session_id", s.id).put("initial_windows", made)
            }
            "session.new_window" -> {
                val s = reached(client, payload)
                val tab = newTab(s, owner = client.id)
                sessionEvent(s.id, "window_created", JSONObject().put("window_id", tab.id).put("label", tab.label))
                if (payload.optBoolean("focus", true) || s.activeTab == null) {
                    s.activeTab = tab.id
                    sessionEvent(s.id, "active_window_changed", JSONObject().put("window_id", tab.id))
                }
                changed(s)
                JSONObject().put("window_id", tab.id).put("block_ids", JSONArray(tab.blocks)).put("revision", s.revision)
            }
            "session.new_split" -> {
                val s = reached(client, payload)
                if (payload.optString("direction").isEmpty() || payload.optString("side").isEmpty()) {
                    throw Refused("bad_request", 400, "validating input: validating root: required: missing properties: [\"direction\" \"side\"]")
                }
                val anchor = payload.optString("anchor_id")
                val tab = if (anchor.isNotEmpty()) {
                    s.tabOf(anchor) ?: throw Refused("not_found", 404, goneRefusal(anchor))
                } else {
                    s.tabs.firstOrNull { it.id == s.activeTab } ?: throw Refused("conflict", 409, "session has no windows")
                }
                val block = next("block")
                tab.blocks += block
                sessionEvent(s.id, "block_created", JSONObject().put("block_id", block))
                claim(block, client.id, "create")
                setOwner(s, block, client.id, "create", view = false)
                changed(s)
                JSONObject().put("anchor_id", anchor.ifEmpty { tab.focused ?: tab.blocks.first() }).put("block_ids", JSONArray().put(block)).put("revision", s.revision)
            }
            "session.close_window" -> {
                val s = reached(client, payload)
                val window = payload.optString("window_id")
                val tab = s.tabs.firstOrNull { it.id == window } ?: throw Refused("not_found", 404, "window $window not found")
                closeTab(s, tab)
                JSONObject().put("window_id", window).put("revision", s.revision)
            }
            "block.close" -> {
                val (s, block) = pane(client, payload)
                closeBlock(s, block)
                JSONObject().put("block_id", block).put("revision", s.revision)
            }
            "session.destroy" -> {
                destroy(reached(client, payload))
                JSONObject()
            }
            "session.own_block", "session.disown_block" -> {
                val blocks = payload.optJSONArray("block_ids") ?: JSONArray()
                val s = session(payload)
                if (s.id !in client.attached) {
                    if (client.scope != null) throw Refused("bad_request", 400, "the caller must be a client attached to the session")
                    throw notAttached(s)
                }
                for (i in 0 until blocks.length()) {
                    val block = blocks.getString(i)
                    if (s.tabOf(block) == null) throw Refused("bad_request", 400, paneRefusal(block))
                }
                val owned = JSONArray()
                for (i in 0 until blocks.length()) {
                    val block = blocks.getString(i)
                    if (method == "session.own_block") {
                        val mode = payload.optString("mode").ifEmpty { "take" }
                        claim(block, client.id, mode)
                        // A request waits in line while someone holds the pane.
                        if (mode == "take" || owners[block] == null) setOwner(s, block, client.id, mode)
                    } else {
                        claims[block]?.removeAll { it.first == client.id }
                        if (owners[block] == client.id) setOwner(s, block, nextInLine(block, client.id), "disown")
                    }
                    owned.put(JSONObject().put("block_id", block).apply { owners[block]?.let { put("owner", it) } })
                }
                JSONObject().put("blocks", owned)
            }
            "com.superlogical.terminal.size" -> {
                val (_, block) = pane(client, payload)
                JSONObject().put("columns", 80).put("rows", 24).put("cell_width_px", 8).put("cell_height_px", 16)
                    .put("owner", owners[block] ?: JSONObject.NULL).put("desired", JSONArray())
            }
            "com.superlogical.terminal.write" -> {
                val (_, block) = pane(client, payload)
                val data = payload.optJSONObject("args")?.optString("data").orEmpty()
                screens.getOrPut(block) { StringBuilder() }.append(data)
                JSONObject().put("bytes_written", data.toByteArray().size)
            }
            "block.method" -> answer(client, "com.superlogical.terminal." + payload.optString("method"), payload)
            "com.superlogical.terminal.clear", "com.superlogical.terminal.reset" -> {
                val (_, block) = pane(client, payload)
                screens -= block
                JSONObject()
            }
            "com.superlogical.terminal.format" -> {
                val (_, block) = pane(client, payload)
                val args = payload.optJSONObject("args") ?: JSONObject()
                var text = screens[block]?.toString().orEmpty()
                if (args.optBoolean("trim")) text = text.lines().joinToString("\n") { it.trimEnd() }
                val max = args.optInt("max_bytes")
                val bytes = text.toByteArray()
                if (max <= 0 || bytes.size <= max) {
                    JSONObject().put("content", text).apply { if (max > 0) put("total", bytes.size).put("eof", true) }
                } else {
                    val id = blobIds.incrementAndGet()
                    blobs[id] = text
                    JSONObject().put("content", String(bytes, 0, max)).put("id", id).put("total", bytes.size)
                }
            }
            "com.superlogical.terminal.blob_read", "com.superlogical.terminal.clipboard_read" -> {
                val (_, block) = pane(client, payload)
                val args = payload.optJSONObject("args") ?: JSONObject()
                val copy = method.endsWith("clipboard_read")
                val asked = args.optLong("id")
                val (id, text) = when {
                    copy && asked == 0L -> copies[block]
                    copy -> copies[block]?.takeIf { it.first == asked }
                    else -> blobs[asked]?.let { asked to it }
                } ?: throw Refused("not_found", 404, "not found")
                val bytes = text.toByteArray()
                val offset = args.optInt("offset").coerceIn(0, bytes.size)
                val max = args.optInt("max_bytes").takeIf { it > 0 } ?: 49_152
                val end = minOf(bytes.size, offset + max)
                JSONObject().put("id", id).put("data", String(bytes, offset, end - offset)).put("total", bytes.size).put("eof", end == bytes.size)
            }
            "com.superlogical.terminal.resize", "client.key", "block.event" -> {
                pane(client, payload)
                JSONObject()
            }
            "session.set_block_label" -> {
                val s = reached(client, payload)
                if (s.tabOf(payload.optString("block_id")) == null) throw Refused("not_found", 404, "block not found")
                JSONObject()
            }
            "com.superlogical.terminal.program_status" -> {
                val (_, block) = pane(client, payload)
                JSONObject().put(
                    "records",
                    JSONArray().apply { statuses[block]?.values?.forEach { put(JSONObject(it.toString())) } },
                )
            }
            "com.superlogical.terminal.pwd" -> {
                pane(client, payload)
                JSONObject()
            }
            "com.superlogical.terminal.process" -> {
                pane(client, payload)
                val bash = JSONObject().put("pid", 4242).put("name", "bash").put("path", "/usr/bin/bash")
                JSONObject().put("last_exit", JSONObject.NULL).put("child", bash).put("foreground", bash).put("home", "/root")
            }
            "com.superlogical.terminal.title" -> {
                pane(client, payload)
                JSONObject().put("title", "root@rex: ~").put("owner", JSONObject().put("pid", 4242).put("name", "bash"))
            }
            "data.connect" -> {
                val s = session(payload)
                if (s.id !in client.attached) throw notAttached(s)
                val (_, block) = pane(client, payload)
                val bytes = ByteArray(16).also { java.util.Random().nextBytes(it) }
                val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                tickets[bytes.toByteString().hex()] = Triple(client.id, s.id, block)
                JSONObject().put("ticket", ticket).put("expires_in_ms", 60_000)
            }
            "session.set_label" -> {
                val s = reached(client, payload)
                s.label = payload.optString("label")
                sessionEvent(s.id, "session_label_changed", JSONObject().put("label", s.label))
                changed(s)
                JSONObject().put("label", s.label)
            }
            "session.set_window_label" -> {
                val s = reached(client, payload)
                val window = payload.optString("window_id")
                val tab = s.tabs.firstOrNull { it.id == window } ?: throw Refused("not_found", 404, "window $window not found")
                tab.label = payload.optString("label")
                sessionEvent(s.id, "window_label_changed", JSONObject().put("window_id", window).put("label", tab.label))
                changed(s)
                JSONObject()
            }
            "client.keymap" -> JSONObject().put("bindings", JSONArray()).put("diagnostics", JSONArray()).put("actions", JSONArray())
            else -> JSONObject()
        }
    }

    /** The client's connection ended. A close frame ends the client; a drop leaves it for a resume. */
    private fun gone(webSocket: WebSocket, clean: Boolean) {
        val client = clients.remove(webSocket) ?: return
        synchronized(this) {
            if (client.socket === webSocket) client.socket = null
            val held = owners.filterValues { it == client.id }.keys.toList()
            if (clean) claims.values.forEach { line -> line.removeAll { it.first == client.id } }
            for (block in held) {
                val s = sessions.values.firstOrNull { it.tabOf(block) != null } ?: continue
                val next = nextInLine(block, client.id)
                setOwner(s, block, next, if (clean) "disconnect" else "lost")
                if (!clean) client.lost[block] = next
            }
            if (clean) {
                client.lost.clear()
                tokens.values.removeAll { it === client }
                streams.filter { it.client == client.id }.forEach { stream ->
                    stream.socket?.close(1001, "data provider canceled")
                    streams -= stream
                }
            }
        }
    }

    private fun control() = object : WebSocketListener() {
        @Volatile private var clean = false

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            clean = true
            webSocket.close(code, reason)
            gone(webSocket, clean = true)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket, clean)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) = gone(webSocket, clean = false)

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "hello" -> {
                    val previous = message.optString("reconnect_token").takeIf { it.isNotEmpty() }?.let { tokens.remove(it) }
                    val resumed = previous != null
                    val client = previous ?: Client(next("client"))
                    client.socket = webSocket
                    client.seq.set(0)
                    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { java.util.Random().nextBytes(it) })
                    tokens[token] = client
                    if (resumed) {
                        // Its panes come back, unless their owner changed again since. The resuming
                        // connection is not told: it is not listening yet.
                        synchronized(this@ScriptedRex) {
                            for ((block, after) in client.lost.toMap()) {
                                val s = sessions.values.firstOrNull { it.tabOf(block) != null } ?: continue
                                if (owners[block] == after) setOwner(s, block, client.id, "restore")
                            }
                            client.lost.clear()
                        }
                    }
                    val welcomeClient = JSONObject().put("id", client.id).put("reconnect_token", token)
                    if (resumed) welcomeClient.put("resumed", true)
                    webSocket.send(
                        JSONObject()
                            .put("type", "welcome")
                            .put("protocol", "rex.control.v1")
                            .put("limits", JSONObject().put("max_message_bytes", 1_048_576))
                            .put("client", welcomeClient)
                            .put("api_revision", message.optInt("api_revision").coerceAtMost(19))
                            .put("api_server_revision", 19)
                            .toString(),
                    )
                    // Listening from here on: the welcome is always its first message.
                    clients[webSocket] = client
                }
                "request" -> {
                    val client = clients[webSocket] ?: return
                    val id = message.getString("id")
                    val method = message.getString("method")
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    holds[method]?.await(30, TimeUnit.SECONDS)
                    calls += "$method ${payload.optString("block_id").ifEmpty { payload.optJSONArray("block_ids")?.optString(0) ?: payload.optString("session_id") }}"
                    val events = mutableListOf<Outgoing>()
                    pending.set(events)
                    val reply = JSONObject().put("type", "response").put("id", id).put("seq", client.seq.get())
                    try {
                        reply.put("payload", answer(client, method, payload))
                    } catch (e: Refused) {
                        reply.put("error", JSONObject().put("code", e.code).put("message", e.message).put("status", e.status))
                    } finally {
                        pending.remove()
                    }
                    // Rex tells a change before it answers the call that made it: the events come
                    // first, with a seq above the response's.
                    events.forEach(::broadcast)
                    webSocket.send(reply.toString())
                }
            }
        }
    }

    private fun data() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
            // A stream its client closes with 1000 ends; any other close waits for a resume.
            if (code == 1000) streams.removeAll { it.socket === webSocket } else gone(webSocket)
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
                    val (client, sid, block) = tickets.remove(hello.copyOfRange(1, 17).toByteString().hex())
                        ?: return reject(webSocket, 0x01)
                    if (!alive(sid, block)) return reject(webSocket, 0x03)
                    // One stream of a pane per client: another HELLO while it is open is refused.
                    if (streams.any { it.client == client && it.block == block && it.socket != null }) return reject(webSocket, 0x02)
                    // A HELLO starts over: a stream left waiting for a resume ends.
                    streams.removeAll { it.client == client && it.block == block }
                    val stream = Stream(client, block, ByteArray(16).also { java.util.Random().nextBytes(it) }.toByteString(), ids.incrementAndGet())
                    stream.socket = webSocket
                    streams += stream
                    welcome(webSocket, stream)
                    if (pictures) {
                        webSocket.send(SNAPSHOT.toByteString())
                        // Rex follows the snapshot with the pane's size, in its own APC.
                        val owner = ownerOf(block)?.let { "\"$it\"" } ?: "null"
                        webSocket.send(
                            "\u001b_rex1;${java.lang.Long.toUnsignedString(stream.nonce)};{\"type\":\"resize\",\"cols\":80,\"rows\":24,\"cell_width_px\":8,\"cell_height_px\":16,\"owner\":$owner}\u001b\\"
                                .toByteArray().toByteString(),
                        )
                        webSocket.send("$ ".toByteArray().toByteString())
                    }
                }
                0x02 -> {
                    val token = hello.copyOfRange(1, 17).toByteString()
                    val stream = streams.firstOrNull { it.token == token } ?: return reject(webSocket, 0x03)
                    runCatching { stream.socket?.cancel() }
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
            // How long Rex keeps the stream for a resume: 60 s, as Rex 0.1.0-preview.22 says.
            val keep = 60_000
            for (i in 0 until 4) out[20 + i] = ((keep ushr (8 * i)) and 0xff).toByte()
            for (i in 0 until 8) out[24 + i] = ((stream.nonce ushr (8 * i)) and 0xff).toByte()
            webSocket.send(out.toByteString())
        }

        private fun reject(webSocket: WebSocket, reason: Int) {
            webSocket.send(byteArrayOf(0x82.toByte(), reason.toByte()).toByteString())
            webSocket.close(if (reason == 0x06) 1011 else 1008, "data handshake rejected")
        }
    }

    override fun close() {
        holds.keys.toList().forEach(::release)
        clients.keys.forEach { runCatching { it.close(1001, "bye") } }
        server.shutdown()
    }

    private companion object {
        /** The root record's id, as Rex writes it in events and `program_status_removed`. */
        const val ROOT = ""

        /** The smallest snapshot there is: the magic, a version, and the record that ends it. */
        val SNAPSHOT = "GHOSTSNP".toByteArray() + byteArrayOf(1, 0) + byteArrayOf(6, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

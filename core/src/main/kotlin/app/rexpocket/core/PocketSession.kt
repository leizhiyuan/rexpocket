package app.rexpocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

enum class Phase { Idle, Connecting, Live, Reconnecting, Failed }

data class TermSize(val cols: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

data class PocketState(
    val phase: Phase = Phase.Idle,
    /** host:port of the server this state belongs to, shown while connecting. */
    val endpointLabel: String = "",
    /** True when the server accepted our reconnect token and kept the session. */
    val resumed: Boolean = false,
    val error: String? = null,
    val clientId: String? = null,
    val sessions: List<ListedSession> = emptyList(),
    val activeSessionId: String? = null,
    val view: SessionView? = null,
    val views: Map<String, SessionView> = emptyMap(),
    val blockStatus: Map<String, ProgramRecord> = emptyMap(),
    val pwd: String? = null,
    /** Raw `format=vt` dump of the focused block: scrollback and screen as one replay. */
    val vt: String? = null,
    /** Block [vt] was read from. The UI draws it only while that block is the shown pane. */
    val vtBlockId: String? = null,
    val vtVersion: Int = 0,
)

/**
 * Phone-side session: bootstrap, sidebar model, and a polled VT screen.
 * Owning the focused block makes Rex use this client's size, the same way
 * Moshi takes the pane while it is on screen. Leaving disowns it.
 */
class PocketSession(
    private val endpoint: Endpoint,
    private val clientFactory: () -> RexClient = { RexClient() },
) {
    private val _state = MutableStateFlow(PocketState())
    val state: StateFlow<PocketState> = _state

    private val callMutex = Mutex()
    private val clientRef = AtomicReference<RexClient?>(null)
    private val sizeRef = AtomicReference(TermSize(80, 24, 8, 16))
    private var ownedBlock: String? = null
    private var lastResize: TermSize? = null
    private var lastVt: String? = null
    private var preferred: String? = null
    private var reconnectToken: String? = null
    private val ready = CompletableDeferred<Unit>()
    private var runner: Job? = null

    fun start(scope: CoroutineScope, preferredSessionId: String? = null): Job {
        preferred = preferredSessionId
        val job = scope.launch {
            var backoff = 400L
            while (isActive) {
                try {
                    runConnection()
                    backoff = 400L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    _state.update {
                        it.copy(
                            phase = if (it.phase == Phase.Live) Phase.Reconnecting else Phase.Connecting,
                            error = e.message,
                        )
                    }
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(8_000)
                }
            }
        }
        runner = job
        return job
    }

    suspend fun awaitReady() {
        ready.await()
    }

    fun note(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun stop() {
        val job = runner
        runner = null
        job?.cancel()
        // Join so the connection's NonCancellable finally can disown before the socket closes.
        // Clearing clientRef first would skip that disown.
        if (job != null) {
            runBlocking {
                withTimeoutOrNull(4_000) { job.join() }
            }
        }
        clientRef.getAndSet(null)?.close()
        _state.update { it.copy(phase = Phase.Idle, resumed = false) }
    }

    fun setSize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val next = TermSize(
            cols.coerceIn(20, 400),
            rows.coerceIn(4, 200),
            cellWidthPx.coerceIn(1, 128),
            cellHeightPx.coerceIn(1, 128),
        )
        if (sizeRef.get() != next) {
            sizeRef.set(next)
            lastResize = null
            lastVt = null
        }
    }

    suspend fun openSession(sessionId: String) {
        preferred = sessionId
        val payload = rpc("session.attach", JSONObject().put("session_id", sessionId))
        val view = parseView(payload.getJSONObject("view"))
        // applyView only replaces the shown view when this session is already active.
        // Opening another session has to force it, or the old view stays on screen.
        applyView(view)
        _state.update {
            it.copy(
                activeSessionId = sessionId,
                view = view,
                views = if (view.sessionId.isEmpty()) it.views else it.views + (view.sessionId to view),
                sessions = mergeSession(it.sessions, ListedSession(sessionId, view.label)),
            )
        }
        lastVt = null
        ownedBlock = null
    }

    suspend fun createShell(label: String?) {
        val body = JSONObject().put(
            "initial_windows",
            JSONArray().put(
                JSONObject().put(
                    "layout",
                    JSONObject().put(
                        "block",
                        JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                    ),
                ),
            ),
        )
        if (!label.isNullOrBlank()) body.put("label", label.trim())
        val payload = rpc("session.create", body)
        val id = payload.getString("session_id")
        val createdLabel = label?.trim().takeUnless { it.isNullOrEmpty() } ?: id
        _state.update { it.copy(sessions = it.sessions + ListedSession(id, createdLabel)) }
        openSession(id)
    }

    suspend fun newWindow() {
        val sid = requireSession()
        rpc(
            "session.new_window",
            JSONObject()
                .put("session_id", sid)
                .put(
                    "layout",
                    JSONObject().put(
                        "block",
                        JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                    ),
                ),
        )
    }

    suspend fun split(vertical: Boolean) {
        val sid = requireSession()
        val anchor = _state.value.view?.focusedBlock()?.id
        val body = JSONObject()
            .put("session_id", sid)
            .put("direction", if (vertical) "vertical" else "horizontal")
            .put("side", "after")
            .put("ratio", 0.5)
            .put("focus", true)
            .put(
                "layout",
                JSONObject().put(
                    "block",
                    JSONObject().put("flavor", "com.superlogical.terminal.shell"),
                ),
            )
        if (anchor != null) body.put("anchor_id", anchor)
        rpc("session.new_split", body)
    }

    /**
     * Shows a pane. Rex makes the pane's window the active one as part of the same call, so this
     * also switches tabs. The local view follows at once, so the header, the tab bar and the
     * terminal move together instead of waiting for the view event.
     */
    suspend fun focusBlock(blockId: String) {
        val sid = requireSession()
        val payload = rpc("session.focus_block", JSONObject().put("session_id", sid).put("block_id", blockId))
        val window = _state.value.view?.windows?.firstOrNull { w -> w.blocks.any { it.id == blockId } }
        if (window != null) showLocally(window.id, blockId, payload.optLong("revision", -1))
        lastVt = null
    }

    /** Shows a tab. Rex brings back the pane that tab last had focused. */
    suspend fun focusWindow(windowId: String) {
        val sid = requireSession()
        val payload = rpc("session.focus_window", JSONObject().put("session_id", sid).put("window_id", windowId))
        showLocally(windowId, null, payload.optLong("revision", -1))
        lastVt = null
    }

    /** Closes a pane, the shown one by default. Closing a tab's last pane closes the tab. */
    suspend fun closeBlock(blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        rpc("block.close", JSONObject().put("session_id", sid).put("block_id", block))
    }

    /** Closes a tab and every pane in it, the shown tab by default. */
    suspend fun closeWindow(windowId: String? = null) {
        val sid = requireSession()
        val window = windowId ?: _state.value.view?.activeWindow()?.id
        val body = JSONObject().put("session_id", sid)
        if (window != null) body.put("window_id", window)
        rpc("session.close_window", body)
    }

    suspend fun destroySession() {
        val sid = requireSession()
        rpc("session.destroy", JSONObject().put("session_id", sid))
        _state.update {
            val rest = it.sessions.filter { s -> s.id != sid }
            it.copy(
                sessions = rest,
                activeSessionId = rest.firstOrNull()?.id,
                view = null,
                vt = null,
            )
        }
        ownedBlock = null
        val next = _state.value.activeSessionId
        if (next != null) openSession(next)
    }

    suspend fun renameSession(label: String) {
        val sid = requireSession()
        rpc("session.set_label", JSONObject().put("session_id", sid).put("label", label))
    }

    suspend fun renameWindow(label: String, windowId: String? = null) {
        val sid = requireSession()
        val window = windowId ?: _state.value.view?.activeWindow()?.id ?: return
        rpc(
            "session.set_window_label",
            JSONObject().put("session_id", sid).put("window_id", window).put("label", label),
        )
    }

    suspend fun renameBlock(label: String, blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        rpc(
            "session.set_block_label",
            JSONObject().put("session_id", sid).put("block_id", block).put("label", label),
        )
    }

    suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        val (sid, block) = requireBlock()
        rpc(
            "com.superlogical.terminal.write",
            JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject().put("data", text)),
        )
        lastVt = null
    }

    /**
     * Keystrokes and mouse reports from the terminal view arrive as UTF-8 bytes. They are sent
     * without waiting for a reply so a swipe is not throttled by round trips.
     */
    suspend fun writeBytes(data: ByteArray) {
        if (data.isEmpty()) return
        val client = clientRef.get() ?: throw IllegalStateException("Not connected yet")
        val (sid, block) = requireBlock()
        client.send(
            "com.superlogical.terminal.write",
            JSONObject()
                .put("session_id", sid)
                .put("block_id", block)
                .put("args", JSONObject().put("data", String(data, Charsets.UTF_8))),
        )
        lastVt = null
    }

    /**
     * Typing a line means writing the text and then pressing Enter as a separate key event.
     * Full-screen UIs such as pi read a newline inside pasted text as a line break in their
     * own input box instead of accepting it, so the newline cannot ride along with the text.
     */
    suspend fun submitLine(text: String) {
        if (text.isNotEmpty()) {
            sendText(text)
            delay(40)
        }
        sendKey("enter")
    }

    suspend fun paste(text: String) {
        if (text.isEmpty()) return
        val (sid, block) = requireBlock()
        rpc(
            "block.event",
            JSONObject()
                .put("session_id", sid)
                .put("block_id", block)
                .put(
                    "event",
                    JSONObject()
                        .put("type", "paste")
                        .put("payload", JSONObject().put("text", text)),
                ),
        )
        lastVt = null
    }

    suspend fun sendKey(code: String, mods: String = "", text: String = "", codepoint: Int = text.firstCodePoint()) {
        val (sid, block) = requireBlock()
        rpc(
            "client.key",
            JSONObject()
                .put("session_id", sid)
                .put("block_id", block)
                .put(
                    "key",
                    JSONObject()
                        .put("key", code)
                        .put("mods", mods)
                        .put("consumed_mods", "")
                        .put("text", text)
                        .put("unshifted_codepoint", codepoint)
                        .put("composing", false)
                        .put("action", "press"),
                ),
        )
        lastVt = null
    }

    private suspend fun runConnection() {
        val client = clientFactory()
        clientRef.set(client)
        _state.update {
            it.copy(phase = Phase.Connecting, endpointLabel = "${endpoint.host}:${endpoint.port}", error = null)
        }
        try {
            val welcome = client.connect(endpoint, reconnectToken)
            reconnectToken = welcome.reconnectToken
            _state.update { it.copy(clientId = welcome.clientId, resumed = welcome.resumed) }
            coroutineScope {
                launch {
                    client.events.collect { event ->
                        try {
                            onEvent(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _state.update { it.copy(error = e.message) }
                        }
                    }
                }
                bootstrap()
                if (!ready.isCompleted) ready.complete(Unit)
                _state.update { it.copy(phase = Phase.Live, error = null) }
                launch { formatLoop() }
                launch { statusLoop() }
                val cause = client.awaitClosed()
                throw IllegalStateException(cause?.message ?: "Control connection dropped", cause)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    disownQuietly()
                } catch (_: Exception) {
                }
            }
            client.close()
            if (clientRef.get() === client) clientRef.set(null)
            ownedBlock = null
            lastResize = null
        }
    }

    private suspend fun bootstrap() {
        val body = JSONObject().put("event_scope", "session")
        val preferredId = preferred
        if (!preferredId.isNullOrBlank()) body.put("preferred_session_id", preferredId)
        val payload = rpc("session.bootstrap", body)
        val sessions = parseSessions(payload.optJSONObject("attach_all")?.optJSONArray("sessions"))
        val selected = payload.optJSONObject("selected_session")
        val view = selected?.optJSONObject("view")?.let { parseView(it) }
        val active = view?.sessionId ?: selected?.optJSONObject("session")?.optNullableString("session_id")
        _state.update {
            it.copy(
                sessions = sessions,
                activeSessionId = active,
                view = view,
                views = if (view != null && view.sessionId.isNotEmpty()) it.views + (view.sessionId to view) else it.views,
            )
        }
        preferred = active
    }

    private suspend fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session_created" -> {
                val session = event.optJSONObject("payload")?.optJSONObject("session") ?: return
                val id = session.optNullableString("session_id") ?: return
                val label = session.optNullableString("label") ?: shortId(id)
                _state.update { it.copy(sessions = mergeSession(it.sessions, ListedSession(id, label))) }
            }
            "session_destroyed" -> {
                val id = event.optJSONObject("payload")?.optNullableString("session_id")
                    ?: event.optNullableString("session_id")
                    ?: return
                dropSession(id)
            }
            "session_moved" -> refreshSessionList()
            "session_event" -> {
                val payload = event.optJSONObject("payload") ?: return
                val sid = payload.optNullableString("session_id") ?: return
                val inner = payload.optJSONObject("event") ?: return
                onSessionEvent(sid, inner)
            }
        }
    }

    private suspend fun onSessionEvent(sid: String, event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (event.optString("type")) {
            "session_view_changed" -> {
                val revision = payload.optLong("revision")
                val known = _state.value.views[sid]?.revision ?: -1
                if (revision > known) refreshView(sid)
            }
            "session_label_changed" -> {
                val label = payload.optNullableString("label") ?: return
                _state.update {
                    it.copy(
                        sessions = it.sessions.map { s -> if (s.id == sid) s.copy(label = label) else s },
                        view = if (it.view?.sessionId == sid) it.view.copy(label = label) else it.view,
                    )
                }
            }
            "session_destroyed" -> dropSession(sid)
            "block_closed", "window_closed", "window_created", "active_window_changed" -> refreshView(sid)
        }
    }

    private fun dropSession(id: String) {
        _state.update {
            val rest = it.sessions.filter { s -> s.id != id }
            val view = if (it.view?.sessionId == id) null else it.view
            it.copy(
                sessions = rest,
                views = it.views - id,
                view = view,
                activeSessionId = if (it.activeSessionId == id) rest.firstOrNull()?.id else it.activeSessionId,
                vt = if (it.activeSessionId == id) null else it.vt,
            )
        }
    }

    private suspend fun refreshSessionList() {
        val payload = rpc("session.list", null)
        _state.update { it.copy(sessions = parseSessions(payload.optJSONArray("sessions"))) }
    }

    private suspend fun refreshView(sid: String) {
        val payload = rpc("session.view", JSONObject().put("session_id", sid))
        applyView(parseView(payload))
    }

    /**
     * Mirrors a focus change the server just accepted. [revision] is the one the call returned,
     * so a view fetched before the change cannot overwrite it, while any later change still does.
     */
    private fun showLocally(windowId: String, blockId: String?, revision: Long) {
        _state.update { st ->
            val view = st.view ?: return@update st
            if (view.windows.none { it.id == windowId }) return@update st
            val windows = if (blockId == null) {
                view.windows
            } else {
                view.windows.map { if (it.id == windowId) it.copy(focusedBlockId = blockId) else it }
            }
            val next = view.copy(
                revision = maxOf(view.revision, revision),
                activeWindowId = windowId,
                windows = windows,
            )
            st.copy(view = next, views = st.views + (next.sessionId to next))
        }
    }

    private fun applyView(view: SessionView) {
        if (view.sessionId.isEmpty()) return
        _state.update {
            val known = it.views[view.sessionId]?.revision ?: -1
            if (view.revision < known) return@update it
            val views = it.views + (view.sessionId to view)
            val active = it.activeSessionId == view.sessionId || it.activeSessionId == null
            it.copy(
                views = views,
                view = if (active) view else it.view,
                activeSessionId = it.activeSessionId ?: view.sessionId,
                sessions = mergeSession(it.sessions, ListedSession(view.sessionId, view.label)),
            )
        }
    }

    private suspend fun formatLoop() {
        while (true) {
            val sid = _state.value.activeSessionId
            val block = _state.value.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id
            if (sid != null && block != null) {
                try {
                    ensureOwned(sid, block)
                    val size = sizeRef.get()
                    if (lastResize != size) {
                        rpc(
                            "com.superlogical.terminal.resize",
                            JSONObject().put("session_id", sid).put("block_id", block).put(
                                "args",
                                JSONObject()
                                    .put("columns", size.cols)
                                    .put("rows", size.rows)
                                    .put("cell_width_px", size.cellWidthPx)
                                    .put("cell_height_px", size.cellHeightPx),
                            ),
                        )
                        lastResize = size
                        delay(40)
                    }
                    val vt = readFormat(sid, block)
                    if (vt != lastVt) {
                        lastVt = vt
                        _state.update { it.copy(vt = vt, vtBlockId = block, vtVersion = it.vtVersion + 1, error = null) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RexCallException) {
                    if (e.code == "session_not_found" || e.code == "not_found") {
                        lastVt = null
                        ownedBlock = null
                    } else {
                        _state.update { it.copy(error = e.message) }
                    }
                } catch (e: Exception) {
                    _state.update { it.copy(error = e.message) }
                }
            }
            delay(if (lastVt == null) 40 else 180)
        }
    }

    private suspend fun readFormat(sid: String, block: String): String {
        val payload = rpc(
            "com.superlogical.terminal.format",
            JSONObject().put("session_id", sid).put("block_id", block).put(
                "args",
                JSONObject()
                    .put("format", "vt")
                    .put("trim", true)
                    .put("extra_cursor", true)
                    .put("extra_style", true)
                    .put("extra_modes", true)
                    .put("extra_palette", true)
                    .put("extra_scrolling_region", true)
                    .put("max_bytes", 180_000),
            ),
        )
        val first = payload.optString("content")
        if (!payload.has("id") || payload.isNull("id") || payload.optBoolean("eof", false)) return first
        val id = payload.getLong("id")
        val all = StringBuilder(first)
        var offset = first.toByteArray(Charsets.UTF_8).size
        var guard = 0
        while (guard++ < 12 && all.length < 1_500_000) {
            val more = rpc(
                "com.superlogical.terminal.blob_read",
                JSONObject().put("session_id", sid).put("block_id", block).put(
                    "args",
                    JSONObject().put("id", id).put("offset", offset).put("max_bytes", 180_000),
                ),
            )
            val chunk = more.optString("data")
            all.append(chunk)
            offset += chunk.toByteArray(Charsets.UTF_8).size
            if (more.optBoolean("eof", false) || chunk.isEmpty()) break
        }
        return all.toString()
    }

    private suspend fun ensureOwned(sid: String, block: String) {
        if (ownedBlock == block) return
        val previous = ownedBlock
        if (previous != null && previous != block) {
            rpc(
                "session.disown_block",
                JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(previous)),
            )
        }
        rpc(
            "session.own_block",
            JSONObject()
                .put("session_id", sid)
                .put("block_ids", JSONArray().put(block))
                .put("mode", "take"),
        )
        ownedBlock = block
        lastResize = null
        lastVt = null
    }

    private suspend fun disownQuietly() {
        val block = ownedBlock ?: return
        val sid = _state.value.activeSessionId ?: return
        val client = clientRef.get() ?: return
        try {
            client.call(
                "session.disown_block",
                JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)),
            )
        } catch (_: Exception) {
        }
        ownedBlock = null
    }

    private suspend fun statusLoop() {
        while (true) {
            val view = _state.value.view
            if (view != null) {
                val next = LinkedHashMap<String, ProgramRecord>()
                for (block in view.allBlocks()) {
                    if (block.creator != null && block.creator != "com.superlogical.terminal") continue
                    try {
                        val payload = rpc(
                            "com.superlogical.terminal.program_status",
                            JSONObject().put("session_id", view.sessionId).put("block_id", block.id).put("args", JSONObject()),
                        )
                        summarizeStatus(parseProgramRecords(payload))?.let { next[block.id] = it }
                    } catch (_: Exception) {
                    }
                }
                val focused = view.focusedBlock()?.id
                var pwd: String? = _state.value.pwd
                if (focused != null) {
                    try {
                        val payload = rpc(
                            "com.superlogical.terminal.pwd",
                            JSONObject().put("session_id", view.sessionId).put("block_id", focused).put("args", JSONObject()),
                        )
                        pwd = payload.optNullableString("pwd")?.removePrefix("file://")
                    } catch (_: Exception) {
                    }
                }
                _state.update { it.copy(blockStatus = next, pwd = pwd) }
            }
            delay(1_500)
        }
    }

    private suspend fun rpc(method: String, payload: JSONObject?): JSONObject {
        val client = clientRef.get() ?: throw IllegalStateException("Not connected yet")
        return callMutex.withLock { client.call(method, payload) }
    }

    private fun requireSession(): String =
        _state.value.activeSessionId ?: throw IllegalStateException("No open session")

    private fun requireBlock(): Pair<String, String> {
        val sid = requireSession()
        val block = _state.value.view?.focusedBlock()?.id ?: throw IllegalStateException("No terminal in this window")
        return sid to block
    }

    private fun mergeSession(list: List<ListedSession>, item: ListedSession): List<ListedSession> {
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx < 0) return list + item
        return list.map { if (it.id == item.id) item else it }
    }
}

private fun String.firstCodePoint(): Int =
    if (isEmpty()) 0 else codePointAt(0)

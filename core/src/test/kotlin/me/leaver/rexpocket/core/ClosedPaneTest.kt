package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pane on the phone's screen that closes - on the desktop, from another client, or because its
 * program exited - is a state to show, not an error. Rex refuses a ticket for it with "block ... is
 * not in the session"; that used to reach the screen as a red "Rex refused" line over an empty pane
 * that offered to retry, and retrying only met the same refusal.
 */
class ClosedPaneTest {
    @Test
    fun aPaneClosedOnTheDesktopIsShownAsClosedNotAsAnError() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a2")
        assertEquals("block:a2", session.state.value.view?.focusedBlock()?.id)

        rex.closeWindowFromDesktop("session:a", "window:a2")
        val closed = withTimeout(5_000) { awaitState(session) { it.closed != null }.closed!! }
        assertEquals(ClosedPane("session:a", "block:a2", tabClosed = true), closed)
        // The view has moved to the tab that is left; the screen holds it back behind the note.
        assertEquals("block:a1", session.state.value.view?.focusedBlock()?.id)

        // The screen's stream for the closed pane asks once more, is refused, and stops there.
        withTimeout(5_000) { session.watchPane("block:a2").collect { } }
        delay(500)
        assertEquals("tickets asked for the closed pane", 1, rex.ticketsFor("block:a2"))
        assertNull("a closed pane is not an error", session.state.value.notice)

        // Moving on shows the tab that is left, as the phone's own place.
        session.leaveClosedPane()
        assertNull(session.state.value.closed)
        assertEquals("block:a1", session.state.value.view?.focusedBlock()?.id)
    }

    @Test
    fun aCloseWhoseNewsHasNotArrivedIsFoundWhenRexRefusesThePane() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a2")
        // The pane's program exits; the stream ends before Rex's event for it reaches the phone.
        rex.closeWindowFromDesktop("session:a", "window:a2", announce = false)

        withTimeout(5_000) { session.watchPane("block:a2").collect { } }
        val state = session.state.value
        assertEquals(ClosedPane("session:a", "block:a2", tabClosed = true), state.closed)
        assertNull("a closed pane is not an error", state.notice)
        assertEquals(1, rex.ticketsFor("block:a2"))
    }

    @Test
    fun aPaneThePhoneClosesItselfMovesOnToTheTabThatIsLeft() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a2")
        session.closeWindow("window:a2")
        withTimeout(5_000) { awaitState(session) { it.view?.focusedBlock()?.id == "block:a1" } }
        delay(300)
        assertNull("closed by this phone, so nothing to explain", session.state.value.closed)
        assertNull(session.state.value.notice)
    }

    @Test
    fun aRefusalForAPaneThatIsStillThereIsStillAnError() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a1")
        rex.refuseTickets = true
        val watching = launch(Dispatchers.Default) { session.watchPane("block:a1").collect { } }
        try {
            val notice = withTimeout(10_000) { awaitState(session) { it.notice != null }.notice!! }
            assertEquals(Trouble.Rejected, notice.problem.trouble)
            assertNull(session.state.value.closed)
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun aSessionClosedOnTheDesktopTakesThePhoneBackToTheList() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a2")
        rex.destroyFromDesktop("session:a")
        withTimeout(5_000) { awaitState(session) { it.sessions.none { s -> s.id == "session:a" } } }
        val state = session.state.value
        // No session is active, which is what returns the app to its list. The other session is
        // not put on screen: this connection never attached it, so Rex would refuse its panes.
        assertNull(state.activeSessionId)
        assertNull(state.view)
        assertNull(state.notice)

        rex.touch("session:b")
        delay(500)
        assertNull("a view of a session never opened stayed off the screen", session.state.value.view)
        assertNull(session.state.value.activeSessionId)
    }

    /**
     * Entering full screen grows the pane while the bars go away, so its size changes several times
     * within one round trip. The last size set while a resize was out was dropped, and the pane kept
     * the grid of the bars until the next size came - a rotation.
     */
    @Test
    fun aSizeSetWhileAResizeIsOutIsSentWhenItIsAnswered() = withRex { rex, session ->
        session.openSession("session:a", rememberedBlock = "block:a1")
        rex.resizeDelayMs = 400
        session.setSize(80, 24, 8, 16)
        val watching = launch(Dispatchers.Default) { session.watchPane("block:a1").collect { } }
        try {
            withTimeout(5_000) { while (80 !in rex.resizes) delay(10) }
            // While that resize is out, the bars go: two more sizes, the last one the one that counts.
            session.setSize(90, 30, 8, 16)
            delay(50)
            session.setSize(100, 40, 8, 16)
            withTimeout(5_000) { while (rex.resizes.lastOrNull() != 100) delay(20) }
            assertEquals(100, rex.resizes.last())
        } finally {
            watching.cancel()
        }
    }

    private fun withRex(body: suspend CoroutineScope.(FakeRex, PocketSession) -> Unit) = runBlocking {
        FakeRex().use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope, preferredSessionId = "session:a")
                withTimeout(10_000) { session.awaitReady() }
                withTimeout(5_000) { awaitState(session) { it.phase == Phase.Live } }
                body(rex, session)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    private suspend fun awaitState(session: PocketSession, ok: (PocketState) -> Boolean): PocketState {
        while (true) {
            val st = session.state.value
            if (ok(st)) return st
            delay(20)
        }
    }
}

/**
 * Enough of Rex's control connection for a session's tabs and panes to open and close. Two
 * sessions: `session:a` with tabs `window:a1` (`block:a1`) and `window:a2` (`block:a2`), and
 * `session:b` with one. A ticket for a pane that is not in the attached session is refused the way
 * Rex refuses it; one for a pane that is there is never answered, unless [refuseTickets] is set.
 */
private class FakeRex : AutoCloseable {
    private class Tab(val id: String, val blocks: MutableList<String>)

    private class Session(val id: String, val tabs: MutableList<Tab>, var revision: Long = 1)

    private val sessions = linkedMapOf(
        "session:a" to Session("session:a", mutableListOf(Tab("window:a1", mutableListOf("block:a1")), Tab("window:a2", mutableListOf("block:a2")))),
        "session:b" to Session("session:b", mutableListOf(Tab("window:b1", mutableListOf("block:b1")))),
    )
    private val tickets = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var control: WebSocket? = null
    @Volatile private var attached: String? = null
    @Volatile var refuseTickets = false

    /** The columns of every resize asked for, in the order they were asked. */
    val resizes = java.util.concurrent.CopyOnWriteArrayList<Int>()

    /** How long a resize takes to be answered, as over a slow tailnet. */
    @Volatile var resizeDelayMs = 0L

    private val server = MockWebServer()
    val endpoint: Endpoint

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/control" -> MockResponse().withWebSocketUpgrade(listener())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        endpoint = Endpoint("127.0.0.1", server.port, plaintext = true)
    }

    fun ticketsFor(block: String): Int = tickets[block]?.get() ?: 0

    /** Closes a tab the way the desktop does; [announce] false loses Rex's news of it. */
    fun closeWindowFromDesktop(sid: String, window: String, announce: Boolean = true) {
        synchronized(this) {
            val s = sessions.getValue(sid)
            s.tabs.removeAll { it.id == window }
            s.revision++
        }
        if (announce) {
            sessionEvent(sid, "block_closed", JSONObject())
            sessionEvent(sid, "window_closed", JSONObject().put("window_id", window))
        }
    }

    fun destroyFromDesktop(sid: String) {
        synchronized(this) { sessions.remove(sid) }
        sessionEvent(sid, "session_destroyed", JSONObject())
    }

    /** Something changed in [sid]'s view, as every session's news reaches every client. */
    fun touch(sid: String) {
        val revision = synchronized(this) { sessions.getValue(sid).let { ++it.revision } }
        sessionEvent(sid, "session_view_changed", JSONObject().put("revision", revision))
    }

    private fun sessionEvent(sid: String, type: String, payload: JSONObject) {
        val event = JSONObject().put("type", "session_event").put(
            "payload",
            JSONObject().put("session_id", sid).put("event", JSONObject().put("type", type).put("payload", payload)),
        )
        control?.send(JSONObject().put("type", "event").put("payload", event).toString())
    }

    private fun view(s: Session): JSONObject = JSONObject()
        .put("revision", s.revision)
        .put("session_id", s.id)
        .put("label", s.id.substringAfter(':'))
        .put("active_window_id", s.tabs.firstOrNull()?.id)
        .put(
            "windows",
            JSONArray().apply {
                for (tab in s.tabs) {
                    put(
                        JSONObject()
                            .put("window_id", tab.id)
                            .put("focused_block_id", tab.blocks.firstOrNull())
                            .put(
                                "layers",
                                JSONArray().put(
                                    JSONObject().put(
                                        "blocks",
                                        JSONArray().apply { tab.blocks.forEach { put(JSONObject().put("block_id", it)) } },
                                    ),
                                ),
                            ),
                    )
                }
            },
        )

    private fun list(): JSONArray = JSONArray().apply {
        sessions.values.forEach { put(JSONObject().put("session_id", it.id).put("label", it.id.substringAfter(':'))) }
    }

    private fun answer(method: String, payload: JSONObject): Pair<JSONObject?, Triple<String, Int, String>?> = synchronized(this) {
        when (method) {
            "session.bootstrap" -> {
                val s = sessions[payload.optString("preferred_session_id")] ?: sessions.values.firstOrNull()
                attached = s?.id
                JSONObject()
                    .put("attach_all", JSONObject().put("sessions", list()))
                    .put("selected_session", if (s == null) JSONObject() else JSONObject().put("view", view(s))) to null
            }
            "session.attach" -> {
                val s = sessions[payload.optString("session_id")]
                    ?: return@synchronized null to Triple("session_not_found", 404, "no such session")
                attached = s.id
                JSONObject().put("view", view(s)) to null
            }
            "session.view" -> {
                val s = sessions[payload.optString("session_id")]
                    ?: return@synchronized null to Triple("session_not_found", 404, "no such session")
                view(s) to null
            }
            "session.list" -> JSONObject().put("sessions", list()) to null
            "session.close_window" -> {
                val s = sessions.getValue(payload.getString("session_id"))
                s.tabs.removeAll { it.id == payload.optString("window_id") }
                s.revision++
                JSONObject() to null
            }
            "com.superlogical.terminal.resize" -> {
                resizes += payload.getJSONObject("args").getInt("columns")
                JSONObject() to null
            }
            "data.connect" -> {
                val block = payload.optString("block_id")
                tickets.computeIfAbsent(block) { AtomicInteger() }.incrementAndGet()
                val inSession = sessions[attached]?.tabs?.any { block in it.blocks } == true
                when {
                    !inSession -> null to Triple("invalid_request", 400, "block $block is not in the session")
                    refuseTickets -> null to Triple("internal", 500, "the terminal is busy")
                    else -> null to null
                }
            }
            else -> JSONObject() to null
        }
    }

    private fun listener() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "hello" -> {
                    control = webSocket
                    webSocket.send(
                        JSONObject()
                            .put("type", "welcome")
                            .put("api_revision", 7)
                            .put("limits", JSONObject().put("max_message_bytes", 1_048_576))
                            .put("client", JSONObject().put("id", "client:phone"))
                            .toString(),
                    )
                }
                "request" -> {
                    val id = message.getString("id")
                    val method = message.getString("method")
                    val (payload, error) = answer(method, message.optJSONObject("payload") ?: JSONObject())
                    val reply = JSONObject().put("type", "response").put("id", id)
                    when {
                        error != null -> reply.put(
                            "error",
                            JSONObject().put("code", error.first).put("status", error.second).put("message", error.third),
                        )
                        payload != null -> reply.put("payload", payload)
                        // A ticket for a pane that is there is left unanswered: no stream is needed.
                        else -> return
                    }
                    if (method == "com.superlogical.terminal.resize" && resizeDelayMs > 0) {
                        val wait = resizeDelayMs
                        kotlin.concurrent.thread(isDaemon = true) {
                            Thread.sleep(wait)
                            webSocket.send(reply.toString())
                        }
                        return
                    }
                    webSocket.send(reply.toString())
                    // Rex tells every client about a change it made, the asking one included.
                    if (method == "session.close_window") {
                        val sid = message.getJSONObject("payload").getString("session_id")
                        sessionEvent(sid, "window_closed", JSONObject())
                    }
                }
            }
        }
    }

    override fun close() {
        server.shutdown()
    }
}

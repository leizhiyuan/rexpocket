package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Closing the pane this phone owns must not trap the next pane in a failed disown loop.
 * Rex rejects `session.disown_block` for a closed block with `bad_request`.
 */
class CloseOwnedPaneTest {
    @Test
    fun closingOwnedPaneWithBlockClosedEventTakesNextPaneWithoutStaleDisown() = runBlocking {
        FakeRexForClose(sendBlockClosedEvent = true).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                val watchFirst = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:1").collect { }
                }
                withTimeout(5_000) {
                    while (rex.ownerOf("block:1") != "client:test" || !rex.hasOpenDataStream()) delay(20)
                }

                session.closeBlock("block:1")
                // Rex closes the pane's data stream with 4000; watchPane must finish quietly.
                withTimeout(5_000) { watchFirst.join() }
                assertNull("closing a pane should not note a problem", session.state.value.notice)

                // Wait for block_closed -> refreshView to update the focused pane to block:2.
                withTimeout(5_000) {
                    session.state.first { it.view?.focusedBlock()?.id == "block:2" }
                }

                val watchSecond = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:2").collect { }
                }
                try {
                    withTimeout(5_000) {
                        while (rex.ownerOf("block:2") != "client:test" || !session.state.value.owning) delay(20)
                    }
                    assertTrue("new pane should be reported as owned", session.state.value.owning)
                    assertNull("no problem should be noted", session.state.value.notice)
                    assertFalse(
                        "closed pane should not be disowned after block_closed",
                        "block:1" in rex.disownCalls,
                    )
                } finally {
                    watchSecond.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun closingOwnedPaneWithoutBlockClosedEventStillTakesNextPane() = runBlocking {
        FakeRexForClose(sendBlockClosedEvent = false).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                val watchFirst = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:1").collect { }
                }
                withTimeout(5_000) {
                    while (rex.ownerOf("block:1") != "client:test" || !rex.hasOpenDataStream()) delay(20)
                }

                session.closeBlock("block:1")
                watchFirst.cancel()

                // Even if ensureOwned runs before block_closed is processed and Rex rejects
                // disowning the closed block with bad_request, the next pane must still be taken.
                val watchSecond = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:2").collect { }
                }
                try {
                    withTimeout(5_000) {
                        while (rex.ownerOf("block:2") != "client:test" || !session.state.value.owning) delay(20)
                    }
                    assertTrue("disown fallback path should have been exercised", "block:1" in rex.disownCalls)
                    assertTrue("new pane should be reported as owned", session.state.value.owning)
                    assertNull("no problem should be noted", session.state.value.notice)
                    assertEquals("client:test", rex.ownerOf("block:2"))
                } finally {
                    watchSecond.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun transientDisownFailureRetriesDisownBeforeTakingNextPane() = runBlocking {
        FakeRexForClose(sendBlockClosedEvent = true, transientDisownErrors = 1).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                val watchFirst = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:1").collect { }
                }
                withTimeout(5_000) {
                    while (rex.ownerOf("block:1") != "client:test" || !rex.hasOpenDataStream()) delay(20)
                }
                watchFirst.cancel()
                watchFirst.join()

                val watchSecond = scope.launch(Dispatchers.Default) {
                    session.watchPane("block:2").collect { }
                }
                try {
                    withTimeout(5_000) {
                        while (rex.ownerOf("block:2") != "client:test" || !session.state.value.owning) delay(20)
                    }
                    assertEquals(
                        "transient disown failure must keep ownership tracking and retry disowning block:1",
                        2,
                        rex.disownCalls.count { it == "block:1" },
                    )
                    assertNull("block:1 should be disowned once the retry succeeds", rex.ownerOf("block:1"))
                } finally {
                    watchSecond.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun watchingABlankBlockIdEndsImmediatelyWithoutCallingRex() = runBlocking {
        FakeRexForClose(sendBlockClosedEvent = true).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                withTimeout(5_000) {
                    session.watchPane("").collect { }
                }
                assertNull("blank blockId should not note a problem", session.state.value.notice)
                assertTrue("blank blockId should not request a data ticket", rex.dataConnectCalls.isEmpty())
                assertTrue("blank blockId should not own a block", rex.ownCalls.isEmpty())
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun closingLastPaneDestroysTheSession() = runBlocking {
        FakeRexForClose(
            sendBlockClosedEvent = true,
            initialSessions = mapOf("session:1" to listOf("block:1")),
        ).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                session.closeBlock("block:1")
                assertEquals(listOf("session:1"), rex.destroyedSessions)
                assertTrue("session should be removed after closing its last pane", session.state.value.sessions.isEmpty())
                assertNull("activeSessionId should be cleared", session.state.value.activeSessionId)
                // Wait for the trailing block_closed -> session.view -> session_not_found round trip.
                withTimeout(5_000) {
                    while ("session:1" !in rex.sessionNotFoundViews) delay(20)
                }
                // Let the control event collector finish handling the session_not_found reply.
                delay(50)
                assertNull("destroying the session should not note session_not_found", session.state.value.notice)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun closingLastTabDestroysTheSession() = runBlocking {
        FakeRexForClose(
            sendBlockClosedEvent = true,
            initialSessions = mapOf("session:1" to listOf("block:1", "block:2")),
        ).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                session.closeWindow("win:1")
                assertEquals(listOf("session:1"), rex.destroyedSessions)
                assertTrue("session should be removed after closing its last tab", session.state.value.sessions.isEmpty())
                assertNull("activeSessionId should be cleared", session.state.value.activeSessionId)
                withTimeout(5_000) {
                    while ("session:1" !in rex.sessionNotFoundViews) delay(20)
                }
                delay(50)
                assertNull("destroying the session should not note session_not_found", session.state.value.notice)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun closingUnknownBlockDoesNotDestroySession() = runBlocking {
        FakeRexForClose(
            sendBlockClosedEvent = true,
            initialSessions = mapOf("session:1" to listOf("block:1")),
        ).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                session.closeBlock("block:stale")
                assertTrue("unknown blockId must not destroy the session", rex.destroyedSessions.isEmpty())
                assertEquals(listOf("block:stale"), rex.closeBlockCalls)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }

    @Test
    fun closingLastPaneInMultiSessionSwitchesToNextSessionQuietly() = runBlocking {
        FakeRexForClose(
            sendBlockClosedEvent = true,
            initialSessions = linkedMapOf(
                "session:1" to listOf("block:1"),
                "session:2" to listOf("block:2"),
            ),
        ).use { rex ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(rex.endpoint)
            try {
                session.start(scope)
                withTimeout(5_000) { session.awaitReady() }

                session.closeBlock("block:1")
                assertEquals(listOf("session:1"), rex.destroyedSessions)
                assertEquals("session:2", session.state.value.activeSessionId)
                assertEquals("session:2", session.state.value.view?.sessionId)
                assertEquals("block:2", session.state.value.view?.focusedBlock()?.id)
                withTimeout(5_000) {
                    while ("session:1" !in rex.sessionNotFoundViews) delay(20)
                }
                delay(50)
                assertNull("trailing block_closed from destroyed session must not surface a notice", session.state.value.notice)
            } finally {
                session.stop()
                scope.cancel()
            }
        }
    }
}

private class FakeRexForClose(
    private val sendBlockClosedEvent: Boolean,
    initialSessions: Map<String, List<String>> = mapOf("session:1" to listOf("block:1", "block:2")),
    transientDisownErrors: Int = 0,
) : AutoCloseable {
    private val server = MockWebServer()
    private val sessionOrder = CopyOnWriteArrayList(initialSessions.keys)
    private val liveBlocks = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>().apply {
        for ((sid, blocks) in initialSessions) put(sid, CopyOnWriteArrayList(blocks))
    }
    private val owners = ConcurrentHashMap<String, String>()
    private val revision = AtomicLong(1)
    private val dataSockets = CopyOnWriteArrayList<WebSocket>()
    private val remainingTransientDisowns = AtomicInteger(transientDisownErrors)
    val disownCalls = CopyOnWriteArrayList<String>()
    val ownCalls = CopyOnWriteArrayList<String>()
    val dataConnectCalls = CopyOnWriteArrayList<String>()
    val closeBlockCalls = CopyOnWriteArrayList<String>()
    val destroyedSessions = CopyOnWriteArrayList<String>()
    val sessionNotFoundViews = CopyOnWriteArrayList<String>()

    private val ticket = ByteArray(16) { (it + 1).toByte() }
    private val ticketText = Base64.getUrlEncoder().withoutPadding().encodeToString(ticket)

    val endpoint: Endpoint
        get() = Endpoint(server.hostName, server.port, plaintext = true)

    fun ownerOf(blockId: String): String? = owners[blockId]

    fun hasOpenDataStream(): Boolean = dataSockets.isNotEmpty()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/control" -> MockResponse().withWebSocketUpgrade(control())
                "/api/data" -> MockResponse().withWebSocketUpgrade(data())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun control() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "hello" -> webSocket.send(
                    JSONObject()
                        .put("type", "welcome")
                        .put("api_revision", 11)
                        .put("limits", JSONObject().put("max_message_bytes", 1_048_576))
                        .put("client", JSONObject().put("id", "client:test"))
                        .put("reconnect_token", "tok:test")
                        .toString(),
                )
                "request" -> handleRequest(webSocket, message)
            }
        }
    }

    private fun handleRequest(webSocket: WebSocket, message: JSONObject) {
        val id = message.getString("id")
        val method = message.getString("method")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (method) {
            "session.bootstrap" -> {
                val sessions = JSONArray()
                for (sid in sessionOrder) {
                    if (sid !in destroyedSessions) {
                        sessions.put(JSONObject().put("session_id", sid).put("label", sid))
                    }
                }
                val first = sessionOrder.first { it !in destroyedSessions }
                webSocket.send(
                    response(
                        id,
                        JSONObject()
                            .put("attach_all", JSONObject().put("sessions", sessions))
                            .put("selected_session", JSONObject().put("view", viewJson(first))),
                    ),
                )
            }
            "session.attach" -> {
                val sid = payload.getString("session_id")
                if (sid in destroyedSessions || !liveBlocks.containsKey(sid)) {
                    webSocket.send(error(id, "session_not_found", 404, "session $sid not found"))
                } else {
                    webSocket.send(response(id, JSONObject().put("view", viewJson(sid))))
                }
            }
            "session.view" -> {
                val sid = payload.optString("session_id")
                if (sid in destroyedSessions || !liveBlocks.containsKey(sid)) {
                    sessionNotFoundViews += sid
                    webSocket.send(error(id, "session_not_found", 404, "session $sid not found"))
                } else {
                    webSocket.send(response(id, viewJson(sid)))
                }
            }
            "session.destroy" -> {
                val sid = payload.getString("session_id")
                val blocks = liveBlocks[sid]
                val trailingBlock = blocks?.firstOrNull() ?: "block:1"
                destroyedSessions += sid
                blocks?.forEach { owners.remove(it) }
                blocks?.clear()
                dataSockets.forEach { it.close(4000, "session destroyed") }
                dataSockets.clear()
                webSocket.send(response(id, JSONObject()))
                // Match real Rex: destroying a session can emit block_closed right before/alongside it.
                webSocket.send(
                    JSONObject()
                        .put("type", "event")
                        .put(
                            "payload",
                            JSONObject()
                                .put("type", "session_event")
                                .put(
                                    "payload",
                                    JSONObject()
                                        .put("session_id", sid)
                                        .put(
                                            "event",
                                            JSONObject()
                                                .put("type", "block_closed")
                                                .put("payload", JSONObject().put("block_id", trailingBlock)),
                                        ),
                                ),
                        )
                        .toString(),
                )
            }
            "session.own_block" -> {
                val sid = payload.getString("session_id")
                val blocks = liveBlocks[sid].orEmpty()
                val ids = payload.optJSONArray("block_ids") ?: JSONArray()
                for (i in 0 until ids.length()) {
                    val blockId = ids.getString(i)
                    ownCalls += blockId
                    if (blockId !in blocks) {
                        webSocket.send(error(id, "bad_request", 400, "block $blockId is not in the session"))
                        return
                    }
                    owners[blockId] = "client:test"
                }
                webSocket.send(response(id, JSONObject()))
            }
            "session.disown_block" -> {
                val sid = payload.getString("session_id")
                val blocks = liveBlocks[sid].orEmpty()
                val ids = payload.optJSONArray("block_ids") ?: JSONArray()
                for (i in 0 until ids.length()) {
                    val blockId = ids.getString(i)
                    disownCalls += blockId
                    if (remainingTransientDisowns.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                        webSocket.send(error(id, "internal", 500, "temporary error"))
                        return
                    }
                    if (blockId !in blocks) {
                        webSocket.send(error(id, "bad_request", 400, "block $blockId is not in the session"))
                        return
                    }
                    owners.remove(blockId)
                }
                webSocket.send(response(id, JSONObject()))
            }
            "block.close" -> {
                val sid = payload.getString("session_id")
                val blockId = payload.getString("block_id")
                closeBlockCalls += blockId
                val blocks = liveBlocks[sid]
                val removed = blocks?.remove(blockId) == true
                if (removed) {
                    owners.remove(blockId)
                    revision.incrementAndGet()
                    // Match real Rex: closing a block closes its terminal data stream with 4000.
                    dataSockets.forEach { it.close(4000, "block closed") }
                    dataSockets.clear()
                }
                webSocket.send(response(id, JSONObject()))
                if (removed && sendBlockClosedEvent) {
                    webSocket.send(
                        JSONObject()
                            .put("type", "event")
                            .put(
                                "payload",
                                JSONObject()
                                    .put("type", "session_event")
                                    .put(
                                        "payload",
                                        JSONObject()
                                            .put("session_id", sid)
                                            .put(
                                                "event",
                                                JSONObject()
                                                    .put("type", "block_closed")
                                                    .put("payload", JSONObject().put("block_id", blockId)),
                                            ),
                                    ),
                            )
                            .toString(),
                    )
                }
            }
            "data.connect" -> {
                dataConnectCalls += payload.optString("block_id")
                webSocket.send(response(id, JSONObject().put("ticket", ticketText)))
            }
            "com.superlogical.terminal.resize" -> webSocket.send(response(id, JSONObject()))
            "com.superlogical.terminal.size" -> {
                val blockId = payload.optString("block_id")
                val out = JSONObject().put("columns", 80).put("rows", 24)
                owners[blockId]?.let { out.put("owner", it) }
                webSocket.send(response(id, out))
            }
            "com.superlogical.terminal.program_status" -> webSocket.send(response(id, JSONObject().put("records", JSONArray())))
            "com.superlogical.terminal.pwd" -> webSocket.send(response(id, JSONObject().put("pwd", "/tmp")))
            "com.superlogical.terminal.process", "com.superlogical.terminal.title" -> webSocket.send(response(id, JSONObject()))
            else -> webSocket.send(error(id, "method_not_found", 404, "unknown method $method"))
        }
    }

    private fun viewJson(sid: String): JSONObject {
        val sessionBlocks = liveBlocks[sid].orEmpty()
        val blocks = JSONArray()
        for (blockId in sessionBlocks) {
            val block = JSONObject()
                .put("block_id", blockId)
                .put("label", blockId)
                .put("creator_name", "com.superlogical.terminal")
            owners[blockId]?.let { block.put("owner", it) }
            blocks.put(block)
        }
        val window = JSONObject()
            .put("window_id", "win:1")
            .put("label", "tab")
            .put("focused_block_id", sessionBlocks.firstOrNull())
            .put("layers", JSONArray().put(JSONObject().put("blocks", blocks)))
        return JSONObject()
            .put("revision", revision.get())
            .put("session_id", sid)
            .put("label", sid)
            .put("active_window_id", "win:1")
            .put("windows", JSONArray().put(window))
    }

    private fun data() = object : WebSocketListener() {
        private var greeted = false

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            dataSockets += webSocket
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dataSockets -= webSocket
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!greeted) {
                greeted = true
                val welcome = ByteArray(32)
                welcome[0] = 0x81.toByte()
                ByteArray(16) { (it + 0x20).toByte() }.copyInto(welcome, 1)
                webSocket.send(welcome.toByteString())
            }
        }
    }

    private fun response(id: String, payload: JSONObject) = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("seq", 0)
        .put("payload", payload)
        .toString()

    private fun error(id: String, code: String, status: Int, message: String) = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("status", status).put("message", message))
        .toString()

    override fun close() {
        server.shutdown()
    }
}

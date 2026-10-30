package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

class PocketSessionTest {
    @Test
    fun echoesThroughPlaintextServer() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            var blockId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                val ready = session.state.value
                assertEquals(Phase.Live, ready.phase)
                assertTrue("handshake client id missing", ready.clientId.orEmpty().startsWith("client:"))

                session.setSize(80, 24, 8, 16)
                val label = "rexpocket-test-" + System.currentTimeMillis()
                session.createShell(label)
                val created = session.state.value
                sessionId = created.activeSessionId
                val view = created.view
                assertNotNull("session.view missing after create", view)
                assertEquals(sessionId, view!!.sessionId)
                assertTrue("session.view has no windows", view.windows.isNotEmpty())
                blockId = view.focusedBlock()?.id
                assertTrue("focused block missing", blockId.orEmpty().startsWith("block:"))

                val owned = withTimeout(15_000) {
                    while (true) {
                        val owner = session.state.value.view?.focusedBlock()?.owner
                        if (owner == created.clientId) return@withTimeout owner
                        delay(50)
                    }
                    null
                }
                assertEquals(created.clientId, owned)

                val stream = StringBuilder()
                val feeder = collectPane(scope, session, blockId!!, stream)
                try {
                    val token = "rexpocket-ok-" + System.nanoTime()
                    session.sendText("printf '$token\\n'\n")
                    val seen = withTimeoutOrNull(15_000) {
                        while (!stripAnsi(stream.toString()).contains(token)) delay(100)
                        true
                    } ?: false
                    assertTrue("stream did not carry $token:\n${stream.toString()}", seen)
                    // The stream opens with the pane as Rex has it, which is what the emulator restores.
                    assertTrue("stream did not start with a snapshot", stream.startsWith(SNAPSHOT_MARK))
                } finally {
                    feeder.cancel()
                }

                // Stop joins the control coroutine so disown runs before the socket closes.
                session.stop()
                assertDisowned(endpoint, sessionId!!, blockId!!)
            } finally {
                session.stop()
                scope.cancel()
                if (sessionId != null) {
                    destroyQuietly(endpoint, sessionId!!)
                }
            }
        }
    }

    /**
     * Keystrokes from the terminal view, the toolbar and the composer go out through different
     * methods. They must still reach the shell in the order they were typed.
     */
    @Test
    fun mixedInputArrivesInTheOrderItWasTyped() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(200, 24, 8, 16)
                session.createShell("rexpocket-test-order-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                val block = awaitOwnedFocus(session)
                val stream = StringBuilder()
                val feeder = collectPane(scope, session, block, stream)

                // Digits come from the terminal view, letters from the toolbar, interleaved.
                val expected = StringBuilder("echo ")
                session.sendText("echo ")
                for (i in 0 until 40) {
                    val letter = 'a' + (i % 26)
                    for (d in 0 until 4) {
                        expected.append((i + d) % 10)
                        session.writeBytes("${(i + d) % 10}".toByteArray())
                    }
                    expected.append(letter)
                    session.sendKey("key_$letter", text = "$letter")
                }
                session.sendKey("enter")
                val line = expected.toString().removePrefix("echo ")
                // The shell's echo of the command line and its output; the terminal may wrap either,
                // so line breaks are taken out before looking.
                // The typed characters must reach the shell in the order they were sent: the shell
                // echoes them, and the stream carries that echo.
                val seen = withTimeoutOrNull(15_000) {
                    while (!stripAnsi(stream.toString()).replace("\r", "").replace("\n", "").contains(line)) delay(100)
                    true
                } ?: false
                feeder.cancel()
                assertTrue("expected $line, stream:\n${stripAnsi(stream.toString())}", seen)
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Stopping the collector ends the stream; collecting again starts over from a new snapshot. */
    @Test
    fun aStreamThatStopsStartsAgainWithASnapshot() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                session.createShell("rexpocket-test-again-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                val block = awaitOwnedFocus(session)

                val first = StringBuilder()
                val feeder = collectPane(scope, session, block, first)
                withTimeout(10_000) { while (!first.startsWith(SNAPSHOT_MARK)) delay(50) }
                session.sendText("echo first-round\n")
                withTimeout(10_000) {
                    while (!stripAnsi(first.toString()).contains("first-round")) delay(50)
                }
                feeder.cancel()
                // Rex takes a moment to notice the stream is gone before the next one can open.
                delay(500)

                val second = StringBuilder()
                val again = collectPane(scope, session, block, second)
                try {
                    // A new collection starts its own stream, and every stream opens with a snapshot.
                    withTimeout(10_000) { while (!second.startsWith(SNAPSHOT_MARK)) delay(50) }
                    session.sendText("echo second-round\n")
                    withTimeout(10_000) {
                        while (!stripAnsi(second.toString()).contains("second-round")) delay(50)
                    }
                } finally {
                    again.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Rex announces a new session as it creates it; the list must still hold it once. */
    @Test
    fun aCreatedSessionIsListedOnce() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.createShell("rexpocket-test-once-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                // The created event can land just after the call returns.
                delay(500)
                val ids = session.state.value.sessions.map { it.id }
                assertEquals("listed twice: $ids", ids.distinct(), ids)
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * The desktop takes a pane back when its window is focused. A pane owned elsewhere keeps the
     * desktop's size, so the phone streams a screen wider than itself with its right side cut off;
     * the phone has to take it back and ask for its own size again.
     */
    @Test
    fun aPaneTakenByAnotherClientIsTakenBack() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val desktop = RexClient()
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-owner-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId!!
                val blockId = awaitOwnedFocus(session)

                val watched = StringBuilder()
                val watcher = collectPane(scope, session, blockId, watched)
                try {
                    val mine = session.state.value.clientId
                    // Watching the pane takes it first; only then can the desktop take it away.
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockId) != mine) delay(100) }
                    // The desktop focuses the pane and takes it, as Rex does while someone is at the desk.
                    desktop.connect(endpoint)
                    desktop.call("session.attach", JSONObject().put("session_id", sessionId))
                    desktop.call(
                        "session.own_block",
                        JSONObject()
                            .put("session_id", sessionId)
                            .put("block_ids", JSONArray().put(blockId))
                            .put("mode", "take"),
                    )
                    // Ask Rex, not the session's cached view: the view can lag behind an owner change.
                    val back = withTimeoutOrNull(20_000) {
                        while (ownerOnServer(endpoint, sessionId!!, blockId) != mine) delay(100)
                        true
                    } ?: false
                    val last = ownerOnServer(endpoint, sessionId!!, blockId)
                    assertTrue("the pane stayed with $last", back)
                } finally {
                    watcher.cancel()
                    desktop.close()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Moving to another session must hand the pane left behind back to the desktop. */
    @Test
    fun switchingSessionsReleasesThePreviousPane() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val created = ArrayList<String>()
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                val stamp = System.currentTimeMillis()

                session.createShell("rexpocket-test-a-$stamp")
                val first = session.state.value.activeSessionId!!
                created += first
                // Taking a pane is part of watching it, so both panes are actually watched here.
                val firstBlock = awaitWatchedPane(scope, session)
                session.createShell("rexpocket-test-b-$stamp")
                created += session.state.value.activeSessionId!!
                awaitWatchedPane(scope, session)

                assertDisowned(endpoint, first, firstBlock)
            } finally {
                session.stop()
                scope.cancel()
                created.forEach { destroyQuietly(endpoint, it) }
            }
        }
    }
}

/** The owner of a pane according to Rex, read with a fresh connection. */
private suspend fun ownerOnServer(endpoint: Endpoint, sessionId: String, blockId: String): String? {
    val probe = RexClient()
    try {
        probe.connect(endpoint)
        probe.call("session.attach", JSONObject().put("session_id", sessionId))
        return probe.call(
            "com.superlogical.terminal.size",
            JSONObject().put("session_id", sessionId).put("block_id", blockId),
        ).optNullableString("owner")
    } finally {
        probe.close()
    }
}

/** Watches the shown pane until its snapshot arrives, and returns the block it is. */
private suspend fun awaitWatchedPane(scope: CoroutineScope, session: PocketSession): String {
    val block = awaitOwnedFocus(session)
    val seen = StringBuilder()
    val feeder = collectPane(scope, session, block, seen)
    try {
        withTimeout(15_000) {
            while (!synchronized(seen) { seen.startsWith(SNAPSHOT_MARK) }) delay(50)
        }
    } finally {
        feeder.cancel()
    }
    return block
}

/**
 * Waits until the shown pane belongs to this client, and returns its id. Creating a session already
 * makes the creator the owner, so this alone does not prove the phone took it.
 */
private suspend fun awaitOwnedFocus(session: PocketSession): String = withTimeout(15_000) {
    while (true) {
        val st = session.state.value
        val block = st.view?.takeIf { it.sessionId == st.activeSessionId }?.focusedBlock()
        if (block != null && block.owner == st.clientId) return@withTimeout block.id
        delay(50)
    }
    error("unreachable")
}

/** Marks where a snapshot began in a collected stream. */
private const val SNAPSHOT_MARK = "\u0000SNAPSHOT\u0000"

/**
 * Collects a pane the way the terminal screen does, into [into]: a mark per snapshot, then only the
 * output that followed the last one, which is what a restored emulator would show growing. The
 * collector must outlive the typing, so it is returned to cancel at the end.
 */
private fun collectPane(scope: CoroutineScope, session: PocketSession, blockId: String, into: StringBuilder) =
    scope.launch(Dispatchers.Default) {
        session.watchPane(blockId).collect { feed ->
            synchronized(into) {
                when (feed) {
                    is TerminalFeed.Snapshot -> {
                        into.append(SNAPSHOT_MARK)
                        // The snapshot replaces the screen, so earlier text is no longer shown.
                        into.setLength(into.length)
                    }
                    is TerminalFeed.Output -> into.append(feed.bytes.toString(Charsets.UTF_8))
                }
            }
        }
    }

/** Text with its escape sequences removed, close enough to what a screen would show. */
private fun stripAnsi(text: String): String = text
    .replace(Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]"), "")
    .replace(Regex("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)"), "")

private fun testEndpoint(): Endpoint {
    val host = System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val port = System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297
    return Endpoint(host, port, plaintext = true)
}

private fun portOpen(host: String, port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
} catch (_: Exception) {
    false
}

private suspend fun assertDisowned(endpoint: Endpoint, sessionId: String, blockId: String) {
    val probe = RexClient()
    try {
        val welcome = probe.connect(endpoint)
        assertTrue(welcome.clientId.startsWith("client:"))
        val attached = probe.call("session.attach", JSONObject().put("session_id", sessionId))
        val view = parseView(attached.getJSONObject("view"))
        val owner = view.allBlocks().firstOrNull { it.id == blockId }?.owner
        assertNull("block still owned after disown: $owner", owner)
    } finally {
        probe.close()
    }
}

private fun destroyQuietly(endpoint: Endpoint, sessionId: String) {
    runBlocking {
        val probe = RexClient()
        try {
            probe.connect(endpoint)
            probe.call("session.attach", JSONObject().put("session_id", sessionId))
            probe.call("session.destroy", JSONObject().put("session_id", sessionId))
        } catch (_: Exception) {
        } finally {
            probe.close()
        }
    }
}

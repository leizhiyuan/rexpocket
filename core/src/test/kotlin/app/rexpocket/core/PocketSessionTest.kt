package app.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

                val token = "rexpocket-ok-" + System.nanoTime()
                session.sendText("printf '$token\\n'\n")
                val seen = withTimeout(15_000) {
                    while (true) {
                        val text = session.state.value.vt.orEmpty()
                        if (text.contains(token)) return@withTimeout text
                        delay(100)
                    }
                    ""
                }
                assertTrue(seen.contains(token))

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
                awaitOwnedFocus(session)

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
                val seen = withTimeoutOrNull(15_000) {
                    while (screenText(session).replace("\r", "").replace("\n", "").split(line).size < 3) delay(100)
                    true
                } ?: false
                assertTrue("expected $line, screen:\n${screenText(session)}", seen)
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** While the app is in the background nothing is polled; coming back reads the screen at once. */
    @Test
    fun aPausedSessionReadsNothingUntilItResumes() {
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
                session.createShell("rexpocket-test-pause-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                awaitOwnedFocus(session)

                session.setActive(false)
                delay(500)
                val before = session.state.value.vtVersion
                session.sendText("echo paused-marker\n")
                delay(1_500)
                assertEquals("screen read while paused", before, session.state.value.vtVersion)

                session.setActive(true)
                val seen = withTimeoutOrNull(5_000) {
                    while (!screenText(session).contains("\npaused-marker")) delay(50)
                    true
                } ?: false
                assertTrue("screen not read after resuming", seen)
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
                val firstBlock = awaitOwnedFocus(session)

                session.createShell("rexpocket-test-b-$stamp")
                created += session.state.value.activeSessionId!!
                awaitOwnedFocus(session)

                assertDisowned(endpoint, first, firstBlock)
            } finally {
                session.stop()
                scope.cancel()
                created.forEach { destroyQuietly(endpoint, it) }
            }
        }
    }
}

/**
 * Waits until the shown pane is on screen, owned by this client, and returns its id. Creating a
 * session already makes the creator the owner, so ownership alone does not prove the phone took it.
 */
private suspend fun awaitOwnedFocus(session: PocketSession): String = withTimeout(15_000) {
    while (true) {
        val st = session.state.value
        val block = st.view?.takeIf { it.sessionId == st.activeSessionId }?.focusedBlock()
        if (block != null && block.owner == st.clientId && st.vtBlockId == block.id) return@withTimeout block.id
        delay(50)
    }
    error("unreachable")
}

/** The shown pane's screen with its escape sequences removed, close enough to what is on it. */
private fun screenText(session: PocketSession): String =
    session.state.value.vt.orEmpty()
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

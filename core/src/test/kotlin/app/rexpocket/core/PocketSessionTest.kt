package app.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                        val text = session.state.value.term?.plainScreen().orEmpty()
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
}

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

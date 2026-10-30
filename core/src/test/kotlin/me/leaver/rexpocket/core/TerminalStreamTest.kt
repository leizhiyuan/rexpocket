package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

/** The terminal stream against a real Rex: a snapshot of the pane, then its output as it comes. */
class TerminalStreamTest {
    @Test
    fun theStreamStartsWithASnapshotAndThenCarriesOutput() {
        val endpoint = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${endpoint.host}:${endpoint.port}", portOpen(endpoint.host, endpoint.port))
        runBlocking {
            val control = RexClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var sessionId: String? = null
            try {
                control.connect(endpoint)
                control.call("session.bootstrap", JSONObject().put("event_scope", "session"))
                val created = control.call(
                    "session.create",
                    JSONObject().put("label", "rexpocket-stream-" + System.currentTimeMillis()).put(
                        "initial_windows",
                        org.json.JSONArray().put(
                            JSONObject().put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell"))),
                        ),
                    ),
                )
                sessionId = created.getString("session_id")
                val view = parseView(control.call("session.attach", JSONObject().put("session_id", sessionId)).getJSONObject("view"))
                val block = view.focusedBlock()!!.id

                val stream = TerminalStream(endpoint, { RexClient.defaultHttp() }) {
                    control.call(
                        "data.connect",
                        JSONObject()
                            .put("session_id", sessionId)
                            .put("block_id", block)
                            .put("data_ref_id", "terminal")
                            .put("subprotocol", TerminalStream.PROTOCOL),
                    ).getString("ticket")
                }
                val snapshot = StringBuilder()
                val output = StringBuilder()
                scope.launch {
                    stream.feeds().collect { feed ->
                        when (feed) {
                            is TerminalFeed.Snapshot -> synchronized(snapshot) { snapshot.append(feed.bytes.decodeToString(0, 8)) }
                            is TerminalFeed.Output -> synchronized(output) { output.append(feed.bytes.decodeToString()) }
                        }
                    }
                }
                withTimeout(10_000) {
                    while (synchronized(snapshot) { snapshot.isEmpty() }) delay(50)
                }
                assertTrue("stream did not start with GHOSTSNP: $snapshot", snapshot.toString().startsWith("GHOSTSNP"))

                val token = "stream-ok-" + System.nanoTime()
                control.call(
                    "com.superlogical.terminal.write",
                    JSONObject().put("session_id", sessionId).put("block_id", block)
                        .put("args", JSONObject().put("data", "printf '$token\\n'\n")),
                )
                withTimeout(10_000) {
                    while (!synchronized(output) { output.contains(token) }) delay(50)
                }
            } finally {
                sessionId?.let {
                    runCatching {
                        control.call("session.attach", JSONObject().put("session_id", it))
                        control.call("session.destroy", JSONObject().put("session_id", it))
                    }
                }
                control.close()
                scope.cancel()
            }
        }
    }

    private fun portOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
    } catch (_: Exception) {
        false
    }
}

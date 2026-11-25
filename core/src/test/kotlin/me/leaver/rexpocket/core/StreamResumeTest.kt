package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pane's stream that had to start over (Rex restarted, the phone was away past the minute Rex
 * keeps a stream, or Rex said 1013) is a new stream: a RESUME counts its bytes alone. Counting the
 * old stream's too, every later network change was refused and the pane was drawn again from a new
 * snapshot, with a ticket and a round trip more each time.
 */
class StreamResumeTest {
    @Test
    fun aStreamStartedOverResumesCountingItsOwnBytes() = runBlocking {
        ScriptedRex().use { rex ->
            val control = RexClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                control.connect(rex.endpoint)
                control.call("session.bootstrap", JSONObject().put("event_scope", "session"))
                val sid = control.call(
                    "session.create",
                    JSONObject().put("label", "resume").put(
                        "initial_windows",
                        JSONArray().put(JSONObject().put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell")))),
                    ),
                ).getString("session_id")
                val block = parseView(control.call("session.attach", JSONObject().put("session_id", sid)).getJSONObject("view")).focusedBlock()!!.id

                val tickets = AtomicInteger()
                val traced = CopyOnWriteArrayList<String>()
                val stream = TerminalStream(rex.endpoint, { RexClient.defaultHttp() }, { traced += it }) {
                    tickets.incrementAndGet()
                    control.call(
                        "data.connect",
                        JSONObject().put("session_id", sid).put("block_id", block)
                            .put("data_ref_id", "terminal").put("subprotocol", TerminalStream.PROTOCOL),
                    ).getString("ticket")
                }
                val snapshots = AtomicInteger()
                val prompts = AtomicInteger()
                scope.launch {
                    stream.feeds().collect { feed ->
                        when (feed) {
                            is TerminalFeed.Snapshot -> snapshots.incrementAndGet()
                            is TerminalFeed.Output -> if ("$ " in feed.bytes.decodeToString()) prompts.incrementAndGet()
                        }
                    }
                }
                withTimeout(10_000) { while (prompts.get() < 1) delay(20) }

                // Rex cannot keep the stream: it starts over, with a new ticket and a new snapshot.
                rex.requireReconnect(block)
                withTimeout(10_000) { while (prompts.get() < 2) delay(20) }
                assertEquals("snapshots after starting over", 2, snapshots.get())

                // The network changes: the new stream resumes where it is, with nothing drawn again.
                stream.reconnectNow()
                delay(1_500)
                assertEquals("RESUMEs Rex refused: " + traced.joinToString(" | "), 0, rex.resumesRefused.get())
                assertEquals("snapshots after a resume", 2, snapshots.get())
                assertEquals("tickets asked for", 2, tickets.get())
                stream.close()
            } finally {
                control.close()
                scope.cancel()
            }
        }
    }
}

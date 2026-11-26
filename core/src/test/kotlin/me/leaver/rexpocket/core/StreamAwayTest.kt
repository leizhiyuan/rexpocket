package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A pane's stream with the app away from the screen: after the grace it is let go - no socket, so
 * nothing is downloaded or fed for nobody - and taken up on return: resumed while Rex still holds
 * it, started over at once (one ticket, no refused RESUME) once Rex has let it go too.
 */
class StreamAwayTest {
    private class Pane(val rex: ScriptedRex, val control: RexClient, val sid: String, val block: String, val tickets: AtomicInteger)

    private suspend fun pane(rex: ScriptedRex): Pane {
        val control = RexClient()
        control.connect(rex.endpoint)
        control.call("session.bootstrap", JSONObject().put("event_scope", "session"))
        val sid = control.call(
            "session.create",
            JSONObject().put("label", "away").put(
                "initial_windows",
                JSONArray().put(JSONObject().put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell")))),
            ),
        ).getString("session_id")
        val block = parseView(control.call("session.attach", JSONObject().put("session_id", sid)).getJSONObject("view")).focusedBlock()!!.id
        return Pane(rex, control, sid, block, AtomicInteger())
    }

    private fun stream(p: Pane, visible: MutableStateFlow<Boolean>, traced: MutableList<String>, resumeWindowMs: Long?) =
        TerminalStream({ p.rex.endpoint }, { RexClient.defaultHttp() }, { traced += it }, visible, awayGraceMs = 300, resumeWindowMs = resumeWindowMs) {
            p.tickets.incrementAndGet()
            p.control.call(
                "data.connect",
                JSONObject().put("session_id", p.sid).put("block_id", p.block)
                    .put("data_ref_id", "terminal").put("subprotocol", TerminalStream.PROTOCOL),
            ).getString("ticket")
        }

    private fun run(resumeWindowMs: Long?, awayMs: Long, check: (snapshots: Int, tickets: Int, refused: Int, output: String) -> Unit) = runBlocking {
        ScriptedRex().use { rex ->
            val p = pane(rex)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val visible = MutableStateFlow(true)
                val traced = CopyOnWriteArrayList<String>()
                val s = stream(p, visible, traced, resumeWindowMs)
                val snapshots = AtomicInteger()
                val out = StringBuffer()
                scope.launch {
                    s.feeds().collect { feed ->
                        when (feed) {
                            is TerminalFeed.Snapshot -> snapshots.incrementAndGet()
                            is TerminalFeed.Output -> out.append(feed.bytes.decodeToString())
                        }
                    }
                }
                withTimeout(10_000) { while (rex.openStreams(p.block) == 0 || !out.endsWith("$ ")) delay(20) }

                // A short trip away keeps the stream.
                visible.value = false
                delay(100)
                visible.value = true
                delay(400)
                assertEquals("a short trip away", 1, rex.openStreams(p.block))

                // Away past the grace: let go, and nothing more is read.
                visible.value = false
                withTimeout(5_000) { while (rex.openStreams(p.block) != 0) delay(20) }
                delay(awayMs)
                visible.value = true
                withTimeout(10_000) { while (rex.openStreams(p.block) == 0) delay(20) }
                rex.output(p.block, "back\r\n".toByteArray())
                withTimeout(10_000) { while (!out.endsWith("back\r\n")) delay(20) }
                check(snapshots.get(), p.tickets.get(), rex.resumesRefused.get(), out.toString())
                s.close()
            } finally {
                p.control.close()
                scope.cancel()
            }
        }
    }

    /** Within the minute ScriptedRex's WELCOME says it keeps a stream, as Rex's does. */
    @Test
    fun backWithinRexsMinuteTheStreamResumes() = run(resumeWindowMs = null, awayMs = 200) { snapshots, tickets, refused, _ ->
        assertEquals("snapshots", 1, snapshots)
        assertEquals("tickets", 1, tickets)
        assertEquals("refused", 0, refused)
    }

    @Test
    fun backAfterItTheStreamStartsOverWithoutARefusedResume() = run(resumeWindowMs = 100, awayMs = 200) { snapshots, tickets, refused, _ ->
        assertEquals("snapshots", 2, snapshots)
        assertEquals("tickets", 2, tickets)
        assertEquals("refused", 0, refused)
    }
}

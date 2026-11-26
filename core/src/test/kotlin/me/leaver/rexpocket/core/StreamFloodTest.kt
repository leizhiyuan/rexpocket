package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A program that prints faster than the phone draws (`cat` of a big log, `yes`): its output comes
 * through whole and in order, joined into fewer writes, and the stream waits for the screen
 * instead of holding all of it in memory; a resume afterwards still counts its bytes exactly.
 */
class StreamFloodTest {
    @Test
    fun aFloodComesThroughInOrderJoinedAndResumesExactly() = runBlocking {
        ScriptedRex().use { rex ->
            val control = RexClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                control.connect(rex.endpoint)
                control.call("session.bootstrap", JSONObject().put("event_scope", "session"))
                val sid = control.call(
                    "session.create",
                    JSONObject().put("label", "flood").put(
                        "initial_windows",
                        JSONArray().put(JSONObject().put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell")))),
                    ),
                ).getString("session_id")
                val block = parseView(control.call("session.attach", JSONObject().put("session_id", sid)).getJSONObject("view")).focusedBlock()!!.id
                val traced = CopyOnWriteArrayList<String>()
                val stream = TerminalStream(rex.endpoint, { RexClient.defaultHttp() }, { traced += it }) {
                    control.call(
                        "data.connect",
                        JSONObject().put("session_id", sid).put("block_id", block)
                            .put("data_ref_id", "terminal").put("subprotocol", TerminalStream.PROTOCOL),
                    ).getString("ticket")
                }
                val out = ByteArrayOutputStream()
                val writes = AtomicInteger()
                val snapshots = AtomicInteger()
                scope.launch {
                    stream.feeds().coalesced().collect { feed ->
                        when (feed) {
                            is TerminalFeed.Snapshot -> snapshots.incrementAndGet()
                            is TerminalFeed.Output -> {
                                synchronized(out) { out.write(feed.bytes) }
                                writes.incrementAndGet()
                                // A screen slower than the network.
                                delay(2)
                            }
                        }
                    }
                }
                withTimeout(10_000) { while (synchronized(out) { out.size() } < 2) delay(20) }

                val messages = 3_000
                val expected = ByteArrayOutputStream().apply { write("$ ".toByteArray()) }
                for (i in 0 until messages) {
                    val line = ("%06d ".format(i) + "x".repeat(1_000) + "\r\n").toByteArray()
                    expected.write(line)
                    rex.output(block, line)
                }
                withTimeout(60_000) { while (synchronized(out) { out.size() } < expected.size()) delay(20) }
                assertArrayEquals(expected.toByteArray(), synchronized(out) { out.toByteArray() })
                assertTrue("writes for $messages messages: ${writes.get()}", writes.get() < messages / 2)

                // Caught up, the network changes: the stream resumes where it is, nothing drawn again.
                stream.reconnectNow()
                delay(1_500)
                rex.output(block, "after\r\n".toByteArray())
                withTimeout(10_000) { while (!synchronized(out) { out.toString() }.endsWith("after\r\n")) delay(20) }
                assertEquals("RESUMEs Rex refused: " + traced.joinToString(" | "), 0, rex.resumesRefused.get())
                assertEquals(1, snapshots.get())
                stream.close()
            } finally {
                control.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun coalescingKeepsOrderAndTheBound() = runBlocking {
        fun out(s: String) = TerminalFeed.Output(s.toByteArray())
        val feeds = listOf(out("a"), out("b"), TerminalFeed.Snapshot("S".toByteArray()), out("c"), out("d"), out("e".repeat(10)), out("f"))
        // Everything is buffered before the first is taken, so as much as the bound allows is joined.
        val got = flow { feeds.forEach { emit(it) } }.coalesced(maxBytes = 8).toList()
        val shown = got.map { if (it is TerminalFeed.Snapshot) "[S]" else (it as TerminalFeed.Output).bytes.decodeToString() }
        assertEquals(listOf("ab", "[S]", "cd", "e".repeat(10), "f"), shown)
    }

    @Test
    fun aPieceWithNothingOfRexInItIsHandedOnAsItCame() {
        val decoder = StreamDecoder(7uL)
        decoder.feed("GHOSTSNP".toByteArray().copyOf(7)) // not a snapshot after all, once the next byte comes
        decoder.feed("x".toByteArray())
        val piece = "plain output\r\n".toByteArray()
        assertSame(piece, (decoder.feed(piece).single() as TerminalFeed.Output).bytes)
    }

    @Test
    fun anyWayOfCuttingTheStreamGivesTheSameOutput() {
        val nonce = 99uL
        val rexMessage = "\u001b_rex1;$nonce;{\"type\":\"resize\"}\u001b\\"
        val text = buildString {
            repeat(200) { i ->
                append("line $i \u001b[1mbold\u001b[0m ")
                if (i % 7 == 0) append(rexMessage)
                if (i % 11 == 0) append("\u001b_Gi=1;AAAA\u001b\\")
                if (i % 13 == 0) append("\u001b_rex1;${nonce + 1u};{}\u001b\\")
                append("\r\n")
            }
        }
        val stream = ("not a snapshot ").toByteArray() + text.toByteArray()
        val want = String(stream).replace(rexMessage, "")
        val random = java.util.Random(4)
        repeat(50) {
            val decoder = StreamDecoder(nonce)
            val got = ByteArrayOutputStream()
            var at = 0
            while (at < stream.size) {
                val n = minOf(stream.size - at, 1 + random.nextInt(64))
                for (feed in decoder.feed(stream.copyOfRange(at, at + n))) got.write((feed as TerminalFeed.Output).bytes)
                at += n
            }
            assertEquals(want, got.toString())
        }
    }

    /** The ticket is asked for while the data socket opens: Rex has the socket before the ticket is answered. */
    @Test
    fun theTicketIsAskedForWhileTheSocketOpens() = runBlocking {
        ScriptedRex().use { rex ->
            val control = RexClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                control.connect(rex.endpoint)
                control.call("session.bootstrap", JSONObject().put("event_scope", "session"))
                val sid = control.call(
                    "session.create",
                    JSONObject().put("label", "ticket").put(
                        "initial_windows",
                        JSONArray().put(JSONObject().put("layout", JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell")))),
                    ),
                ).getString("session_id")
                val block = parseView(control.call("session.attach", JSONObject().put("session_id", sid)).getJSONObject("view")).focusedBlock()!!.id
                var openBeforeTicket = -1
                val stream = TerminalStream(rex.endpoint, { RexClient.defaultHttp() }) {
                    // A slow control connection: by the time the ticket comes, the socket is open.
                    withTimeout(5_000) { while (rex.dataSocketsOpened.get() == 0) delay(10) }
                    openBeforeTicket = rex.dataSocketsOpened.get()
                    control.call(
                        "data.connect",
                        JSONObject().put("session_id", sid).put("block_id", block)
                            .put("data_ref_id", "terminal").put("subprotocol", TerminalStream.PROTOCOL),
                    ).getString("ticket")
                }
                val snapshots = AtomicInteger()
                scope.launch { stream.feeds().collect { if (it is TerminalFeed.Snapshot) snapshots.incrementAndGet() } }
                withTimeout(10_000) { while (snapshots.get() == 0) delay(20) }
                assertEquals(1, openBeforeTicket)
                stream.close()
            } finally {
                control.close()
                scope.cancel()
            }
        }
    }
}

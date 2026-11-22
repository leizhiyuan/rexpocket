package me.leaver.rexpocket.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ScriptedRex sends what a real Rex sends. One script - a phone and a desk making, owning, closing
 * and destroying, programs reporting and exiting, a stream opened and ended, a resume - runs against
 * a Rex, and every message the phone receives is written down as its shape: the method or event,
 * each field's name and kind (ids, times and numbers taken out), the error codes and messages.
 *
 * The shapes recorded from Rex 0.1.0-preview.22 (API revision 19, spoken to at the app's revision 7)
 * are in `rex-shapes/preview.22.txt`. [theFakeSendsWhatRexSends] holds ScriptedRex to them on every
 * run. [realRexStillSendsTheRecordedShapes] runs the same script against a real Rex where one
 * listens (REX_TEST_HOST, as the other real-Rex tests), so a new Rex that changes a shape fails
 * there first; `REX_RECORD_SHAPES=1` writes the file anew from it.
 */
class RexShapeTest {
    @Test
    fun theFakeSendsWhatRexSends() {
        ScriptedRex().use { rex ->
            val got = record(rex.endpoint, FakePrograms(rex))
            File(System.getProperty("user.dir"), "build/scripted-rex-shapes.txt").writeText(got.joinToString("\n", postfix = "\n"))
            assertShapes(expected(), got)
        }
    }

    @Test
    fun realRexStillSendsTheRecordedShapes() {
        val real = RealRex()
        assumeTrue("rex plaintext listener on ${real.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", real.reachable)
        val got = record(real.endpoint, null)
        if (System.getenv("REX_RECORD_SHAPES") == "1") {
            File(System.getProperty("user.dir"), "src/test/resources/$FIXTURE").writeText(HEADER + got.joinToString("\n", postfix = "\n"))
        }
        assertShapes(expected(), got)
    }

    private fun expected(): List<String> =
        javaClass.classLoader.getResource(FIXTURE)!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("# ") }

    private fun assertShapes(expected: List<String>, got: List<String>) {
        // The whole list compared, so a failure shows every difference at once.
        assertEquals(expected.joinToString("\n"), got.joinToString("\n"))
    }

    /** How a test makes the program in a pane report or exit: OSC 7501 typed into a real shell, or ScriptedRex told. */
    private interface Programs {
        fun report(probe: Probe, sid: String, block: String, body: String, state: String?, id: String = "", app: String? = null, msg: String? = null)
        fun exit(probe: Probe, sid: String, block: String, code: Int)
        fun copy(probe: Probe, sid: String, block: String, text: String)
    }

    private class FakePrograms(val rex: ScriptedRex) : Programs {
        // The text is typed as into a real shell, which ScriptedRex takes and ignores; then its program reports.
        override fun report(probe: Probe, sid: String, block: String, body: String, state: String?, id: String, app: String?, msg: String?) {
            ShellPrograms().report(probe, sid, block, body, state, id, app, msg)
            rex.report(block, state, app = app, msg = msg, id = id)
        }

        override fun exit(probe: Probe, sid: String, block: String, code: Int) {
            ShellPrograms().exit(probe, sid, block, code)
            rex.exit(block, code)
        }

        override fun copy(probe: Probe, sid: String, block: String, text: String) {
            ShellPrograms().copy(probe, sid, block, text)
            rex.copy(block, text)
        }
    }

    private class ShellPrograms : Programs {
        override fun report(probe: Probe, sid: String, block: String, body: String, state: String?, id: String, app: String?, msg: String?) {
            probe.call("com.superlogical.terminal.write", pane(sid, block).put("args", JSONObject().put("data", "printf '\\033]7501;$body\\033\\\\'\n")))
        }

        override fun exit(probe: Probe, sid: String, block: String, code: Int) {
            probe.call("com.superlogical.terminal.write", pane(sid, block).put("args", JSONObject().put("data", "exit $code\n")))
        }

        override fun copy(probe: Probe, sid: String, block: String, text: String) {
            probe.call("com.superlogical.terminal.write", pane(sid, block).put("args", JSONObject().put("data", "printf '\\033]52;c;%s\\a' ${b64(text)}\n")))
        }
    }

    private fun record(endpoint: Endpoint, fake: Programs?): List<String> {
        val programs = fake ?: ShellPrograms()
        val quiet = if (fake == null) 700L else 150L
        val out = ArrayList<String>()
        val phone = Probe(endpoint)
        val desk = Probe(endpoint)
        var sid: String? = null
        try {
            /** One step and what the phone received meanwhile; with [deskToo], what the desk received as well. */
            fun step(name: String, deskToo: Boolean = false, unordered: Boolean = false, body: () -> Unit) {
                desk.take()
                body()
                Thread.sleep(quiet)
                out += "## $name"
                // Where Rex's order is a race (a response against the event the same call caused, on
                // a real server), the lines are compared as a set.
                out += phone.take().let { if (unordered) it.sorted() else it }
                if (deskToo) out += desk.take().map { "desk: $it" }.let { if (unordered) it.sorted() else it }
            }
            step("phone hello") { phone.hello() }
            step("phone bootstrap, nothing on the server") { phone.call("session.bootstrap", JSONObject().put("event_scope", "all")) }
            desk.hello()
            lateinit var first: String
            lateinit var firstTab: String
            step("desk creates a session with one tab") {
                val made = desk.call(
                    "session.create",
                    JSONObject().put("label", "rexpocket-shapes").put("initial_windows", JSONArray().put(JSONObject().put("layout", SHELL))),
                ).getJSONObject("payload")
                sid = made.getString("session_id")
                first = made.getJSONArray("initial_windows").getJSONObject(0).getJSONArray("block_ids").getString(0)
                firstTab = made.getJSONArray("initial_windows").getJSONObject(0).getString("window_id")
                desk.call("session.attach", JSONObject().put("session_id", sid))
            }
            val s = sid!!
            // A shell reports what its programs say only once it reads its input; give it a moment.
            if (fake == null) Thread.sleep(1_500)
            step("phone reads a pane of a session it has not attached") {
                phone.call("com.superlogical.terminal.program_status", pane(s, first))
                phone.call("com.superlogical.terminal.size", pane(s, first))
                phone.call("session.own_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(first)).put("mode", "take"))
                phone.call("data.connect", ticket(s, first))
            }
            step("phone attaches") { phone.call("session.attach", JSONObject().put("session_id", s)) }
            lateinit var second: String
            step("desk makes a tab") {
                second = desk.call("session.new_window", JSONObject().put("session_id", s).put("focus", false).put("layout", SHELL))
                    .getJSONObject("payload").getJSONArray("block_ids").getString(0)
            }
            lateinit var split: String
            step("desk splits the first pane") {
                split = desk.call(
                    "session.new_split",
                    JSONObject().put("session_id", s).put("anchor_id", first).put("direction", "vertical").put("side", "after").put("ratio", 0.5).put("focus", false).put("layout", SHELL),
                ).getJSONObject("payload").getJSONArray("block_ids").getString(0)
            }
            step("phone views the session") { phone.call("session.view", JSONObject().put("session_id", s)) }
            step("phone takes the first pane") {
                phone.call("session.own_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(first)).put("mode", "take"))
            }
            step("desk takes it back") {
                desk.call("session.own_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(first)).put("mode", "take"))
            }
            step("phone disowns a pane the desk holds") {
                phone.call("session.disown_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(first)))
            }
            lateinit var stream: Probe.Stream
            step("phone opens the second pane's stream") {
                val t = phone.call("data.connect", ticket(s, second)).getJSONObject("payload").getString("ticket")
                stream = phone.stream(t)
                stream.awaitResize()
            }
            step("a second stream of the same pane") {
                val t = phone.call("data.connect", ticket(s, second)).getJSONObject("payload").getString("ticket")
                phone.stream(t).awaitEnd()
            }
            step("phone takes the split pane, then its network drops", deskToo = true) {
                phone.call("session.own_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(split)).put("mode", "take"))
                phone.take()
                phone.drop()
            }
            step("phone resumes with its reconnect token", deskToo = true) { phone.hello(resume = true) }
            if (fake == null) Thread.sleep(1_000)
            step("a program reports done, then a failed child record") {
                programs.report(phone, s, split, "state=done:app=probe:msg=" + b64("all good"), "done", app = "probe", msg = "all good")
                Thread.sleep(quiet)
                programs.report(phone, s, split, "state=error:id=build/test:msg=" + b64("3 failed"), "error", id = "build/test", msg = "3 failed")
                Thread.sleep(quiet)
                phone.call("com.superlogical.terminal.program_status", pane(s, split))
            }
            step("the program clears its records") { programs.report(phone, s, split, "state=clear", null) }
            step("desk closes the tab whose stream the phone has open") {
                desk.call("session.close_window", JSONObject().put("session_id", s).put("window_id", tabOf(desk, s, second)))
                stream.awaitEnd()
            }
            step("phone calls on the pane and tab that are gone") {
                phone.call("block.close", pane(s, second))
                phone.call("com.superlogical.terminal.write", pane(s, second).put("args", JSONObject().put("data", "x")))
                phone.call("com.superlogical.terminal.program_status", pane(s, second))
                phone.call("session.own_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(second)).put("mode", "take"))
                phone.call("session.disown_block", JSONObject().put("session_id", s).put("block_ids", JSONArray().put(second)))
                phone.call("session.close_window", JSONObject().put("session_id", s).put("window_id", "window:00000000-0000-7000-8000-000000000000"))
                phone.call("session.set_block_label", pane(s, second).put("label", "x"))
                phone.call("data.connect", ticket(s, second))
            }
            step("a program copies to the clipboard with OSC 52") { programs.copy(phone, s, split, "hi") }
            step("phone reads the copy back") {
                phone.call("com.superlogical.terminal.clipboard_read", pane(s, split).put("args", JSONObject()))
            }
            step("phone formats the pane as text, whole and in parts") {
                val text = JSONObject().put("format", "text").put("trim", true).put("unwrap", true)
                phone.call("block.method", pane(s, split).put("method", "format").put("args", text))
                val part = phone.call("block.method", pane(s, split).put("method", "format").put("args", JSONObject(text.toString()).put("max_bytes", 4)))
                    .getJSONObject("payload")
                phone.call(
                    "block.method",
                    pane(s, split).put("method", "blob_read")
                        .put("args", JSONObject().put("id", part.getLong("id")).put("offset", part.getString("content").toByteArray().size)),
                )
            }
            step("phone clears the pane, then resets it") {
                phone.call("block.method", pane(s, split).put("method", "clear"))
                phone.call("block.method", pane(s, split).put("method", "reset"))
            }
            step("a program working in the split pane exits with 3") {
                programs.report(phone, s, split, "state=working:msg=" + b64("busy"), "working", msg = "busy")
                Thread.sleep(quiet)
                programs.exit(phone, s, split, 3)
            }
            lateinit var lone: String
            step("desk makes another tab") {
                lone = desk.call("session.new_window", JSONObject().put("session_id", s).put("focus", false).put("layout", SHELL))
                    .getJSONObject("payload").getJSONArray("block_ids").getString(0)
            }
            if (fake == null) Thread.sleep(1_500)
            step("the program in a tab of one pane exits with 0") { programs.exit(phone, s, lone, 0) }
            step("desk closes the last pane of the last tab") { desk.call("block.close", pane(s, first)) }
            step("phone views the session with no tabs") { phone.call("session.view", JSONObject().put("session_id", s)) }
            step("phone bootstraps again, with a session there") {
                phone.call("session.bootstrap", JSONObject().put("event_scope", "all").put("preferred_session_id", s))
            }
            lateinit var mine: String
            lateinit var minePane: String
            step("phone makes a session of its own", unordered = true) {
                val made = phone.call(
                    "session.create",
                    JSONObject().put("label", "rexpocket-shapes-2").put("initial_windows", JSONArray().put(JSONObject().put("layout", SHELL))),
                ).getJSONObject("payload")
                mine = made.getString("session_id")
                minePane = made.getJSONArray("initial_windows").getJSONObject(0).getJSONArray("block_ids").getString(0)
                desk.call("session.attach", JSONObject().put("session_id", mine))
            }
            lateinit var last: Probe.Stream
            step("phone holds and streams its pane") {
                phone.call("session.attach", JSONObject().put("session_id", mine))
                phone.call("session.own_block", JSONObject().put("session_id", mine).put("block_ids", JSONArray().put(minePane)).put("mode", "take"))
                last = phone.stream(phone.call("data.connect", ticket(mine, minePane)).getJSONObject("payload").getString("ticket"))
                last.awaitResize()
            }
            step("phone closes its connection with a close frame", deskToo = true) {
                phone.close(keepStreams = true)
                last.awaitEnd()
            }
            step("desk destroys the sessions", deskToo = true, unordered = true) {
                desk.call("session.destroy", JSONObject().put("session_id", mine))
                desk.call("session.destroy", JSONObject().put("session_id", s))
                sid = null
            }
        } finally {
            sid?.let { runCatching { desk.call("session.destroy", JSONObject().put("session_id", it)) } }
            phone.close()
            desk.close()
        }
        return out
    }

    private fun tabOf(desk: Probe, sid: String, block: String): String {
        val view = desk.call("session.view", JSONObject().put("session_id", sid)).getJSONObject("payload")
        val windows = view.getJSONArray("windows")
        for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            val layers = w.getJSONArray("layers")
            for (l in 0 until layers.length()) {
                val blocks = layers.getJSONObject(l).getJSONArray("blocks")
                for (b in 0 until blocks.length()) if (blocks.getJSONObject(b).getString("block_id") == block) return w.getString("window_id")
            }
        }
        error("no tab holds $block")
    }

    /** A bare control connection that writes down the shape of everything it receives. */
    private class Probe(private val endpoint: Endpoint) {
        private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        private var socket: WebSocket? = null
        private val ids = AtomicInteger()
        private val waiting = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
        private val methods = ConcurrentHashMap<String, String>()
        private val seen = CopyOnWriteArrayList<String>()

        /** What the streams saw, kept apart: when a stream ends against the control events is a race. */
        private val streamSeen = CopyOnWriteArrayList<String>()
        private var welcome = CompletableFuture<JSONObject>()
        private var token: String? = null

        fun hello(resume: Boolean = false) {
            welcome = CompletableFuture()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    val hello = JSONObject().put("type", "hello").put("api_revision", 7)
                        .put("client", JSONObject().put("kind", "app").put("name", "rexpocket-shapes"))
                    if (resume) hello.put("reconnect_token", token)
                    webSocket.send(hello.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = JSONObject(text)
                    when (m.optString("type")) {
                        "welcome" -> {
                            seen += "welcome " + shape(m)
                            token = m.optJSONObject("client")?.optString("reconnect_token")
                            welcome.complete(m)
                        }
                        "response" -> {
                            val method = methods.remove(m.getString("id")) ?: "?"
                            val err = m.optJSONObject("error")
                            seen += if (err != null) {
                                "response $method error ${err.optString("code")} ${err.optInt("status")} \"${scrub(err.optString("message"))}\""
                            } else {
                                "response $method " + shape(m.opt("payload"))
                            }
                            waiting.remove(m.getString("id"))?.complete(m)
                        }
                        "event" -> describe(m.getJSONObject("payload"))?.let { seen += "event $it" }
                    }
                }
            }
            socket = http.newWebSocket(
                Request.Builder().url(endpoint.ws("/api/control")).header("Sec-WebSocket-Protocol", "rex.control.v1").build(),
                listener,
            )
            welcome.get(10, TimeUnit.SECONDS)
        }

        fun call(method: String, payload: JSONObject): JSONObject {
            val id = "r" + ids.incrementAndGet()
            val answer = CompletableFuture<JSONObject>()
            waiting[id] = answer
            methods[id] = method
            socket!!.send(JSONObject().put("type", "request").put("id", id).put("method", method).put("payload", payload).toString())
            return answer.get(15, TimeUnit.SECONDS)
        }

        /** Everything received since the last take. */
        fun take(): List<String> {
            val control = seen.toList().also { seen.clear() }
            val streams = streamSeen.toList().also { streamSeen.clear() }
            return control + streams
        }

        fun drop() {
            socket?.cancel()
            Thread.sleep(300)
        }

        fun close(keepStreams: Boolean = false) {
            socket?.close(1000, "bye")
            if (!keepStreams) http.dispatcher.executorService.shutdown()
        }

        inner class Stream(ticket: String) {
            val bytes = java.io.ByteArrayOutputStream()
            val end = CompletableFuture<String>()
            private val resized = CompletableFuture<Unit>()
            private var nonce = ""

            init {
                http.newWebSocket(
                    Request.Builder().url(endpoint.ws("/api/data")).header("Sec-WebSocket-Protocol", TerminalStream.PROTOCOL).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send((byteArrayOf(1) + Base64.getUrlDecoder().decode(ticket)).toByteString())
                        }

                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                            val b = bytes.toByteArray()
                            if (nonce.isEmpty() && b.size == 32 && b[0] == 0x81.toByte()) {
                                var n = 0uL
                                for (i in 0 until 8) n = n or (b[24 + i].toUByte().toULong() shl (8 * i))
                                nonce = n.toString()
                                val keep = (0 until 4).sumOf { (b[20 + it].toInt() and 0xff) shl (8 * it) }
                                streamSeen += "stream welcome 32 bytes, kept $keep ms"
                                return
                            }
                            if (b.size == 2 && b[0] == 0x82.toByte()) {
                                streamSeen += "stream reject 0x%02x".format(b[1])
                                return
                            }
                            synchronized(bytes) { this@Stream.bytes.write(b) }
                            val all = this@Stream.bytes.toByteArray()
                            if (!resized.isDone && String(all, Charsets.ISO_8859_1).contains("\u001b_rex1;$nonce;{\"type\":\"resize\"")) {
                                streamSeen += "stream starts with ${String(all, 0, 8, Charsets.ISO_8859_1)}, then a rex1 resize"
                                resized.complete(Unit)
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            streamSeen += "stream closed $code \"$reason\""
                            webSocket.close(code, reason)
                            end.complete("$code")
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                            end.complete("failure")
                        }
                    },
                )
            }

            fun awaitResize() = resized.get(10, TimeUnit.SECONDS)
            fun awaitEnd() {
                end.get(10, TimeUnit.SECONDS)
            }
        }

        fun stream(ticket: String) = Stream(ticket)
    }

    companion object {
        const val FIXTURE = "rex-shapes/preview.22.txt"

        const val HEADER = "# What the phone receives from Rex 0.1.0-preview.22 (api revision 19; the phone speaks 7) in\n" +
            "# RexShapeTest's script. Recorded with REX_RECORD_SHAPES=1 against that Rex; see docs/design/rex-parity.md.\n"

        val SHELL: JSONObject get() = JSONObject().put("block", JSONObject().put("flavor", "com.superlogical.terminal.shell"))

        fun pane(sid: String, block: String): JSONObject = JSONObject().put("session_id", sid).put("block_id", block)

        fun ticket(sid: String, block: String): JSONObject =
            pane(sid, block).put("data_ref_id", "terminal").put("subprotocol", TerminalStream.PROTOCOL)

        fun b64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

        /** Events that say what a real shell happens to do - its title, process, size - and the other clients'. */
        private val NOISE = setOf(
            "client_joined", "client_left", "client_changed", "client_connected", "client_disconnected", "client_connection_changed",
        )
        private val BLOCK_NOISE = setOf("title_changed", "process_changed", "pwd_changed", "size_changed", "ports_changed", "bell")

        /** Fields left out of the shapes: what a phone never reads, and the fake does not model. */
        private val IGNORED = setOf("host_id", "bounds", "layout", "split_ids", "server_status", "features", "carriers")

        fun describe(event: JSONObject): String? {
            val type = event.optString("type")
            if (type in NOISE) return null
            if (type != "session_event") return "$type " + shape(event.opt("payload"))
            val inner = event.getJSONObject("payload").getJSONObject("event")
            val name = inner.optString("type")
            if (name in NOISE) return null
            if (name != "block_event") return "$name " + shape(inner.opt("payload"))
            val block = inner.getJSONObject("payload")
            val blockName = block.optString("name")
            if (blockName in BLOCK_NOISE) return null
            return "block_event $blockName " + shape(block.opt("payload"))
        }

        /** A value's shape: objects by their sorted fields, arrays by their items' shapes, strings by what they hold. */
        fun shape(v: Any?): String = when (v) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> v.keys().asSequence().filter { it !in IGNORED }.sorted().joinToString(",", "{", "}") { "$it:" + shape(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { shape(v.get(it)) }.distinct().joinToString("|", "[", "]")
            is String -> when {
                ID.matches(v) -> "<${v.substringBefore(':')}>"
                TIME.matches(v) -> "<time>"
                v.isEmpty() -> "\"\""
                v in WORDS -> "\"$v\""
                else -> "str"
            }
            is Boolean -> v.toString()
            is Number -> "num"
            else -> v.javaClass.simpleName
        }

        /** Strings whose value is part of the protocol, so the shape keeps them. */
        private val WORDS = setOf(
            "report", "clear", "child_exited", "prompt", "foreground", "reset", "evicted",
            "take", "request", "disown", "create", "lost", "restore", "disconnect",
            "working", "blocked", "done", "error", "idle", "tiled", "rex.control.v1", "exit status 3", "build/test",
        )
        private val ID = Regex("^(session|window|block|client|layer|split):[0-9a-f-]+$")
        private val TIME = Regex("^\\d{4}-\\d\\d-\\d\\dT[0-9:.]+Z$")

        fun scrub(message: String) = message.replace(Regex("(session|window|block|client|layer|split):[0-9a-f-]+"), "<$1>")
    }
}

package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReconnectTest {
    /** A host that takes the TCP connection and never answers must not end the retries. */
    @Test
    fun aConnectThatNeverAnswersKeepsRetrying() = runBlocking {
        ServerSocket(0).use { server ->
            val held = ArrayList<Socket>()
            thread(isDaemon = true) { runCatching { while (true) held += server.accept() } }
            val attempts = AtomicInteger()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(Endpoint("127.0.0.1", server.localPort)) {
                attempts.incrementAndGet()
                RexClient(connectTimeoutMs = 200)
            }
            try {
                val job = session.start(scope)
                withTimeout(5_000) { while (attempts.get() < 3) delay(50) }
                assertTrue("the retry loop ended", job.isActive)
                assertEquals(Trouble.TimedOut, session.state.value.problem?.trouble)
            } finally {
                session.stop()
                scope.cancel()
                held.forEach { it.close() }
            }
        }
    }

    /** "Retry now" skips the rest of the backoff instead of waiting up to eight seconds. */
    @Test
    fun retryNowSkipsTheBackoff() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", port)) {
            attempts.incrementAndGet()
            RexClient()
        }
        try {
            session.start(scope)
            // 400 + 800 + 1600 ms of backoff; the next wait is 3.2 s.
            withTimeout(6_000) { while (attempts.get() < 4) delay(20) }
            assertEquals(Trouble.Unreachable, session.state.value.problem?.trouble)
            delay(100)
            session.retryNow()
            withTimeout(1_000) { while (attempts.get() < 5) delay(20) }
        } finally {
            session.stop()
            scope.cancel()
        }
    }
}

/**
 * Coming back from the background: the sockets may have died while the app was away, and the
 * retry loop may be sleeping a long backoff. The person is looking now, so it tries at once.
 */
class ForegroundTest {
    @Test
    fun comingBackSkipsTheBackoff() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", port)) {
            attempts.incrementAndGet()
            RexClient()
        }
        try {
            session.setActive(false)
            session.start(scope)
            withTimeout(6_000) { while (attempts.get() < 4) delay(20) }
            delay(100)
            session.setActive(true)
            withTimeout(1_000) { while (attempts.get() < 5) delay(20) }
        } finally {
            session.stop()
            scope.cancel()
        }
    }

    /**
     * A control socket that looks open but no longer answers - the network under it went away
     * while the app was in the background - is found out within seconds of coming back, not after
     * OkHttp's 30 s ping, and replaced.
     */
    @Test
    fun comingBackReplacesAConnectionThatNoLongerAnswers() = runBlocking {
        val real = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${real.host}:${real.port}", portOpen(real.host, real.port))
        val proxy = StallingProxy(real.host, real.port)
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", proxy.port, plaintext = true)) {
            attempts.incrementAndGet()
            RexClient()
        }
        try {
            session.start(scope)
            withTimeout(10_000) { session.awaitReady() }
            assertEquals(1, attempts.get())
            session.setActive(false)
            // The connection stays open, but nothing gets through any more.
            proxy.stall()
            delay(300)
            proxy.unstall()
            val back = System.nanoTime()
            session.setActive(true)
            withTimeout(8_000) {
                while (attempts.get() < 2 || session.state.value.phase != Phase.Live) delay(50)
            }
            val ms = (System.nanoTime() - back) / 1_000_000
            assertTrue("took ${ms}ms to notice the dead connection", ms < 8_000)
        } finally {
            session.stop()
            scope.cancel()
            proxy.close()
        }
    }
}

/**
 * The pane's stream dies with the network as the control connection does, and looks just as open.
 * Coming back, once the control connection is found dead, the stream is replaced too: otherwise
 * the pane shows nothing new until OkHttp's ping gives up on it, half a minute or more later.
 */
class ForegroundStreamTest {
    @Test
    fun comingBackReplacesAPaneStreamThatNoLongerAnswers() = runBlocking {
        val real = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${real.host}:${real.port}", portOpen(real.host, real.port))
        val proxy = StallingProxy(real.host, real.port)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", proxy.port, plaintext = true))
        var sessionId: String? = null
        val seen = StringBuilder()
        try {
            session.start(scope)
            withTimeout(15_000) { session.awaitReady() }
            session.createShell("rexpocket-test-stream-back-" + System.currentTimeMillis())
            sessionId = session.state.value.activeSessionId
            val block = withTimeout(10_000) {
                var id: String? = null
                while (id == null) {
                    id = session.state.value.view?.focusedBlock()?.id
                    if (id == null) delay(20)
                }
                id
            }
            val watcher = scope.launch {
                session.watchPane(block).collect { feed ->
                    if (feed is TerminalFeed.Output) synchronized(seen) { seen.append(feed.bytes.toString(Charsets.UTF_8)) }
                }
            }
            try {
                // Wait for the stream to carry output, so it is flowing when the network goes.
                session.writeBytesAwaitable("echo ready-\$((1+1))\r".toByteArray()).await()
                withTimeout(10_000) { while (!synchronized(seen) { "ready-2" in seen }) delay(50) }

                session.setActive(false)
                proxy.stall()
                delay(300)
                proxy.unstall()
                session.setActive(true)
                val back = System.nanoTime()
                withTimeout(10_000) { while (session.state.value.phase != Phase.Live) delay(20) }
                withTimeout(10_000) {
                    while (true) {
                        // Typed again until it lands: the first try may meet the old connection.
                        runCatching { session.writeBytesAwaitable("echo back-\$((2+2))\r".toByteArray()).await() }
                        if (synchronized(seen) { "back-4" in seen }) break
                        delay(500)
                    }
                }
                val ms = (System.nanoTime() - back) / 1_000_000
                assertTrue("the pane showed new output ${ms}ms after coming back", ms < 10_000)
            } finally {
                watcher.cancel()
            }
        } finally {
            session.stop()
            scope.cancel()
            sessionId?.let { id ->
                runCatching {
                    val probe = RexClient()
                    probe.connect(real)
                    probe.call("session.attach", org.json.JSONObject().put("session_id", id))
                    probe.call("session.destroy", org.json.JSONObject().put("session_id", id))
                    probe.close()
                }
            }
            proxy.close()
        }
    }
}

/**
 * Rex closes a connection whose first message is not the hello, with 1002 `protocol error`. A call
 * made while the connection is still opening - the screen coming back asks who owns its pane at
 * once - went out ahead of the hello, and every such reconnect failed once before it held.
 */
class HelloFirstTest {
    @Test
    fun aCallMadeWhileConnectingWaitsForTheWelcome() = runBlocking {
        val real = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${real.host}:${real.port}", portOpen(real.host, real.port))
        // A slow link, so the call is made well before the welcome can come back.
        val proxy = DelayingProxy(real.host, real.port, delayMs = 150)
        val client = RexClient()
        try {
            val connecting = launch(Dispatchers.Default) { client.connect(Endpoint("127.0.0.1", proxy.port, plaintext = true)) }
            delay(20)
            val answer = withTimeout(5_000) { client.call("session.list", null) }
            connecting.join()
            assertTrue("no sessions field: $answer", answer.has("sessions"))
        } finally {
            client.close()
            proxy.close()
        }
    }
}

class ResumeTest {
    @Test
    fun aResumedConnectionDoesNotReuseARequestIdStillRunning() = runBlocking {
        val real = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${real.host}:${real.port}", portOpen(real.host, real.port))
        val proxy = StallingProxy(real.host, real.port)
        val attempts = AtomicInteger()
        val ended = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(
            Endpoint("127.0.0.1", proxy.port, plaintext = true),
            trace = { if (it.startsWith("connection ended")) ended += it },
        ) {
            attempts.incrementAndGet()
            RexClient()
        }
        try {
            session.start(scope)
            withTimeout(10_000) { session.awaitReady() }
            // Rex keeps the client while its socket is stuck; the phone gives up on it and resumes.
            proxy.stall()
            delay(200)
            proxy.unstall()
            session.reconnectNow()
            withTimeout(8_000) { while (attempts.get() < 2 || session.state.value.phase != Phase.Live) delay(20) }
            delay(1_000)
            assertTrue("resumed connection was closed: $ended", ended.none { "protocol error" in it })
            assertEquals("connections made", 2, attempts.get())
        } finally {
            session.stop()
            scope.cancel()
            proxy.close()
        }
    }
}

/**
 * Scrolling a full-screen program turns into a stream of wheel reports. Each one waited for Rex's
 * reply before the next went out, so over a tailnet with a 150 ms round trip a fling of 20 reports
 * took three seconds to arrive. Writes leave as they come; Rex answers them in order.
 */
class InputPipelineTest {
    @Test
    fun aBurstOfWritesDoesNotWaitARoundTripEach() = runBlocking {
        val real = Endpoint(
            System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
            plaintext = true,
        )
        assumeTrue("rex on ${real.host}:${real.port}", portOpen(real.host, real.port))
        val proxy = DelayingProxy(real.host, real.port, delayMs = 150)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", proxy.port, plaintext = true))
        var sessionId: String? = null
        try {
            session.start(scope)
            withTimeout(15_000) { session.awaitReady() }
            session.createShell("rexpocket-test-burst-" + System.currentTimeMillis())
            sessionId = session.state.value.activeSessionId
            withTimeout(10_000) { while (session.state.value.view?.focusedBlock() == null) delay(20) }
            val started = System.nanoTime()
            val last = (1..20).map { session.writeBytesAwaitable("\u001b[<65;1;1M".toByteArray()) }.last()
            last.await()
            val ms = (System.nanoTime() - started) / 1_000_000
            // 20 round trips would be 20 x 300 ms here; one or two is what a pipeline costs.
            assertTrue("20 writes took ${ms}ms", ms < 1_500)
        } finally {
            session.stop()
            scope.cancel()
            sessionId?.let { id ->
                runCatching {
                    val probe = RexClient()
                    probe.connect(real)
                    probe.call("session.attach", org.json.JSONObject().put("session_id", id))
                    probe.call("session.destroy", org.json.JSONObject().put("session_id", id))
                    probe.close()
                }
            }
            proxy.close()
        }
    }
}

/** Forwards TCP to a real server, holding each chunk [delayMs] in each direction like a slow link. */
private class DelayingProxy(targetHost: String, targetPort: Int, private val delayMs: Long) {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    init {
        thread(isDaemon = true) {
            while (true) {
                val down = runCatching { server.accept() }.getOrNull() ?: break
                val up = runCatching { Socket(targetHost, targetPort) }.getOrNull() ?: run { down.close(); continue }
                open += down
                open += up
                fun pump(from: Socket, to: Socket) {
                    val queue = java.util.concurrent.LinkedBlockingQueue<Pair<Long, ByteArray>>()
                    thread(isDaemon = true) {
                        val buf = ByteArray(16 * 1024)
                        runCatching {
                            while (true) {
                                val n = from.getInputStream().read(buf)
                                if (n < 0) break
                                queue.put(System.nanoTime() + delayMs * 1_000_000 to buf.copyOf(n))
                            }
                        }
                        queue.put(Long.MIN_VALUE to ByteArray(0))
                    }
                    thread(isDaemon = true) {
                        runCatching {
                            while (true) {
                                val (due, bytes) = queue.take()
                                if (due == Long.MIN_VALUE) break
                                val wait = (due - System.nanoTime()) / 1_000_000
                                if (wait > 0) Thread.sleep(wait)
                                to.getOutputStream().write(bytes)
                            }
                        }
                        runCatching { to.close() }
                    }
                }
                pump(down, up)
                pump(up, down)
            }
        }
    }

    fun close() {
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
    }
}

/** Forwards TCP to a real server; [stall] keeps the existing connections open but passes nothing. */
private class StallingProxy(targetHost: String, targetPort: Int) {
    private val server = ServerSocket(0)
    val port: Int = server.localPort
    private val stalled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    private val live = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var stalling = false

    init {
        thread(isDaemon = true) {
            while (true) {
                val down = runCatching { server.accept() }.getOrNull() ?: break
                val up = runCatching { Socket(targetHost, targetPort) }.getOrNull() ?: run { down.close(); continue }
                live += down
                fun pump(from: Socket, to: Socket) = thread(isDaemon = true) {
                    val buf = ByteArray(16 * 1024)
                    runCatching {
                        while (true) {
                            val n = from.getInputStream().read(buf)
                            if (n < 0) break
                            // A stalled connection drops everything, the way a dead network does.
                            if (down in stalled) continue
                            to.getOutputStream().write(buf, 0, n)
                        }
                    }
                    runCatching { to.close() }
                }
                pump(down, up)
                pump(up, down)
            }
        }
    }

    /** Connections open from now on work; the ones open now stay dead. */
    fun stall() {
        stalling = true
        stalled += live
    }

    fun unstall() {
        stalling = false
    }

    fun close() {
        runCatching { server.close() }
        (live).forEach { runCatching { it.close() } }
    }
}

private fun portOpen(host: String, port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
} catch (_: Exception) {
    false
}

class ProblemTest {
    @Test
    fun aRefusedConnectionIsUnreachable() {
        val e = IllegalStateException("Failed to connect", java.net.ConnectException("refused"))
        assertEquals(Trouble.Unreachable, e.toProblem().trouble)
    }

    /** A port that accepts and closes again, such as adb's forward with nothing behind it. */
    @Test
    fun aHandshakeCutShortIsUnreachable() {
        val e = IllegalStateException("unexpected end of stream", java.io.IOException("x", java.io.EOFException("\n not found")))
        assertEquals(Trouble.Unreachable, e.toProblem().trouble)
    }

    @Test
    fun anUnknownNameIsUnreachable() {
        assertEquals(Trouble.Unreachable, java.net.UnknownHostException("nope").toProblem().trouble)
    }

    @Test
    fun noAnswerIsTimedOut() {
        assertEquals(Trouble.TimedOut, RexTimeoutException("late").toProblem().trouble)
        assertEquals(Trouble.TimedOut, java.net.SocketTimeoutException("late").toProblem().trouble)
    }

    @Test
    fun anErrorFromRexKeepsItsMessage() {
        val p = RexCallException("not_found", 404, "block not found").toProblem()
        assertEquals(Trouble.Rejected, p.trouble)
        assertEquals("block not found", p.detail)
    }

    @Test
    fun aDropIsADropWhateverCausedIt() {
        val e = ConnectionDroppedException("reset", java.net.SocketException("Connection reset"))
        assertEquals(Trouble.Dropped, e.toProblem().trouble)
    }
}

/** The screen keeps asking for its pane while the connection is down; that must not crash it. */
class WatchPaneTest {
    @Test
    fun watchingAPaneWhileDisconnectedKeepsTryingInsteadOfThrowing() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(Endpoint("127.0.0.1", port))
        try {
            session.start(scope)
            // No connection, no session: the flow has nothing to show yet and must wait, not fail.
            val job = scope.launch(Dispatchers.Default) {
                session.watchPane("block:none").collect { }
            }
            delay(2_000)
            assertTrue("watching a pane threw or finished", job.isActive)
        } finally {
            session.stop()
            scope.cancel()
        }
    }
}

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
import org.junit.Test
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

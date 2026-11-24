package me.leaver.rexpocket.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.net.TailnetNode
import me.leaver.rexpocket.net.TailnetStatus
import me.leaver.rexpocket.net.TailscaleConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tailnet control's watchdog, on a node that gets stuck as the phone's did: started, then
 * "Starting" with its management connection failing (DeadlineExceeded), and never coming back by
 * itself. A connect must not be handed it as it is; the watchdog starts it again while it is
 * needed; a network change starts again one in trouble and leaves a healthy one alone; and asks
 * queued behind a start that failed take its answer instead of each waiting out the same dial.
 */
@RunWith(AndroidJUnit4::class)
class TailnetWatchdogTest {
    private val stuck = TailnetStatus(
        backendState = "Starting",
        health = listOf("management: rpc error: code = DeadlineExceeded desc = context deadline exceeded"),
    )
    private val healthy = TailnetStatus(backendState = "Running")

    /** A watched node whose state the test sets; a start leaves it as [afterStart] says. */
    private class StuckNode(var afterStart: TailnetStatus) : TailnetNode {
        val state = MutableStateFlow(TailnetStatus())
        override val status: StateFlow<TailnetStatus> = state
        override val proxyPort: Int = 1080
        override val watched: Boolean = true
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        @Volatile var startError: String? = null
        @Volatile var startMillis = 0L

        override fun knows(host: String) = true
        override suspend fun awaitPeer(host: String, timeoutMs: Long) = true
        override fun probePath(host: String): TailnetPath = TailnetPath.Unreachable("test")
        override fun dialError(host: String): String? = null
        override fun resetNetwork() = Unit
        override fun start(config: TailnetConfig): Int {
            starts.incrementAndGet()
            if (startMillis > 0) Thread.sleep(startMillis)
            startError?.let { throw TailnetException(it) }
            state.value = afterStart
            return proxyPort
        }
        override fun stop() {
            stops.incrementAndGet()
            state.value = TailnetStatus()
        }
        override fun stopAsync() = stop()
    }

    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    @After
    fun close() {
        scope.cancel()
        thread.close()
    }

    private fun control(node: StuckNode, stuckAfterMs: Long, watchTickMs: Long) = TailnetControl(
        scope = scope,
        specs = { listOf(TailnetSpec("t", "Test", node, joined = true, config = { TailscaleConfig(it, "phone") })) },
        users = { mapOf("t" to 1) },
        away = { false },
        changes = emptyList(),
        read = { TailnetPolicies() },
        write = {},
        stuckAfterMs = stuckAfterMs,
        watchTickMs = watchTickMs,
    )

    private suspend fun waitFor(what: String, check: () -> Boolean) {
        withTimeout(5_000) { while (!check()) delay(20) }
        assertTrue(what, check())
    }

    @Test
    fun aConnectIsNotHandedANodeKnownToBeStuck() = runBlocking {
        val node = StuckNode(afterStart = stuck)
        // The watchdog's own tick is far off: only the connect's start can notice.
        val control = control(node, stuckAfterMs = 100, watchTickMs = 3_600_000)
        control.start("t")
        assertEquals(1, node.starts.get())
        delay(250)
        // Before the watchdog, this was "reusing the node": the same stuck node, and Rex never answered.
        control.start("t")
        assertEquals("the stuck node is started again", 2, node.starts.get())
        assertEquals(1, node.stops.get())
        assertEquals(1, control.run("t").restarts)
        assertTrue(control.run("t").lastRestart.orEmpty().contains("DeadlineExceeded"))
    }

    @Test
    fun aHealthyNodeIsReused() = runBlocking {
        val node = StuckNode(afterStart = healthy)
        val control = control(node, stuckAfterMs = 100, watchTickMs = 50)
        control.start("t")
        delay(300)
        control.start("t")
        assertEquals(1, node.starts.get())
        assertEquals(0, node.stops.get())
    }

    @Test
    fun theWatchdogRestartsANodeThatStaysStuckThenBacksOff() = runBlocking {
        val node = StuckNode(afterStart = stuck)
        val control = control(node, stuckAfterMs = 100, watchTickMs = 30)
        control.start("t")
        waitFor("restarted by the watchdog") { node.starts.get() == 2 }
        // The next restart waits out the backoff (15 s), not another bound.
        delay(400)
        assertEquals(2, node.starts.get())
    }

    @Test
    fun aStoppedNodeIsNotWatched() = runBlocking {
        val node = StuckNode(afterStart = stuck)
        val control = control(node, stuckAfterMs = 100, watchTickMs = 30)
        control.start("t")
        scope.async { control.stopAll() }.await()
        delay(400)
        assertEquals(1, node.starts.get())
    }

    @Test
    fun aNodeThatRecoversIsNotRestarted() = runBlocking {
        val node = StuckNode(afterStart = stuck)
        val control = control(node, stuckAfterMs = 200, watchTickMs = 30)
        control.start("t")
        delay(80)
        node.state.value = healthy
        delay(400)
        assertEquals(1, node.starts.get())
    }

    @Test
    fun aNetworkChangeRestartsANodeInTroubleAndLeavesAHealthyOne() = runBlocking {
        val node = StuckNode(afterStart = healthy)
        val control = control(node, stuckAfterMs = 3_600_000, watchTickMs = 3_600_000)
        control.start("t")
        scope.async { control.networkChanged() }.await()
        delay(100)
        assertEquals("a healthy node keeps its connections", 1, node.starts.get())
        node.state.value = stuck
        scope.async { control.networkChanged() }.await()
        waitFor("restarted at once on the new network") { node.starts.get() == 2 }
    }

    @Test
    fun asksQueuedBehindAFailedStartTakeItsAnswer() = runBlocking {
        val node = StuckNode(afterStart = healthy)
        node.startError = "create auth client: create connection: dial context: context deadline exceeded"
        node.startMillis = 300
        val control = control(node, stuckAfterMs = 3_600_000, watchTickMs = 3_600_000)
        val first = scope.async { runCatching { control.start("t") } }
        delay(50)
        val second = scope.async { runCatching { control.start("t") } }
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        // Before, each queued ask waited out its own 30 s dial: four of them in two minutes.
        assertEquals(1, node.starts.get())
    }

    @Test
    fun aStartThatFailedOnTheNetworkIsTriedAgainOnTheNextOne() = runBlocking {
        val node = StuckNode(afterStart = healthy)
        node.startError = "dial context: context deadline exceeded"
        val control = control(node, stuckAfterMs = 3_600_000, watchTickMs = 3_600_000)
        scope.async { control.begin() }.await()
        waitFor("the plan's start failed") { control.run("t").error != null }
        assertEquals(1, node.starts.get())
        node.startError = null
        scope.async { control.networkChanged() }.await()
        waitFor("started again on the new network") { node.starts.get() == 2 && control.run("t").error == null }
    }
}

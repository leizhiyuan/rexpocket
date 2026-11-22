package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the terminal screen does with a [PocketSession], without Compose: it streams the pane it
 * shows - the view's focused pane, unless the pane it showed was closed, as `TermScreen` decides -
 * and starts over whenever that changes. It records every notice and connection problem that would
 * have been on the banner, so a scenario can say none flashed by.
 */
class Screen(scope: CoroutineScope, private val phone: PocketSession) {
    private val seen = CopyOnWriteArrayList<String>()
    private val snapshots = ConcurrentHashMap<String, AtomicInteger>()

    init {
        scope.launch {
            var last: Long? = null
            phone.state.collect { st ->
                val notice = st.notice
                if (notice != null && notice.id != last) {
                    last = notice.id
                    seen += "on ${shown(st)}: $notice"
                }
            }
        }
        scope.launch(Dispatchers.Default) { drive() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun drive() {
        phone.state.map { shown(it) }.distinctUntilChanged().collectLatest { block ->
            if (block == null) return@collectLatest
            phone.watchPane(block).collect { feed ->
                if (feed is TerminalFeed.Snapshot) snapshots.computeIfAbsent(block) { AtomicInteger() }.incrementAndGet()
            }
        }
    }

    /** The pane on screen: none while the note that it closed stands in its place. */
    fun shown(st: PocketState = phone.state.value): String? {
        val view = st.view ?: return null
        if (st.closed?.sessionId == view.sessionId) return null
        return view.focusedBlock()?.id
    }

    /** What the red line under the header shows now, if anything. */
    fun banner(st: PocketState = phone.state.value): String? = when {
        st.phase != Phase.Live && st.problem != null -> "problem ${st.problem}"
        st.notice != null -> "notice ${st.notice}"
        else -> null
    }

    fun pictures(block: String): Int = snapshots[block]?.get() ?: 0

    /** Every notice shown so far, in order. */
    fun errors(): List<String> = seen.toList()

    fun forget() = seen.clear()
}

/** A Rex to run scenarios against: a [ScriptedRex] here, or a real one. */
interface RexUnderTest : AutoCloseable {
    val endpoint: Endpoint

    /** A network outage: the phone's connections drop and it resumes. */
    fun drop(phone: PocketSession) = phone.reconnectNow()
}

/** A real Rex listening without TLS on REX_TEST_HOST:REX_TEST_PORT, 127.0.0.1:7297 by default. */
class RealRex : RexUnderTest {
    override val endpoint = Endpoint(
        System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
        System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
        plaintext = true,
    )

    val reachable: Boolean
        get() = try {
            Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), 800); true }
        } catch (_: Exception) {
            false
        }

    override fun close() {}
}

class FakeRexUnderTest : RexUnderTest {
    val rex = ScriptedRex()
    override val endpoint get() = rex.endpoint

    override fun drop(phone: PocketSession) = rex.drop(phone.state.value.clientId!!)

    override fun close() = rex.close()
}

/** One scenario's world: a Rex, the desk, the phone and its screen. */
class Scenario(val rex: RexUnderTest, val scope: CoroutineScope) {
    val desk = Desk(rex.endpoint)
    val phone = PocketSession(rex.endpoint)
    val screen = Screen(scope, phone)

    val phoneId: String get() = phone.state.value.clientId!!

    suspend fun start() {
        desk.connect()
        phone.start(scope)
        withTimeout(15_000) { phone.awaitReady() }
        phone.setSize(60, 20, 8, 16)
    }

    /** Waits for [ok] of the phone's state; fails with what the state was instead. */
    suspend fun until(what: String, timeoutMs: Long = 10_000, ok: (PocketState) -> Boolean): PocketState =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val st = phone.state.value
                if (ok(st)) return@withTimeoutOrNull st
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") null
        } ?: throw AssertionError("$what: never happened. State: ${describe()}; banner so far: ${screen.errors()}")

    /** Waits until the pane is on screen, has its picture, and the phone holds it on Rex. */
    suspend fun showsAndHolds(sid: String, block: String, timeoutMs: Long = 10_000) {
        until("$block on screen") { screen.shown(it) == block }
        withTimeoutOrNull(timeoutMs) { while (screen.pictures(block) == 0) delay(20) }
            ?: throw AssertionError("$block never got its picture. State: ${describe()}; banner: ${screen.errors()}")
        withTimeoutOrNull(timeoutMs) { while (desk.ownerOf(sid, block) != phoneId) delay(100) }
            ?: throw AssertionError("the phone never took $block (owner ${desk.ownerOf(sid, block)}). State: ${describe()}; banner: ${screen.errors()}")
        until("$block reported as the phone's") { it.owning && it.holder == Holder.Phone }
    }

    fun describe(): String {
        val st = phone.state.value
        return "phase=${st.phase} active=${st.activeSessionId} shown=${screen.shown(st)} closed=${st.closed} " +
            "owning=${st.owning} released=${st.released} banner=${screen.banner(st)}"
    }

    companion object {
        fun run(rex: RexUnderTest, body: suspend Scenario.() -> Unit) = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val scenario = Scenario(rex, scope)
            try {
                scenario.start()
                scenario.body()
            } finally {
                scenario.phone.stop()
                scope.cancel()
                scenario.desk.close()
                rex.close()
            }
        }
    }
}

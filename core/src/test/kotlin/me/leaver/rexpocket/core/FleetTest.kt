package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Two hosts connected at once, each its own ScriptedRex: they connect and disconnect on their own,
 * both raise alerts with their host, a reply reaches the host it names, and one host reconnecting
 * leaves the other's connection alone. See docs/design/multi-host.md.
 */
class FleetTest {
    private class Host(val id: String) : AutoCloseable {
        val rex = ScriptedRex()
        val desk = Desk(rex.endpoint)
        lateinit var sid: String
        lateinit var block: String

        suspend fun prepare() {
            desk.connect()
            val (made, tabs) = desk.newSession(tabs = 1)
            sid = made
            block = tabs.first().second
        }

        override fun close() {
            desk.close()
            rex.close()
        }
    }

    private fun twoHosts(body: suspend (Fleet, Host, Host) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fleet = Fleet(scope)
        val a = Host("a")
        val b = Host("b")
        try {
            a.prepare()
            b.prepare()
            fleet.add(a.id, PocketSession(a.rex.endpoint), a.sid)
            fleet.add(b.id, PocketSession(b.rex.endpoint), b.sid)
            awaitLive(fleet, a)
            awaitLive(fleet, b)
            body(fleet, a, b)
        } finally {
            fleet.members.value.keys.toList().forEach { fleet.disconnect(it) }
            scope.cancel()
            a.close()
            b.close()
        }
    }

    private suspend fun awaitLive(fleet: Fleet, host: Host) = withTimeout(15_000) {
        while (true) {
            val st = fleet[host.id]?.state?.value
            if (st?.phase == Phase.Live && st.views[host.sid] != null && host.block in st.blockStatus.keys + st.views.getValue(host.sid).allBlocks().map { it.id }) break
            delay(20)
        }
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(20) }
        } catch (e: Exception) {
            throw AssertionError("still waiting: $what", e)
        }
    }

    @Test
    fun eachHostConnectsAndDisconnectsOnItsOwn() = twoHosts { fleet, a, b ->
        val bClient = fleet[b.id]!!.state.value.clientId
        fleet.disconnect(a.id)
        assertNull(fleet[a.id])
        assertEquals(listOf(b.id), fleet.members.value.keys.toList())
        // B was not touched: the same client, still live, no new hello.
        delay(300)
        assertEquals(Phase.Live, fleet[b.id]!!.state.value.phase)
        assertEquals(bClient, fleet[b.id]!!.state.value.clientId)
        // A connects again beside it.
        fleet.add(a.id, PocketSession(a.rex.endpoint), a.sid)
        awaitLive(fleet, a)
        assertEquals(setOf(a.id, b.id), fleet.members.value.keys)
        assertEquals(bClient, fleet[b.id]!!.state.value.clientId)
    }

    @Test
    fun bothHostsRaiseAlertsAndEachKeepsItsOwnLedger() = twoHosts { fleet, a, b ->
        val posted = CopyOnWriteArrayList<Pair<String, String>>()
        val cancelled = CopyOnWriteArrayList<Pair<String, String>>()
        val prefs = MutableStateFlow(AlertPrefs())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val heard = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                fleet.alertMoves(prefs) { true }.collect { alerts ->
                    heard += alerts.hostId
                    for (move in alerts.moves) when (move) {
                        is AlertMove.Post -> posted += alerts.hostId to move.block
                        is AlertMove.Cancel -> cancelled += alerts.hostId to move.block
                    }
                }
            }
            // The first state each planner sees is what is already there; news is what comes after it.
            until("both planners saw their host") { a.id in heard && b.id in heard }
            a.rex.report(a.block, "blocked", kind = "question", app = "claude", msg = "A?")
            b.rex.report(b.block, "blocked", kind = "permission", app = "codex", msg = "B?")
            until("both hosts' alerts") { (a.id to a.block) in posted && (b.id to b.block) in posted }
            // A's program goes back to work: only A's alert goes.
            a.rex.report(a.block, "working", app = "claude")
            until("A's alert settled") { (a.id to a.block) in cancelled }
            delay(200)
            assertFalse(cancelled.any { it.first == b.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun signalsCarryTheirHost() = twoHosts { fleet, a, b ->
        val signals = CopyOnWriteArrayList<HostSignal>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { fleet.signals.collect { signals += it } }
            b.rex.notify(b.block, "", "from b")
            until("B's notification") { signals.any { it.hostId == b.id && it.signal.blockId == b.block } }
            assertTrue(signals.none { it.hostId == a.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReplyReachesTheHostItNames() = twoHosts { fleet, a, b ->
        assertTrue(fleet.reply(b.id, b.sid, b.block, "yes"))
        until("B's pane got the reply") { b.rex.inputs.toList() == listOf("paste ${b.block} yes", "key ${b.block} +enter") }
        assertTrue(a.rex.inputs.isEmpty())
        assertTrue(fleet.interrupt(a.id, a.sid, a.block))
        until("A's pane got Ctrl+C") { a.rex.inputs.toList() == listOf("key ${a.block} ctrl+key_c") }
        assertEquals(2, b.rex.inputs.size)
        // A host that is not connected cannot be answered.
        assertFalse(fleet.reply("gone", a.sid, a.block, "no"))
    }

    @Test
    fun oneHostReconnectingLeavesTheOtherAlone() = twoHosts { fleet, a, b ->
        val aClient = fleet[a.id]!!.state.value.clientId!!
        val bClient = fleet[b.id]!!.state.value.clientId
        val bHellos = b.rex.calls.count { it.startsWith("session.bootstrap") }
        val bPhases = CopyOnWriteArrayList<Phase>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { fleet[b.id]!!.state.collect { bPhases += it.phase } }
            val aHellos = a.rex.calls.count { it.startsWith("session.bootstrap") }
            a.rex.drop(aClient)
            fleet[a.id]!!.reconnectNow()
            // Counted on A's Rex, not from A's phases: a state flow keeps only the latest, and the
            // redial on loopback is quick enough that a collector missed Reconnecting now and then.
            until("A redialed and is live again") {
                a.rex.calls.count { it.startsWith("session.bootstrap") } > aHellos && fleet[a.id]!!.state.value.phase == Phase.Live
            }
            assertEquals(aClient, fleet[a.id]!!.state.value.clientId)
            assertTrue("B left Live: $bPhases", bPhases.all { it == Phase.Live })
            assertEquals(bClient, fleet[b.id]!!.state.value.clientId)
            assertEquals(bHellos, b.rex.calls.count { it.startsWith("session.bootstrap") })
        } finally {
            scope.cancel()
        }
    }
}

package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The order of sessions and tabs is Rex's, one for every client: the phone moves a session or a tab
 * with `session.move` and `session.move_window`, and follows a move made at the desk. On
 * ScriptedRex every run, and on a real Rex where one listens (the `rex-real` workflow).
 */
abstract class OrderTest {
    abstract fun rex(): RexUnderTest

    class World(val rex: RexUnderTest, val desk: Desk, val phone: PocketSession, val ids: List<String>)

    private fun withPhone(body: suspend World.() -> Unit) = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val ids = List(3) { desk.newSession(tabs = 3).first }
            phone.start(scope, preferredSessionId = ids[0])
            withTimeout(15_000) { phone.awaitReady() }
            // The other sessions' views come after the connection is live.
            awaitThat("the phone has every view") { ids.all { it in phone.state.value.views } }
            World(rex, desk, phone, ids).body()
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    /** The phone's sessions, in its order, of the ones this test made (a real Rex may have others). */
    private fun World.phoneOrder() = phone.state.value.sessions.map { it.id }.filter { it in ids }

    private suspend fun World.rexOrder() = desk.sessions().map { it.id }.filter { it in ids }

    private suspend fun World.awaitOrder(want: List<String>) {
        awaitThat("the phone's order is $want") { phoneOrder() == want }
        assertEquals(want, rexOrder())
    }

    @Test
    fun thePhoneMovesASessionInRexsOrder() = withPhone {
        val (a, b, c) = ids
        // Places count among every session on the server; these three are the last ones made.
        val base = phone.state.value.sessions.indexOfFirst { it.id == a }
        phone.moveSession(a, base + 1)
        awaitOrder(listOf(b, a, c))
        phone.moveSession(c, base)
        awaitOrder(listOf(c, b, a))
        // To the end, which Rex takes as no before_session_id.
        phone.moveSession(c, Int.MAX_VALUE)
        awaitOrder(listOf(b, a, c))
        // Where it already is: no call at all.
        phone.moveSession(c, Int.MAX_VALUE)
        assertEquals(listOf(b, a, c), rexOrder())
    }

    @Test
    fun aMoveAtTheDeskReachesThePhone() = withPhone {
        val (a, b, c) = ids
        desk.moveSession(c, before = a)
        awaitOrder(listOf(c, a, b))
        desk.moveSession(c, before = null)
        awaitOrder(listOf(a, b, c))
        desk.rename(b, "renamed-after-a-move")
        awaitThat("the rename reached the phone") { phone.state.value.sessions.any { it.id == b && it.label == "renamed-after-a-move" } }
        assertEquals(listOf(a, b, c), phoneOrder())
    }

    @Test
    fun thePhoneMovesATabWithoutChangingTheActiveOne() = withPhone {
        val sid = ids[1]
        val before = phone.state.value.views.getValue(sid)
        val tabs = before.windows.map { it.id }
        phone.moveWindow(sid, tabs[2], 0)
        awaitThat("the phone's tabs are moved") { phone.state.value.views[sid]?.windows?.map { it.id } == listOf(tabs[2], tabs[0], tabs[1]) }
        assertEquals(listOf(tabs[2], tabs[0], tabs[1]), desk.view(sid).windows.map { it.id })
        assertEquals(before.activeWindowId, desk.view(sid).activeWindowId)
        phone.moveWindow(sid, tabs[2], Int.MAX_VALUE)
        awaitThat("the tab is last") { phone.state.value.views[sid]?.windows?.map { it.id } == listOf(tabs[0], tabs[1], tabs[2]) }
    }
}

private suspend fun awaitThat(what: String, condition: () -> Boolean) {
    withTimeoutOrNull(10_000) { while (!condition()) delay(20) } ?: throw AssertionError("timed out: $what")
}

class ScriptedOrderTest : OrderTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()
}

class RealRexOrderTest : OrderTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }
}

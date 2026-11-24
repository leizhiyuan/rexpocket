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
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A done or failed pane a desk looked at counts as seen on the phone too, inferred from what Rex
 * tells, since it keeps no seen state (docs/design/home.md, "Seen"): a desk app taking the pane
 * after it finished, or the pane being the session's active one while a desk app owns it. Another
 * client that is no desk app - this phone's kind, a script - looking changes nothing, and a new
 * report of the pane is news again.
 */
class DeskSeenTest {
    private fun withRex(body: suspend (ScriptedRex, Desk, PocketSession, String, List<Pair<String, String>>) -> Unit) = runBlocking {
        val rex = ScriptedRex()
        // Rex.app on a Mac, as its hello says.
        val mac = Desk(rex.endpoint, platform = "macos")
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            mac.connect()
            val (sid, tabs) = mac.newSession(tabs = 2)
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            body(rex, mac, phone, sid, tabs)
        } finally {
            phone.stop()
            scope.cancel()
            mac.close()
            rex.close()
        }
    }

    private suspend fun PocketSession.until(what: String, ok: (PocketState) -> Boolean): PocketState =
        withTimeoutOrNull(10_000) {
            while (!ok(state.value)) delay(20)
            state.value
        } ?: throw AssertionError("$what never happened: deskSeen=${state.value.deskSeen} status=${state.value.blockStatus}")

    private fun stamp(st: PocketState, block: String) = seenStamp(st.blockStatus.getValue(block))

    @Test
    fun aDoneTabTheDeskTakesAfterwardsIsSeen() = withRex { rex, mac, phone, sid, tabs ->
        val (firstTab, pane) = tabs[0]
        rex.report(pane, "done", msg = "built", app = "cargo")
        val st = phone.until("the report") { it.blockStatus[pane]?.state == "done" }
        delay(300)
        assertFalse("seen before the desk looked", pane in phone.state.value.deskSeen)
        // The desk shows that tab, and takes its pane, as Rex.app does.
        mac.focus(sid, firstTab)
        mac.take(sid, pane)
        phone.until("seen on the desk") { it.deskSeen[pane] == stamp(st, pane) }
    }

    @Test
    fun aDeskAlreadyOnTheTabSeesWhatItsProgramSays() = withRex { rex, mac, phone, sid, tabs ->
        // The desk shows the first tab (the phone shows the second, Rex's focus when it opened).
        val (firstTab, pane) = tabs[0]
        mac.focus(sid, firstTab)
        mac.take(sid, pane)
        phone.until("the desk on the first tab") { st -> st.views[sid]?.windows?.first()?.blocks?.first()?.owner == mac.id }
        rex.report(pane, "error", msg = "3 tests failed", app = "pi")
        phone.until("seen on the desk") { st -> st.blockStatus[pane] != null && st.deskSeen[pane] == stamp(st, pane) }
    }

    @Test
    fun aClientThatIsNoDeskAppLookingChangesNothing() = withRex { rex, _, phone, sid, tabs ->
        val script = Desk(rex.endpoint)
        try {
            script.connect()
            val (firstTab, pane) = tabs[0]
            rex.report(pane, "done", app = "cargo")
            phone.until("the report") { it.blockStatus[pane]?.state == "done" }
            script.focus(sid, firstTab)
            script.take(sid, pane)
            delay(800)
            assertFalse("a client that is no desk app counted as one", pane in phone.state.value.deskSeen)
        } finally {
            script.close()
        }
    }

    @Test
    fun aNewReportAfterTheDeskLookedIsNewsAgain() = withRex { rex, mac, phone, sid, tabs ->
        val (firstTab, pane) = tabs[0]
        rex.report(pane, "done", msg = "first", app = "cargo")
        phone.until("the report") { it.blockStatus[pane]?.msg == "first" }
        mac.focus(sid, firstTab)
        mac.take(sid, pane)
        val seen = phone.until("seen on the desk") { pane in it.deskSeen }.deskSeen.getValue(pane)
        // The desk moves on; the program reports again while nobody looks at it.
        mac.focus(sid, tabs[1].first)
        delay(300)
        rex.report(pane, "done", msg = "second", app = "cargo")
        val st = phone.until("the new report") { it.blockStatus[pane]?.msg == "second" }
        delay(300)
        assertEquals("the desk saw the first report only", seen, phone.state.value.deskSeen[pane])
        assertFalse(phone.state.value.deskSeen[pane] == stamp(st, pane))
    }
}

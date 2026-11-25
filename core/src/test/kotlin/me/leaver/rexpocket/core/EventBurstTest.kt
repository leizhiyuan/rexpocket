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
import org.junit.Test

/**
 * Rex's events are taken in order, and some wait on a call - a tab opened at the desk reads the
 * session's view again. On a slow network a busy pane's events (progress bars, titles) pile up behind
 * that call; none may be lost, or the phone never hears that an agent is waiting for an answer.
 */
class EventBurstTest {
    @Test
    fun eventsBehindASlowCallAreNotLost() = runBlocking {
        val rex = ScriptedRex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 2)
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            val pane = tabs[0].second
            rex.report(pane, "working", app = "claude", msg = "Refactoring")
            withTimeout(10_000) { while (phone.state.value.blockStatus[pane]?.state != "working") delay(20) }

            // The view the desk's focus change asks for is slow to come...
            rex.hold("session.view")
            desk.focus(sid, tabs[1].first)
            // ...the agent asks for approval meanwhile, and another program draws its progress bar.
            rex.report(pane, "blocked", app = "claude", msg = "Approve the edit?")
            repeat(600) { rex.progress(tabs[1].second, 1, it % 100) }
            delay(500)
            rex.release("session.view")

            withTimeoutOrNull(10_000) { while (phone.state.value.blockStatus[pane]?.state != "blocked") delay(20) }
            assertEquals("the agent's state on the phone", "blocked", phone.state.value.blockStatus[pane]?.state)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

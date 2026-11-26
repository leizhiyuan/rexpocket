package me.leaver.rexpocket.core

import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pane the phone holds is not asked who owns it every few seconds: Rex tells of an owner change
 * with `block_owner_changed`, and the check runs on that (with a slow safety net).
 */
class PanePollTest {
    @Test
    fun aHeldPaneIsCheckedOnOwnerNewsNotOnATimer() = Scenario.run(FakeRexUnderTest()) {
        val scripted = (rex as FakeRexUnderTest).rex
        fun ownerChecks() = scripted.calls.count { it.startsWith("com.superlogical.terminal.size") }
        val (sid, tabs) = desk.newSession(tabs = 1)
        val (_, block) = tabs[0]
        phone.openSession(sid)
        showsAndHolds(sid, block)
        delay(500)
        val before = ownerChecks()
        delay(7_000)
        val asked = ownerChecks() - before
        assertTrue("owner asked $asked times in 7 s while held", asked == 0)
        // The desk takes it: the phone hears of it at once and leaves it there.
        desk.take(sid, block)
        until("left to the desk", timeoutMs = 3_000) { it.released }
    }
}

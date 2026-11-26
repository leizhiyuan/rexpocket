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
        // A 3 s poll asked twice or more; a late wake (a size sent just after the pane was held) once.
        assertTrue("owner asked $asked times in 7 s while held", asked <= 1)
        // The desk takes it: the phone hears of it at once and leaves it there.
        desk.take(sid, block)
        until("left to the desk", timeoutMs = 10_000) { it.released }
    }
}

/** A pane that closed leaves nothing of itself in the published state; panes ever seen do not pile up. */
class GonePanesTest {
    @Test
    fun aClosedPanesStatusAndTitleGoWithIt() = Scenario.run(FakeRexUnderTest()) {
        val scripted = (rex as FakeRexUnderTest).rex
        val (sid, tabs) = desk.newSession(tabs = 2)
        val (window2, block2) = tabs[1]
        phone.openSession(sid)
        scripted.report(block2, "working", app = "omp")
        scripted.setTitle(block2, "omp")
        until("the second tab's status", timeoutMs = 30_000) { it.blockStatus[block2]?.state == "working" && it.titles[block2] != null }
        desk.closeTab(sid, window2)
        until("the closed pane forgotten", timeoutMs = 30_000) { st ->
            st.views[sid]?.allBlocks()?.none { it.id == block2 } == true &&
                block2 !in st.blockStatus && block2 !in st.records && block2 !in st.titles
        }
    }
}

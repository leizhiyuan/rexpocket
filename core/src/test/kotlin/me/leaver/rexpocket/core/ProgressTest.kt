package me.leaver.rexpocket.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OSC 9;4 as Rex forwards it (`progress_report`), and which bar a tab shows. */
class ProgressTest {
    private fun report(state: String, progress: Int? = null) =
        parseProgressReport(JSONObject().put("state", state).apply { if (progress != null) put("progress", progress) })

    @Test
    fun everyStateRexSendsIsABarOrItsRemoval() {
        assertEquals(PaneProgress(ProgressState.Normal, 42), report("set", 42))
        assertEquals(PaneProgress(ProgressState.Error, 10), report("error", 10))
        assertEquals(PaneProgress(ProgressState.Error, null), report("error"))
        assertEquals(PaneProgress(ProgressState.Indeterminate), report("indeterminate", 70))
        assertEquals(PaneProgress(ProgressState.Paused, 50), report("pause", 50))
        assertNull(report("remove"))
        assertNull(report("remove", 12))
    }

    @Test
    fun aPercentPastAHundredStopsThereAndAMissingOneIsNothingYet() {
        assertEquals(PaneProgress(ProgressState.Normal, 100), report("set", 255))
        assertEquals(PaneProgress(ProgressState.Normal, 0), report("set"))
        assertEquals(1f, report("set", 200)!!.fraction)
        assertNull("an indeterminate bar has no fill", report("indeterminate")!!.fraction)
    }

    @Test
    fun aStateRexAddsLaterIsStillABusyBar() {
        assertEquals(PaneProgress(ProgressState.Indeterminate), report("warp"))
        assertEquals(PaneProgress(ProgressState.Normal, 5), report("warp", 5))
    }

    @Test
    fun oscStatesMapToRexsWords() {
        assertEquals(listOf("remove", "set", "error", "indeterminate", "pause"), (0..4).map(::rexProgressState))
        assertNull(oscProgress(0))
        assertEquals(PaneProgress(ProgressState.Normal, 30), oscProgress(1, 30))
        assertEquals(PaneProgress(ProgressState.Error, 30), oscProgress(2, 30))
        assertEquals(PaneProgress(ProgressState.Indeterminate), oscProgress(3))
        assertEquals(PaneProgress(ProgressState.Paused, null), oscProgress(4))
        for (osc in 0..4) {
            val rex = report(rexProgressState(osc), 30)
            assertEquals("OSC $osc", oscProgress(osc, 30)?.state, rex?.state)
        }
    }

    private fun pane(id: String) = BlockInfo("block:$id", id, null, null, null)

    @Test
    fun aTabShowsItsFocusedPanesBarElseItsMostPressing() {
        val tab = WindowInfo("window:1", "build", "block:a", null, listOf(pane("a"), pane("b"), pane("c")))
        val work = PaneProgress(ProgressState.Normal, 40)
        val broke = PaneProgress(ProgressState.Error, 70)
        val busy = PaneProgress(ProgressState.Indeterminate)
        assertNull(tabProgress(tab, emptyMap()))
        assertEquals(work, tabProgress(tab, mapOf("block:a" to work, "block:b" to broke)))
        assertEquals(broke, tabProgress(tab, mapOf("block:c" to busy, "block:b" to broke)))
        assertEquals(busy, tabProgress(tab, mapOf("block:c" to busy, "block:other" to broke)))

        val view = SessionView(1, "session:1", "work", tab.id, listOf(tab, WindowInfo("window:2", "idle", null, null, listOf(pane("d")))))
        assertEquals(
            listOf(TabProgress("session:1", "window:1", "block:b", broke)),
            tabsWithProgress(listOf(view), mapOf("block:c" to busy, "block:b" to broke)),
        )
    }
}

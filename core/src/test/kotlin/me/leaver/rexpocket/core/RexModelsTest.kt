package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RexModelsTest {
    @Test
    fun parsesView() {
        val json = org.json.JSONObject(
            """
            {"revision":3,"session_id":"session:abc","label":"work","active_window_id":"window:w",
             "windows":[{"window_id":"window:w","label":"shell","focused_block_id":"block:b","layers":[
               {"blocks":[{"block_id":"block:b","label":"shell","creator_name":"com.superlogical.terminal"}]}
             ]}]}
            """.trimIndent(),
        )
        val view = parseView(json)
        assertEquals("work", view.label)
        assertEquals("block:b", view.focusedBlock()?.id)
        assertEquals(1, view.windows.size)
    }

    @Test
    fun shownPaneIsTheActiveTabsFocusedPane() {
        val view = view(
            active = "w2",
            window("w1", focused = "a", "a", "b"),
            window("w2", focused = "d", "c", "d", "e"),
        )
        assertEquals("d", view.focusedBlock()?.id)
        assertEquals("w2", view.activeWindow()?.id)
    }

    @Test
    fun reopeningASessionPrefersThePaneLastShownOnThePhone() {
        val view = view(
            active = "w1",
            window("w1", focused = "a", "a", "b"),
            window("w2", focused = "c", "c", "d"),
        )
        assertEquals("d", view.paneToShow(remembered = "d")?.id)
        // Gone, or never seen: fall back to the pane Rex has focused, where the desktop is.
        assertEquals("a", view.paneToShow(remembered = "gone")?.id)
        assertEquals("a", view.paneToShow(remembered = null)?.id)
    }

    @Test
    fun showingAPaneMakesItsTabActiveAndFocusesItThere() {
        val view = view(
            active = "w1",
            window("w1", focused = "a", "a", "b"),
            window("w2", focused = "c", "c", "d"),
        )
        val next = view.showingPane("d", revision = 9)!!
        assertEquals("w2", next.activeWindowId)
        assertEquals("d", next.focusedBlock()?.id)
        assertEquals("a", next.windows[0].focusedBlockId)
        assertEquals(9L, next.revision)
        // A stale revision from the call never moves the view backwards.
        assertEquals(9L, next.showingPane("c", revision = 3)!!.revision)
        assertNull(view.showingPane("gone", revision = 9))
    }

    @Test
    fun showingATabKeepsThePaneItLastHadFocused() {
        val view = view(active = "w1", window("w1", focused = "a", "a"), window("w2", focused = "d", "c", "d"))
        val next = view.showingTab("w2", revision = 4)!!
        assertEquals("d", next.focusedBlock()?.id)
        assertNull(view.showingTab("gone", revision = 4))
    }

    @Test
    fun focusedBlockFallsBackToTheFirstPaneOfTheTab() {
        val w = window("w1", focused = null, "a", "b")
        assertEquals("a", w.focusedBlock()?.id)
        assertEquals(0, w.focusedIndex())
        assertEquals(1, window("w1", focused = "b", "a", "b").focusedIndex())
    }

    @Test
    fun aPaneStatusIsItsMostUrgentRecord() {
        val records = listOf(record("working"), record("blocked"), record("done"))
        assertEquals("blocked", summarizeStatus(records)?.state)
        assertNull(summarizeStatus(emptyList()))
    }

    @Test
    fun countsPanesAcrossTabs() {
        val view = view(active = "w1", window("w1", focused = "a", "a", "b"), window("w2", focused = "c", "c"))
        assertEquals(3, view.allBlocks().size)
    }


    @Test
    fun aSessionListedTwiceKeepsOneRow() {
        // Rex can report the same session twice while it moves; the list is keyed by id, so a
        // repeat used to crash the screen.
        val json = org.json.JSONArray(
            """[
                {"session_id":"session:a","label":"one"},
                {"session_id":"session:b","label":"beta"},
                {"session_id":"session:a","label":"renamed"}
            ]""",
        )
        val sessions = parseSessions(json)
        assertEquals(listOf("session:a", "session:b"), sessions.map { it.id })
        assertEquals("renamed", sessions.first().label)
    }

    private fun view(active: String, vararg windows: WindowInfo) =
        SessionView(revision = 1, sessionId = "s", label = "s", activeWindowId = active, windows = windows.toList())

    private fun window(id: String, focused: String?, vararg blocks: String) = WindowInfo(
        id = id,
        label = id,
        focusedBlockId = focused,
        zoomedBlockId = null,
        blocks = blocks.map { BlockInfo(it, it, null, null, null) },
    )

    private fun record(state: String) = ProgramRecord("r", state, null, null, null, null, null)
}

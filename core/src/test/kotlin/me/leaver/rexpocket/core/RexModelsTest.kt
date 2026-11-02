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

    /** Opening a tab from the home shows that tab: its remembered pane if it has it, else its own focus. */
    @Test
    fun openingATabShowsAPaneOfThatTab() {
        val view = view(
            active = "w1",
            window("w1", focused = "a", "a", "b"),
            window("w2", focused = "c", "c", "d"),
        )
        assertEquals("d", view.paneToShow(remembered = "d", windowId = "w2")?.id)
        // The remembered pane is in another tab: the asked-for tab wins, on the pane it has focused.
        assertEquals("c", view.paneToShow(remembered = "b", windowId = "w2")?.id)
        assertEquals("c", view.paneToShow(remembered = null, windowId = "w2")?.id)
        // A tab that is gone falls back to the session's own choice.
        assertEquals("b", view.paneToShow(remembered = "b", windowId = "gone")?.id)
    }

    /** Rex reports a program's status as one record at a time; a cleared record goes away. */
    @Test
    fun aStatusEventReplacesOrClearsItsRecord() {
        val working = org.json.JSONObject("""{"id":"","record":{"state":"working"},"reason":"report"}""")
        val records = applyStatusEvent(emptyMap(), working)
        assertEquals("working", summarizeStatus(records.values.toList())?.state)
        val idle = org.json.JSONObject("""{"id":"","record":{"state":"idle"},"reason":"report"}""")
        assertEquals("idle", summarizeStatus(applyStatusEvent(records, idle).values.toList())?.state)
        val cleared = org.json.JSONObject("""{"id":"","record":null,"reason":"clear"}""")
        assertNull(summarizeStatus(applyStatusEvent(records, cleared).values.toList()))
    }

    /** The phone shows its own pane: that pane's tab is active and the pane focused, whatever Rex has. */
    @Test
    fun aViewIsShownOnThePhonesOwnPlace() {
        val view = view(
            active = "w1",
            window("w1", focused = "a", "a", "b"),
            window("w2", focused = "c", "c", "d"),
        )
        val placed = view.placedAt("d")
        assertEquals("w2", placed.activeWindowId)
        assertEquals("d", placed.focusedBlock()?.id)
        // The other tab keeps the pane it had.
        assertEquals("a", placed.windows[0].focusedBlockId)
        // A pane that is gone, or no place yet, leaves Rex's choice as it is.
        assertEquals(view, view.placedAt("gone"))
        assertEquals(view, view.placedAt(null))
    }

    /** What a pane runs: the app a program reported first, else the foreground process Rex sees. */
    @Test
    fun aPaneIsNamedAfterWhatItRuns() {
        val pi = org.json.JSONObject(
            """{"foreground":{"name":"node","argv0":"pi","path":"/x/bin/node"}}""",
        )
        assertEquals("pi", foregroundProgram(pi))
        val shell = org.json.JSONObject("""{"foreground":{"name":"zsh","argv0":"-zsh","path":"/bin/zsh"}}""")
        assertEquals("zsh", foregroundProgram(shell))
        val vim = org.json.JSONObject("""{"foreground":{"name":"nvim","path":"/opt/bin/nvim"}}""")
        assertEquals("nvim", foregroundProgram(vim))
        assertNull(foregroundProgram(org.json.JSONObject("{}")))

        val reported = ProgramRecord("", "working", null, "claude", null, null, null)
        assertEquals("claude", paneProgram(reported, "node"))
        assertEquals("node", paneProgram(null, "node"))
        assertNull(paneProgram(null, null))
    }

    /** A title a program set is kept; what a shell writes there - its next command, user@host - is not. */
    @Test
    fun onlyAProgramsOwnTitleIsShown() {
        val pi = org.json.JSONObject("""{"title":"π - rexpocket","owner":{"pid":1,"name":"node"}}""")
        assertEquals("π - rexpocket", programTitle(pi))
        val shell = org.json.JSONObject("""{"title":"( exec -a claude sleep 900; )","owner":{"pid":2,"name":"zsh"}}""")
        assertNull(programTitle(shell))
        assertNull(programTitle(org.json.JSONObject("""{"title":""}""")))
    }

    /** A program has a mark when the app knows it; aliases and paths reach the same one. */
    @Test
    fun aProgramGetsItsMark() {
        assertEquals(ProgramMark.Pi, programMark("pi"))
        assertEquals(ProgramMark.Claude, programMark("claude"))
        assertEquals(ProgramMark.Claude, programMark("Claude Code"))
        assertEquals(ProgramMark.Codex, programMark("codex"))
        assertEquals(ProgramMark.Vim, programMark("nvim"))
        assertEquals(ProgramMark.Shell, programMark("zsh"))
        assertEquals(ProgramMark.Shell, programMark("-bash"))
        assertEquals(ProgramMark.Shell, programMark(null))
        assertEquals(ProgramMark.Other, programMark("ffmpeg"))
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

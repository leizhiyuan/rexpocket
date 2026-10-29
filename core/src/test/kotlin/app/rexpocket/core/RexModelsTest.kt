package app.rexpocket.core

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
    fun focusedBlockFallsBackToTheFirstPaneOfTheTab() {
        val w = window("w1", focused = null, "a", "b")
        assertEquals("a", w.focusedBlock()?.id)
        assertEquals(0, w.focusedIndex())
        assertEquals(1, window("w1", focused = "b", "a", "b").focusedIndex())
    }

    @Test
    fun tabStatusIsTheMostUrgentOfItsPanes() {
        val w = window("w1", focused = "a", "a", "b", "c")
        val status = mapOf(
            "a" to record("working"),
            "b" to record("blocked"),
            "x" to record("error"),
        )
        assertEquals("blocked", w.status(status)?.state)
        assertNull(window("w2", focused = "z", "z").status(status))
    }

    @Test
    fun countsPanesAcrossTabs() {
        val view = view(active = "w1", window("w1", focused = "a", "a", "b"), window("w2", focused = "c", "c"))
        assertEquals(3, view.allBlocks().size)
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

package app.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VtScreenTest {
    @Test
    fun replaysSgrAndCursor() {
        val screen = VtScreen(40, 8)
        screen.feed("\u001b[0m\u001b[1;32mGREEN\u001b[0m \u001b[31mRED\u001b[0m\r\nline2\u001b[2;3H")
        assertEquals("GREEN RED", screen.rowText(0))
        assertEquals("line2", screen.rowText(1))
        assertEquals(2, screen.cursorX)
        assertEquals(1, screen.cursorY)
        val green = screen.cellAt(0, 0)
        assertTrue(green != null && green.bold)
        assertTrue(green!!.fg != -1)
    }

    @Test
    fun wideCharacterOccupiesTwoCells() {
        val screen = VtScreen(10, 2)
        screen.feed("中a")
        assertEquals("中a", screen.rowText(0))
        assertTrue(screen.cellAt(0, 1)?.wideTail == true)
        assertEquals(3, screen.cursorX)
    }

    @Test
    fun paletteOscAndErase() {
        val screen = VtScreen(10, 3)
        screen.feed("\u001b]4;1;rgb:ff/00/00\u001b\\\u001b[31mX\u001b[0m\u001b[2J")
        assertEquals("", screen.rowText(0))
    }

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
}

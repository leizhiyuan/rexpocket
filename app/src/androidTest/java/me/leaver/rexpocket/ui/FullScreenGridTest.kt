package me.leaver.rexpocket.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.terminal.TerminalFrame
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * What a real pane's grid does when full screen makes it taller: 52 rows with the bars, 64 without.
 *
 * The owner's report: on entering full screen an agent's screen kept its old height and left the
 * bottom fifth empty. The pane does take the new rows, and asks Rex for them (`TermFullScreenTest`
 * checks the pane's box, this the grid in it). The empty rows are the terminal's own rule, the
 * same in Rex's terminal and in Ghostty on a desk: a screen that grows pulls rows back out of the
 * scrollback only when the cursor is on the bottom row. A shell's prompt is, so its screen fills;
 * an agent that draws a status line under its cursor is not, so the new rows go under that line,
 * empty, until the program draws more - a program redrawing for a height change is the one thing
 * that would fill them, and an inline one does not. A real pane, so on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class FullScreenGridTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var session: TerminalSession
    private lateinit var view: TerminalView
    private var row = 0f

    @Before
    fun open() = onMain {
        view = TerminalView(context)
        row = view.rowHeight
        session = requireNotNull(TerminalSession.create(40, BARS)) { "libghostty-vt could not make a terminal" }
        view.session = session
        layOut(BARS)
    }

    @After
    fun close() = onMain {
        view.session = null
        session.close()
    }

    private fun layOut(rows: Int) {
        val height = ceil(rows * row).toInt() + 1
        view.measure(exactly(WIDTH), exactly(height))
        view.layout(0, 0, WIDTH, height)
    }

    private fun frame(): TerminalFrame = requireNotNull(session.frame())

    private fun history() = (1..200).joinToString("") { "line $it\r\n" }

    @Test
    fun aShellAtItsPromptFillsTheTallerScreen() {
        session.feedOutput((history() + "% ").toByteArray())
        assertEquals("the prompt is on the bottom row", BARS - 1, frame().cursorY)

        onMain { layOut(FULL) }
        val frame = frame()
        assertEquals("the grid takes every row full screen gives", FULL, frame.rows)
        assertEquals("history comes down to fill it, the prompt still at the bottom", FULL - 1, frame.cursorY)
        assertEquals(FULL - 1, frame.lastDrawnRow())
        assertEquals("twelve lines of history came down", "line 138", frame.rowText(0).trim())
    }

    @Test
    fun aStatusLineUnderTheCursorLeavesTheNewRowsEmptyUntilTheProgramDraws() {
        // An agent's screen: its input line two rows up, and a status line on the bottom row.
        session.feedOutput((history() + "\u001b[$BARS;1Hstatus\u001b[${BARS - 2};1H> ").toByteArray())
        assertEquals(BARS - 3, frame().cursorY)

        onMain { layOut(FULL) }
        val frame = frame()
        assertEquals("the grid takes every row full screen gives", FULL, frame.rows)
        assertEquals("nothing moved: the cursor was not on the bottom row", BARS - 3, frame.cursorY)
        assertEquals("the status line is where it was", "status", frame.rowText(BARS - 1).trim())
        assertEquals("and the new rows under it are empty", BARS - 1, frame.lastDrawnRow())
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1000
        const val BARS = 52
        const val FULL = 64
    }
}

package me.leaver.rexpocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalTheme
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.floor

/**
 * How far the screen keyboard moves a real pane, from what the pane reports as it draws - the
 * cursor's row and the bottom of what is drawn - through [imeShift], as TermScreen does. A real
 * libghostty-vt pane, so on the emulator only.
 *
 * The pane's grid is the phone's either way: when the desk holds the pane's size Rex keeps the
 * program at the desk's size, but the phone's terminal is still the pane's own, so a short screen
 * - the owner's seven prompts with the desk holding it - is the same short screen here.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardShiftTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var session: TerminalSession
    private lateinit var view: TerminalView
    private var cursorTop: Int? = null
    private var drawnBottom: Int? = null
    private var rows = 0
    private var row = 0f

    @Before
    fun open() = onMain {
        view = TerminalView(context)
        view.theme = TerminalTheme(
            foreground = 0xFF101010.toInt(),
            background = 0xFFFAFAF5.toInt(),
            cursor = 0xFF0050FF.toInt(),
            selection = 0xFFFFCC00.toInt(),
        )
        view.onCursorRow = { cursorTop = it }
        view.onDrawnBottom = { drawnBottom = it }
        view.measure(exactly(WIDTH), exactly(HEIGHT))
        view.layout(0, 0, WIDTH, HEIGHT)
        row = view.rowHeight
        rows = floor(HEIGHT / row).toInt()
        session = requireNotNull(TerminalSession.create(40, rows)) { "libghostty-vt could not make a terminal" }
        view.session = session
    }

    @After
    fun close() = onMain {
        view.session = null
        session.close()
    }

    /** What the keyboard covers: some 40 % of the pane, as on a phone held upright. */
    private val ime get() = (HEIGHT * 0.4).toInt()

    private fun shiftAfter(text: String): Float {
        session.feedOutput(text.toByteArray())
        onMain {
            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        return imeShift(ime, HEIGHT, cursorTop, drawnBottom)
    }

    private fun prompts(n: Int) = (1..n).joinToString("\r\n") { "user@desk project %" } + " "

    @Test
    fun aFewPromptsStayWhereTheyAre() {
        // The owner's screen: seven prompts, the cursor on the last, all above the keyboard.
        val shift = shiftAfter(prompts(7))
        assertEquals("cursor on the seventh row", 6 * row, requireNotNull(cursorTop).toFloat(), 1f)
        assertEquals("nothing to move for: the prompts end well above the keyboard", 0f, shift, 0.01f)
        assertTrue("every prompt is in view", requireNotNull(drawnBottom) <= HEIGHT - ime)
    }

    @Test
    fun aCursorJustUnderTheKeyboardMovesThePaneOnlyAsFarAsItsRow() {
        // The cursor on the row the keyboard's edge cuts: the pane moves by what of it is covered, not to the cursor.
        val cursorRow = floor((HEIGHT - ime) / row).toInt()
        val shift = shiftAfter(prompts(cursorRow + 1))
        val top = requireNotNull(cursorTop)
        assertEquals((cursorRow + 1) * row - (HEIGHT - ime), shift, 2f)
        assertTrue("well short of the cursor's row", shift < top)
        assertTrue("the cursor's row clears the keyboard", top + row <= shift + (HEIGHT - ime) + 1)
        assertTrue("the first prompt is still in view", shift <= row)
    }

    @Test
    fun longOutputStillMovesTheWholeKeyboard() {
        // The cursor on the last row after a long listing: unchanged, the bottom of the grid stays in view.
        val shift = shiftAfter((1..rows * 3).joinToString("\r\n") { "line $it" })
        assertEquals("the drawing reaches the last row", null, drawnBottom)
        assertEquals(ime.toFloat(), shift, 0.01f)
    }

    @Test
    fun drawingUnderTheCursorKeepsTheBottomOfTheGridInView() {
        // A status line at the bottom with the cursor higher up, as an editor draws: unchanged, the
        // pane moves up to the cursor's row so the bottom of the grid stays above the keyboard.
        val shift = shiftAfter(prompts(3) + "\u001b7\u001b[$rows;1Hstatus\u001b8")
        assertEquals(null, drawnBottom)
        assertEquals(requireNotNull(cursorTop).toFloat(), shift, 0.01f)
    }

    @Test
    fun aFullScreenProgramIsMovedAsBefore() {
        // The alternate screen is a program's own, every row of it: the cursor's rule alone, as before.
        val shift = shiftAfter("\u001b[?1049h\u001b[H" + prompts(3))
        assertEquals(null, drawnBottom)
        assertEquals(requireNotNull(cursorTop).toFloat(), shift, 0.01f)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 1000
        const val HEIGHT = 1800
    }
}

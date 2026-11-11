package me.leaver.rexpocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.terminal.TerminalFrame
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalTheme
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The terminal view on a device: what it draws from libghostty-vt's cells, and what gestures do. */
@RunWith(AndroidJUnit4::class)
class TerminalViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val theme = TerminalTheme(
        foreground = 0xFF101010.toInt(),
        background = 0xFFFAFAF5.toInt(),
        cursor = 0xFF0050FF.toInt(),
        selection = 0xFFFFCC00.toInt(),
    )
    private val sent = StringBuilder()
    private var resized: List<Int>? = null
    private lateinit var session: TerminalSession
    private lateinit var view: TerminalView

    @Before
    fun open() = onMain {
        session = requireNotNull(TerminalSession.create(80, 24)) { "libghostty-vt could not make a terminal" }
        session.transport = object : TerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                sent.append(String(data, Charsets.UTF_8))
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) {
                resized = listOf(columns, rows)
            }
        }
        view = TerminalView(context)
        view.theme = theme
        view.session = session
        view.measure(exactly(WIDTH), exactly(HEIGHT))
        view.layout(0, 0, WIDTH, HEIGHT)
    }

    @After
    fun close() = onMain {
        view.session = null
        session.close()
    }

    private fun feed(text: String) {
        session.feedOutput(text.toByteArray())
        onMain { view.invalidate() }
    }

    private fun render(): Bitmap = onMainResult {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap
    }

    private fun frame(): TerminalFrame = requireNotNull(session.frame())

    /** Whether anything but [color] was drawn in the probe block at the top left of cell [column],[row]. */
    private fun Bitmap.cellHas(column: Int, row: Int, other: Int): Boolean {
        for (y in row * CELL_PROBE_H until (row + 1) * CELL_PROBE_H) {
            for (x in column * CELL_PROBE_W until (column + 1) * CELL_PROBE_W) if (getPixel(x, y) != other) return true
        }
        return false
    }

    @Test
    fun theGridFitsTheViewAndRexIsToldOnceItHasSettled() {
        val f = frame()
        assertTrue("columns ${f.columns} should fill ${WIDTH}px", f.columns in 20..120)
        assertTrue("rows ${f.rows}", f.rows in 8..60)
        Thread.sleep(300)
        assertEquals(listOf(f.columns, f.rows), resized)
    }

    @Test
    fun textIsDrawnInTheAppsColoursAndTheCursorFollowsIt() {
        feed("Hi")
        val picture = render()

        assertEquals("paper", theme.background, picture.getPixel(WIDTH - 2, HEIGHT - 2))
        assertTrue("ink is drawn where the text is", picture.cellHas(0, 0, theme.background))
        val cursorColumn = frame().cursorX
        assertEquals(2, cursorColumn)
        // The cursor is a block in the theme's cursor colour just after the text.
        var cursorPixels = 0
        for (y in 0 until CELL_PROBE_H) for (x in 0 until WIDTH) if (picture.getPixel(x, y) == theme.cursor) cursorPixels++
        assertTrue("cursor pixels: $cursorPixels", cursorPixels > 20)
    }

    @Test
    fun aProgramsBackgroundAndReverseVideoArePaintedBehindTheText() {
        feed("\u001b[48;2;200;0;0m \u001b[0m\u001b[7mX")
        val picture = render()

        val red = 0xFFC80000.toInt()
        var redSeen = false
        var inkSeen = false
        for (y in 0 until CELL_PROBE_H) for (x in 0 until WIDTH / 4) {
            val px = picture.getPixel(x, y)
            if (px == red) redSeen = true
            if (px == theme.foreground) inkSeen = true
        }
        assertTrue("the program's own background colour", redSeen)
        assertTrue("reverse video fills the cell with the foreground", inkSeen)
    }

    @Test
    fun draggingTheFingerDownMovesBackThroughTheHistory() {
        feed((1..200).joinToString("\r\n") { "line $it" })
        assertTrue(frame().atBottom)

        drag(fromY = 100f, toY = 600f)

        val f = frame()
        assertFalse("the viewport left the bottom", f.atBottom)
        assertEquals("nothing was typed", "", sent.toString())
    }

    @Test
    fun aTapIsAClickOnlyForAProgramThatAskedForTheMouse() {
        var tapped = 0
        onMain { view.onTap = { tapped++ } }
        tap(40f, 40f)
        assertEquals(1, tapped)
        assertEquals("no mouse requested, nothing reported", "", sent.toString())

        feed("\u001b[?1000h\u001b[?1006h")
        tap(40f, 40f)
        assertEquals(2, tapped)
        assertTrue("an SGR press and release: $sent", sent.toString().matches(Regex("\u001b\\[<0;\\d+;\\d+M\u001b\\[<0;\\d+;\\d+m")))
    }

    @Test
    fun draggingOverAProgramThatReadsTheMouseSendsItsWheel() {
        feed("\u001b[?1049h\u001b[?1000h\u001b[?1006h")
        drag(fromY = 600f, toY = 100f)

        assertTrue("wheel reports: $sent", sent.toString().contains("\u001b[<65;"))
        assertTrue(frame().atBottom)
    }

    @Test
    fun aHorizontalSwipeMovesToTheNextPaneAndScrollsNothing() {
        feed((1..100).joinToString("\r\n") { "line $it" })
        var forward: Boolean? = null
        onMain { view.onSwipe = { forward = it } }

        drag(fromX = 600f, toX = 100f, fromY = 300f, toY = 310f)

        assertEquals(true, forward)
        assertTrue("the history stayed where it was", frame().atBottom)
    }

    @Test
    fun aLongPressOnAnAddressOffersItInsteadOfSelecting() {
        feed("open https://example.com/pull/7 now")
        var link: String? = null
        onMain { view.onLink = { link = it } }

        longPress(x = 12f * 8, y = 10f)

        assertEquals("https://example.com/pull/7", link)
    }

    @Test
    fun aLongPressOnAWordSelectsItAndTheSessionReadsItBack() {
        feed("alpha beta gamma")

        longPress(x = 3f, y = 10f)

        assertEquals("alpha", session.selectionText())
        val picture = render()
        var highlighted = 0
        for (y in 0 until CELL_PROBE_H) for (x in 0 until WIDTH) if (picture.getPixel(x, y) == theme.selection) highlighted++
        assertTrue("selection highlight pixels: $highlighted", highlighted > 50)
    }

    @Test
    fun theKeyboardsKeysReachTheProgramWhoseModesTheyFollow() {
        onMain { assertTrue(view.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP, key(android.view.KeyEvent.KEYCODE_DPAD_UP))) }
        feed("\u001b[?1h")
        onMain { assertTrue(view.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_UP, key(android.view.KeyEvent.KEYCODE_DPAD_UP))) }

        assertEquals("\u001b[A\u001bOA", sent.toString())
    }

    // ---- Gestures -----------------------------------------------------------------------

    private fun key(code: Int) = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)

    private fun touch(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        onMain { view.dispatchTouchEvent(event) }
        event.recycle()
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun drag(fromX: Float = 200f, toX: Float = 200f, fromY: Float, toY: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, fromX, fromY)
        val steps = 12
        for (i in 1..steps) {
            touch(MotionEvent.ACTION_MOVE, down, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps)
            Thread.sleep(8)
        }
        touch(MotionEvent.ACTION_UP, down, toX, toY)
    }

    private fun longPress(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 400L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> onMainResult(block: () -> T): T {
        var result: T? = null
        onMain { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 900
        // 12sp at the density of a test device is some 30-50 px wide; a probe this size sits inside one.
        const val CELL_PROBE_W = 6
        const val CELL_PROBE_H = 12
    }
}

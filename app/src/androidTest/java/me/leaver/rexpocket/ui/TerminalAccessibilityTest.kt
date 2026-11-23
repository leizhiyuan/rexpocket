package me.leaver.rexpocket.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.R
import me.leaver.rexpocket.terminal.TerminalSession
import me.leaver.rexpocket.terminal.TerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pane as TalkBack sees it, on a real libghostty terminal: a node per line on screen with its
 * words, wrapped rows joined, blank rows left out, a double tap on a line acting as a tap, and the
 * keyboard's view still out of TalkBack's way.
 */
@RunWith(AndroidJUnit4::class)
class TerminalAccessibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var session: TerminalSession
    private lateinit var view: TerminalView
    private var taps = 0

    @Before
    fun open() = onMain {
        session = requireNotNull(TerminalSession.create(80, 24)) { "libghostty-vt could not make a terminal" }
        session.transport = object : TerminalSession.Transport {
            override fun sendInput(data: ByteArray) = Unit
            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }
        view = TerminalView(context)
        view.session = session
        view.onTap = { taps++ }
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

    /** The pane's node provider; on the main thread. */
    private fun provider(): AccessibilityNodeProvider = requireNotNull(view.accessibilityNodeProvider)

    private fun host(): AccessibilityNodeInfo = onMainResult { provider().createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!! }

    /** The words of each line node, in the order TalkBack visits them. */
    private fun lines(): List<String> = onMainResult {
        val p = provider()
        val host = p.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        // A line's node is named by the row it starts on, and the host lists them top to bottom.
        val ids = view.access.linesOf().map { it.firstRow }
        assertEquals(ids.size, host.childCount)
        ids.map { p.createAccessibilityNodeInfo(it)!!.text?.toString().orEmpty() }
    }

    @Test
    fun eachLineOnScreenIsANodeWithItsWords() {
        feed("\$ ls\r\nnotes.txt  todo.md\r\n\r\n\$ ")
        assertEquals(listOf("\$ ls", "notes.txt  todo.md", "\$"), lines())
        val host = host()
        assertEquals(context.getString(R.string.terminal_a11y_label), host.contentDescription)
        assertEquals(3, host.childCount)
        val node = onMainResult { provider().createAccessibilityNodeInfo(1)!! }
        assertEquals("android.widget.TextView", node.className)
        val bounds = Rect()
        @Suppress("DEPRECATION")
        node.getBoundsInParent(bounds)
        assertTrue("row 1 is below row 0: $bounds", bounds.top > 0 && bounds.height() > 0)
    }

    @Test
    fun aWrappedLineIsOneNode() {
        val long = "x".repeat(onMainResult { requireNotNull(session.frame()).columns } + 10)
        feed(long + "\r\nnext")
        assertEquals(listOf(long, "next"), lines())
    }

    @Test
    fun theNodesFollowTheScreen() {
        feed("one")
        assertEquals(listOf("one"), lines())
        feed("\r\ntwo")
        assertEquals(listOf("one", "two"), lines())
        feed("\u001b[2J\u001b[H")
        assertEquals(emptyList<String>(), lines())
    }

    @Test
    fun aDoubleTapOnALineIsATapOnThePane() {
        feed("hello")
        onMain { provider().performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertEquals(1, taps)
        onMain { provider().performAction(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityNodeInfo.ACTION_CLICK, null) }
        assertEquals(2, taps)
    }

    @Test
    fun theKeyboardsViewStaysOutOfTalkBacksWay() = onMain {
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, TerminalImeView(context).importantForAccessibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> onMainResult(block: () -> T): T {
        var result: Result<T>? = null
        onMain { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 900
    }
}

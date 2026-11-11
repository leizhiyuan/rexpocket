package me.leaver.rexpocket.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sagernet.libghostty.GhosttyTerminalSession
import io.github.sagernet.libghostty.GhosttyTerminalView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #5: a composing keyboard - Gboard's Zhuyin, or Pinyin, Cangjie, a Japanese one - has to be
 * offered by the pane at all, and what it commits has to reach the program unchanged while what it
 * is still composing stays out of it. The pane here is the real libghostty view with a real session,
 * and the bytes it sends are what the transport would have carried to Rex.
 */
@RunWith(AndroidJUnit4::class)
class TerminalImeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = StringBuilder()
    private lateinit var session: GhosttyTerminalSession
    private lateinit var pane: GhosttyTerminalView
    private lateinit var keyboard: TerminalImeView
    private var connection: InputConnection? = null

    @Before
    fun openPane() = onMain {
        session = GhosttyTerminalSession(context, GhosttyTerminalSession.Options())
        session.transport = object : GhosttyTerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                sent.append(String(data, Charsets.UTF_8))
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit

            override fun close() = Unit
        }
        pane = GhosttyTerminalView(context)
        pane.session = session
        keyboard = TerminalImeView(context)
        keyboard.pane = pane
    }

    @After
    fun closePane() = onMain {
        session.transport = null
        session.close()
    }

    private fun connect(): InputConnection = connection ?: run {
        val attrs = EditorInfo()
        keyboard.onCreateInputConnection(attrs).also { connection = it }
    }

    /** The keyboard only offers its other layouts when the field says it takes text, and not as a password. */
    @Test
    fun fieldTakesTextSoComposingKeyboardsAreOffered() {
        val attrs = EditorInfo()
        keyboard.onCreateInputConnection(attrs)

        assertTrue("The pane must be a text editor to the IME", keyboard.onCheckIsTextEditor())
        assertEquals(
            "Gboard hides its CJK layouts for a field that does not take text",
            InputType.TYPE_CLASS_TEXT,
            attrs.inputType and InputType.TYPE_MASK_CLASS,
        )
        for (password in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
            assertNotEquals(
                "A password variation is what keeps Gboard on the Latin keyboard",
                password,
                attrs.inputType and InputType.TYPE_MASK_VARIATION,
            )
        }
    }

    /** The Zhuyin flow: bopomofo composes, the chosen Han characters are committed and only they are sent. */
    @Test
    fun composingStaysOutOfThePaneAndTheCommittedHanIsSent() {
        onMain {
            val ic = connect()
            ic.setComposingText("ㄨㄛˇ", 1)
            ic.setComposingText("ㄨㄛˇㄇㄣ", 1)
            assertEquals("Composing text is not input yet", "", sent.toString())

            ic.commitText("我們", 1)
            assertEquals("我們", sent.toString())
            assertEquals("e68891e58091", sent.toString().toByteArray(Charsets.UTF_8).toHex())
        }
    }

    /**
     * What is being composed is drawn in the pane, at its cursor, and taken away again when the
     * composition ends: the library keeps that string private, and its own editor used to draw it.
     */
    @Test
    fun composingIsDrawnInThePane() {
        onMain {
            pane.measure(exactly(600), exactly(400))
            pane.layout(0, 0, 600, 400)
            val ic = connect()
            ic.commitText("ab", 1)

            val quiet = pane.render()
            ic.setComposingText("ㄨㄛˇ", 1)
            val composing = pane.render()
            ic.finishComposingText()
            val cleared = pane.render()

            assertFalse("The composing text has to be visible", quiet.sameAs(composing))
            assertTrue("And gone when the composition ends", quiet.sameAs(cleared))
        }
    }

    /** A candidate it decides not to use must leave nothing behind. */
    @Test
    fun abandonedComposingSendsNothing() {
        onMain {
            val ic = connect()
            ic.setComposingText("ㄨㄛˇ", 1)
            ic.setComposingText("", 1)
            ic.finishComposingText()
            assertEquals("", sent.toString())
        }
    }

    /** Deleting into the composition edits the composition, not the program's line. */
    @Test
    fun deletingWhileComposingLeavesTheProgramsLineAlone() {
        onMain {
            val ic = connect()
            ic.commitText("abc", 1)
            ic.setComposingText("ㄨㄛ", 1)

            ic.deleteSurroundingText(1, 0)
            assertEquals("abc", sent.toString())

            ic.finishComposingText()
            ic.deleteSurroundingText(1, 0)
            assertEquals("abc\u007f", sent.toString())
        }
    }

    /** Latin, control keys and keys the keyboard sends as events still go through as before. */
    @Test
    fun keysReachThePaneAsTheyDid() {
        onMain {
            val ic = connect()
            ic.commitText("ls -l", 1)
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))

            assertEquals("ls -l\r\u007f", sent.toString())
        }
    }

    /** A character outside the BMP is four UTF-8 bytes on the wire, as the terminal expects. */
    @Test
    fun aCodePointOutsideTheBmpIsSentWhole() {
        onMain {
            val ic = connect()
            ic.commitText("\uD840\uDC00", 1)
            assertEquals("f0a08080", sent.toString().toByteArray(Charsets.UTF_8).toHex())
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    /** The pane's own picture, at the size the test laid it out at. */
    private fun GhosttyTerminalView.render(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return bitmap
    }
}

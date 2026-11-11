package me.leaver.rexpocket.terminal

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real libghostty-vt, built for this computer by tools/build-ghostty.py --host, driven through
 * the same JNI the app uses.
 */
class TerminalSessionTest {
    private val sessions = mutableListOf<TerminalSession>()

    @After
    fun close() = sessions.forEach { it.close() }

    private fun terminal(columns: Int, rows: Int, vararg output: String): TerminalSession {
        val session = requireNotNull(TerminalSession.create(columns, rows)) { "no terminal" }
        sessions += session
        output.forEach { session.feedOutput(it.toByteArray()) }
        return session
    }

    private fun TerminalSession.frameOf(): TerminalFrame = requireNotNull(frame()) { "no frame" }

    private fun TerminalFrame.lines(): List<String> = (0 until rows).map { rowText(it).trimEnd() }

    @Test
    fun printedTextLandsOnTheGridAndMovesTheCursor() {
        val frame = terminal(20, 3, "hello\r\nworld").frameOf()

        assertEquals(listOf("hello", "world", ""), frame.lines())
        assertEquals(5, frame.cursorX)
        assertEquals(1, frame.cursorY)
    }

    @Test
    fun colourAndWeightComeBackResolved() {
        val frame = terminal(10, 1, "\u001b[1;38;2;12;200;90mG\u001b[0mx").frameOf()

        val green = frame.index(0, 0)
        assertEquals(0xFF0CC85A.toInt(), frame.fg[green])
        assertTrue(frame.flags[green] and TerminalFrame.FLAG_BOLD != 0)
        assertEquals(0, frame.flags[frame.index(1, 0)] and TerminalFrame.FLAG_BOLD)
    }

    @Test
    fun theAppsColoursReplaceTheDefaultsButNotTheProgramsOwn() {
        val session = terminal(10, 1, "a\u001b[31mb")
        session.theme = TerminalTheme(0xFF111111.toInt(), 0xFFFAFAF5.toInt(), 0xFF0000FF.toInt(), 0xFFCCCCCC.toInt())
        val frame = session.frameOf()

        assertEquals(0xFFFAFAF5.toInt(), frame.background)
        assertEquals(0xFF111111.toInt(), frame.fg[frame.index(0, 0)])
        assertNotEquals(0xFF111111.toInt(), frame.fg[frame.index(1, 0)])
    }

    private fun assertNotEquals(unexpected: Int, actual: Int) = assertFalse("$actual", unexpected == actual)

    @Test
    fun aWideCharacterTakesTwoCellsAndSelectsAsOne() {
        val frame = terminal(10, 1, "你好a").frameOf()

        assertEquals(2, frame.width(frame.index(0, 0)))
        assertEquals(0, frame.width(frame.index(1, 0)))
        assertEquals("你好a", frame.rowText(0).trimEnd())

        // A finger on the tail of a wide character selects the character.
        val selection = TerminalSelection(frame, 1, 0)
        selection.word()
        assertEquals("你好a", selection.text())
    }

    @Test
    fun aSnapshotFromRexRestoresThePaneAndTheStreamAfterItContinuesIt() {
        val stream = javaClass.getResourceAsStream("/rex-stream.bin")!!.readBytes()
        // Real stream from a Rex server: its first 1260 bytes are the GHOSTSNP snapshot.
        val session = requireNotNull(TerminalSession.restore(stream.copyOfRange(0, SNAPSHOT_BYTES))) { "did not restore" }
        sessions += session
        session.feedOutput(stream.copyOfRange(SNAPSHOT_BYTES, stream.size))
        val frame = session.frameOf()

        // The stream's Rex resize message, which the app's decoder takes out, would make this 93x31;
        // the app sizes the terminal itself, so the grid here is the snapshot's own.
        val text = frame.lines().joinToString("\n")
        assertTrue(text, text.contains("λ🦀"))
        assertTrue(text, text.contains("REPLY:during-gap"))
    }

    @Test
    fun aSnapshotThatIsNotOneDoesNotRestore() {
        assertNull(TerminalSession.restore(ByteArray(64) { 7 }))
    }

    @Test
    fun linesTheTerminalWrappedRunTogetherWhenSelected() {
        val frame = terminal(5, 3, "abcdefgh").frameOf()

        assertTrue(frame.wrapped(0))
        assertFalse(frame.wrapped(1))
        val selection = TerminalSelection(frame, 0, 0)
        selection.all()
        assertEquals("abcdefgh", selection.text())
    }

    @Test
    fun linesTheProgramEndedKeepTheirBreaksAndLoseTheirTrailingSpaces() {
        // Four lines into three rows: the first has scrolled off, and the viewport is what is left.
        val frame = terminal(8, 3, "ab\r\ncd  \r\n\r\nef").frameOf()
        val selection = TerminalSelection(frame, 0, 0)
        selection.all()

        assertEquals("cd\n\nef", selection.text())
    }

    @Test
    fun aLinkPrintedAcrossWrappedRowsIsOneLinkWithoutItsTrailingPunctuation() {
        val frame = terminal(20, 4, "see https://example.com/a/b?q=1 (docs).").frameOf()

        assertEquals("https://example.com/a/b?q=1", TerminalLinks.urlAt(frame, 5, 0))
        // The part of the address that wrapped onto the second row is still that address.
        assertEquals("https://example.com/a/b?q=1", TerminalLinks.urlAt(frame, 3, 1))
        assertNull("plain text is not a link", TerminalLinks.urlAt(frame, 1, 0))
        assertNull("nor is what follows it", TerminalLinks.urlAt(frame, 12, 1))
        assertNull("nor is the full stop after the bracket", TerminalLinks.urlAt(frame, 18, 1))
    }

    @Test
    fun onlyWebAddressesAreOffered() {
        assertEquals("https://a.example/x", TerminalLinks.webUrl("https://a.example/x"))
        assertEquals("https://www.example.com", TerminalLinks.webUrl("www.example.com"))
        assertNull(TerminalLinks.webUrl("javascript:alert(1)"))
        assertNull(TerminalLinks.webUrl("file:///etc/passwd"))
        assertNull(TerminalLinks.webUrl("https://"))
    }

    @Test
    fun aLinkTheProgramSetIsFoundByItsCell() {
        val session = terminal(30, 2, "\u001b]8;;https://example.com/set\u001b\\click\u001b]8;;\u001b\\ here")
        val frame = session.frameOf()

        assertTrue(frame.hasLink(frame.index(2, 0)))
        assertFalse(frame.hasLink(frame.index(8, 0)))
        assertEquals("https://example.com/set", session.hyperlinkAt(2, 0))
        assertNull(session.hyperlinkAt(8, 0))
    }

    @Test
    fun scrollingMovesTheViewportThroughTheHistoryAndBackToTheBottom() {
        val session = terminal(10, 3, (1..12).joinToString("\r\n") { "line$it" })
        assertTrue(session.frameOf().atBottom)
        assertEquals("line12", session.frameOf().rowText(2).trimEnd())

        session.scroll(-4)
        val back = session.frameOf()
        assertFalse(back.atBottom)
        assertEquals("line8", back.rowText(2).trimEnd())

        session.scrollToBottom()
        assertTrue(session.frameOf().atBottom)
    }

    @Test
    fun modesTheProgramSetAreReported() {
        assertFalse(terminal(10, 3).frameOf().alternateScreen)
        val frame = terminal(10, 3, "\u001b[?1049h\u001b[?1000h").frameOf()

        assertTrue(frame.alternateScreen)
        assertTrue(frame.mouseTracking)
    }

    @Test
    fun cursorShapeIsTheProgramsChoice() {
        assertEquals(TerminalFrame.CURSOR_BAR, terminal(10, 3, "\u001b[6 q").frameOf().cursorStyle)
        assertEquals(TerminalFrame.CURSOR_UNDERLINE, terminal(10, 3, "\u001b[4 q").frameOf().cursorStyle)
    }

    @Test
    fun resizingReflowsTheEmulator() {
        val session = terminal(10, 3, "abcdefghij")
        session.resize(5, 3, 8, 16)

        assertEquals(5, session.frameOf().columns)
        assertEquals("abcde", session.frameOf().rowText(0))
    }

    @Test
    fun keysAreEncodedInTheModesTheProgramSet() {
        val plain = terminal(10, 3)
        assertArrayEquals("\r".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_ENTER, false, 0, null))
        assertArrayEquals("\u007f".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_DEL, false, 0, null))
        assertArrayEquals("\u001b[A".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_DPAD_UP, false, 0, null))
        assertArrayEquals("a".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_A, false, 0, "a"))
        assertArrayEquals("\u0003".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_C, false, KeyEvent.META_CTRL_ON, "c"))
        assertArrayEquals("\u001bb".toByteArray(), plain.encodeKey(KeyEvent.KEYCODE_B, false, KeyEvent.META_ALT_ON, "b"))

        val application = terminal(10, 3, "\u001b[?1h")
        assertArrayEquals("\u001bOA".toByteArray(), application.encodeKey(KeyEvent.KEYCODE_DPAD_UP, false, 0, null))
    }

    @Test
    fun aKeyTheTerminalDoesNotNameIsLeftToTheCaller() {
        assertNull(terminal(10, 3).encodeKey(KeyEvent.KEYCODE_SHIFT_LEFT, false, 0, null))
    }

    @Test
    fun theMouseIsReportedOnlyToAProgramThatAskedForIt() {
        assertEquals(0, terminal(20, 5).encodeWheel(true, 3, 2, 8, 16).size)

        val mouse = terminal(20, 5, "\u001b[?1000h\u001b[?1006h")
        assertEquals("\u001b[<65;4;3M", String(mouse.encodeWheel(true, 3, 2, 8, 16)))
        assertEquals("\u001b[<64;4;3M", String(mouse.encodeWheel(false, 3, 2, 8, 16)))
        assertEquals("\u001b[<0;4;3M\u001b[<0;4;3m", String(mouse.encodeClick(3, 2, 8, 16)))
    }

    @Test
    fun typedInputGoesToTheTransportAndTheViewportFollowsIt() {
        val session = terminal(10, 3, (1..12).joinToString("\r\n") { "l$it" })
        val sent = StringBuilder()
        session.transport = object : TerminalSession.Transport {
            override fun sendInput(data: ByteArray) {
                sent.append(String(data))
            }

            override fun sendResize(columns: Int, rows: Int, widthPixels: Int, heightPixels: Int) = Unit
        }
        session.scroll(-5)
        assertFalse(session.frameOf().atBottom)

        session.sendText("é🦀")
        assertEquals("é🦀", sent.toString())
        assertTrue(session.frameOf().atBottom)

        session.scroll(-5)
        session.sendRaw("x".toByteArray())
        assertFalse("a mouse report does not move the viewport", session.frameOf().atBottom)
    }

    /** A Kitty graphics transmit-and-place of raw RGBA pixels, the shape `kitten icat` sends. */
    private fun kittyImage(id: Int, width: Int, height: Int, rgba: ByteArray, extra: String = ""): String =
        "\u001b_Ga=T,f=32,q=2,i=$id,s=$width,v=$height$extra;" + java.util.Base64.getEncoder().encodeToString(rgba) + "\u001b\\"

    @Test
    fun aKittyImageIsPlacedAtTheCursorAndItsPixelsCanBeRead() {
        val session = terminal(10, 5)
        // The terminal sizes images by its cells, so it needs their size in pixels first.
        session.resize(10, 5, 8, 16)
        val red = byteArrayOf(-1, 0, 0, -1)
        val halfGreen = byteArrayOf(0, -1, 0, -128)
        session.feedOutput(("\r\n" + kittyImage(7, 2, 1, red + halfGreen)).toByteArray())

        val image = session.frameOf().images.single()
        assertEquals(7, image.id)
        assertEquals(0, image.column)
        assertEquals(1, image.row)
        assertEquals(2, image.width)
        assertEquals(1, image.height)
        assertEquals(TerminalImage.LAYER_ABOVE_TEXT, image.layer)
        assertEquals(8, session.frameOf().cellWidthPx)
        assertEquals(16, session.frameOf().cellHeightPx)
        val pixels = requireNotNull(session.imagePixels(7)) { "no pixels" }
        assertEquals(2, pixels.width)
        assertEquals(1, pixels.height)
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0x8000FF00.toInt()), pixels.argb)
    }

    @Test
    fun aNegativeZPutsTheImageUnderTheText() {
        val session = terminal(10, 5)
        session.resize(10, 5, 8, 16)
        session.feedOutput(kittyImage(3, 1, 1, byteArrayOf(1, 2, 3, -1), ",z=-1").toByteArray())

        assertEquals(TerminalImage.LAYER_BELOW_TEXT, session.frameOf().images.single().layer)
    }

    @Test
    fun aDeletedImageLeavesTheFrame() {
        val session = terminal(10, 5)
        session.resize(10, 5, 8, 16)
        session.feedOutput(kittyImage(4, 1, 1, byteArrayOf(1, 2, 3, -1)).toByteArray())
        assertEquals(1, session.frameOf().images.size)

        session.feedOutput("\u001b_Ga=d,d=I,i=4,q=2\u001b\\".toByteArray())
        assertTrue(session.frameOf().images.isEmpty())
        assertNull(session.imagePixels(4))
    }

    @Test
    fun aClosedSessionHasNothingToShow() {
        val session = terminal(10, 3, "x")
        assertNotNull(session.frame())
        session.close()
        assertNull(session.frame())
        session.feedOutput("more".toByteArray())
    }

    private companion object {
        const val SNAPSHOT_BYTES = 1260
    }
}

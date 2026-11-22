package me.leaver.rexpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What full screen takes away from the terminal screen, and every way it gives it back. */
class TermChromeTest {
    @Test
    fun `the terminal opens with its bars`() {
        val chrome = TermChrome()
        assertTrue(chrome.topBar)
        assertTrue(chrome.keyBar(dockWanted = true))
        assertTrue(chrome.systemBars)
    }

    @Test
    fun `full screen hides the top bar, the key bar and the phone's bars together`() {
        val chrome = TermChrome().toggled()
        assertFalse(chrome.topBar)
        assertFalse(chrome.keyBar(dockWanted = true))
        assertFalse(chrome.systemBars)
    }

    @Test
    fun `the same toggle brings them all back`() {
        assertEquals(TermChrome(), TermChrome().toggled().toggled())
    }

    @Test
    fun `a key bar a desk keyboard took away stays away when full screen ends`() {
        assertFalse(TermChrome().keyBar(dockWanted = false))
        assertFalse(TermChrome(fullScreen = true).keyBar(dockWanted = false))
    }

    @Test
    fun `Back leaves full screen first, and only then the terminal`() {
        assertEquals(TermChrome(), TermChrome(fullScreen = true).back())
        assertNull("with the bars shown Back is the screen's own", TermChrome().back())
    }

    @Test
    fun `a double tap puts away only the keyboard its own first tap raised`() {
        assertFalse(keyboardStaysAfterDoubleTap(upBeforeFirstTap = false))
        assertTrue("a keyboard that was up keeps typing", keyboardStaysAfterDoubleTap(upBeforeFirstTap = true))
    }
}

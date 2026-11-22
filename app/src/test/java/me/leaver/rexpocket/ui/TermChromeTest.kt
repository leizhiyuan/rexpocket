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
    fun `a tall window keeps its bars and its pane switcher below the pane`() {
        for (ime in listOf(false, true)) {
            val chrome = TermChrome(short = false, imeUp = ime)
            assertTrue(chrome.topBar)
            assertFalse(chrome.cornerMenu)
            assertTrue(chrome.panesBelow)
            assertFalse(chrome.panesInTopBar)
        }
    }

    @Test
    fun `a short window moves the panes up and gives the top bar to the keyboard`() {
        val closed = TermChrome(short = true, imeUp = false)
        assertTrue(closed.topBar)
        assertTrue("never over the pane's text", closed.panesInTopBar)
        assertFalse(closed.panesBelow)
        assertFalse(closed.cornerMenu)

        val typing = TermChrome(short = true, imeUp = true)
        assertFalse("the key bar keeps its height", typing.topBar)
        assertFalse(typing.panesInTopBar)
        assertFalse(typing.panesBelow)
        assertTrue("the menu stays one tap away", typing.cornerMenu)
        assertTrue(typing.keyBar(dockWanted = true))
    }

    @Test
    fun `full screen hides everything whatever the window`() {
        for (short in listOf(false, true)) for (ime in listOf(false, true)) {
            val chrome = TermChrome(fullScreen = true, short = short, imeUp = ime)
            assertFalse(chrome.topBar)
            assertFalse(chrome.cornerMenu)
            assertFalse(chrome.panesBelow)
            assertFalse(chrome.panesInTopBar)
            assertFalse(chrome.keyBar(dockWanted = true))
        }
    }
}

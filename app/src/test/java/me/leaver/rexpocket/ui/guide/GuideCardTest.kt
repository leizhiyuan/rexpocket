package me.leaver.rexpocket.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the spotlight's card goes ([cardTop]): under the control when it fits in what the window
 * shows, over it otherwise, never behind the keyboard. The layer is the whole window, keyboard
 * included (the app draws edge to edge), so the keyboard's top is the bottom of what is shown.
 */
class GuideCardTest {
    private val margin = 16
    private val gap = 12
    private val window = 2400
    private val card = 300

    @Test
    fun underTheControlWhenThereIsRoom() {
        assertEquals(212, cardTop(100f, 200f, card, 0, window, margin, gap))
    }

    /** The Return key, just over a keyboard 900 px tall: the card goes over the key, where it is seen. */
    @Test
    fun overAKeyJustAboveTheKeyboard() {
        val keyboardTop = window - 900
        val y = cardTop(keyboardTop - 120f, keyboardTop - 20f, card, 0, keyboardTop, margin, gap)
        assertEquals(keyboardTop - 120 - gap - card, y)
    }

    /** The pane, as tall as what is shown: the card sits at the bottom of that, over the keyboard's top, not under it. */
    @Test
    fun aControlAsTallAsTheScreenPutsTheCardAboveTheKeyboard() {
        val keyboardTop = window - 900
        val y = cardTop(80f, keyboardTop - 10f, card, 0, keyboardTop, margin, gap)
        assertTrue("card from $y to ${y + card} runs behind the keyboard at $keyboardTop", y + card <= keyboardTop)
    }

    /** Over a control near the top, never under the status bar. */
    @Test
    fun notUnderTheStatusBar() {
        val statusBar = 100
        val y = cardTop(400f, window - 50f, card, statusBar, window, margin, gap)
        assertTrue("card at $y is under the status bar", y >= statusBar)
    }
}

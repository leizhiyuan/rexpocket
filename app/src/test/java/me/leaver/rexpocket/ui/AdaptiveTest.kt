package me.leaver.rexpocket.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a window is laid out as. The classes are the platform's - a phone, a small tablet or an
 * unfolded foldable, a tablet or a desk window - and a phone held sideways is wide but has no height
 * for two panes. This is the one part of the layout a computer can check: the rest is the screen.
 */
class AdaptiveTest {
    private fun window(width: WindowWidth, height: WindowHeight) = WindowSize(width, height)

    private val phone = window(WindowWidth.Compact, WindowHeight.Expanded)
    private val phoneSideways = window(WindowWidth.Expanded, WindowHeight.Compact)
    private val foldable = window(WindowWidth.Medium, WindowHeight.Expanded)
    private val tablet = window(WindowWidth.Expanded, WindowHeight.Expanded)

    @Test
    fun `a phone keeps one column, with or without a pane open`() {
        assertEquals(WindowLayout.OneColumn, layoutFor(phone, hasHosts = true, terminalOpen = false))
        assertEquals(WindowLayout.OneColumn, layoutFor(phone, hasHosts = true, terminalOpen = true))
    }

    @Test
    fun `a window too narrow for two panes holds the list to the width it reads at`() {
        assertEquals(WindowLayout.CappedColumn, layoutFor(foldable, hasHosts = true, terminalOpen = false))
    }

    @Test
    fun `a pane takes the whole window when there is no room beside it`() {
        assertEquals(WindowLayout.OneColumn, layoutFor(foldable, hasHosts = true, terminalOpen = true))
    }

    @Test
    fun `a tablet or a desk window keeps the list beside the pane`() {
        assertEquals(WindowLayout.ListDetail, layoutFor(tablet, hasHosts = true, terminalOpen = false))
        assertEquals(WindowLayout.ListDetail, layoutFor(tablet, hasHosts = true, terminalOpen = true))
    }

    @Test
    fun `a phone held sideways keeps one column, wide as it is`() {
        // The width class alone would give it two panes; the height class is what says no.
        assertEquals(WindowLayout.CappedColumn, layoutFor(phoneSideways, hasHosts = true, terminalOpen = false))
        assertEquals(WindowLayout.OneColumn, layoutFor(phoneSideways, hasHosts = true, terminalOpen = true))
    }

    @Test
    fun `nothing to list leaves nothing to put beside a pane`() {
        assertEquals(WindowLayout.CappedColumn, layoutFor(tablet, hasHosts = false, terminalOpen = false))
    }

    @Test
    fun `the list takes two fifths of the window, held between a title and the terminal's room`() {
        assertEquals(336.dp, listPaneWidth(840.dp))
        assertEquals(384.dp, listPaneWidth(960.dp))
        assertEquals(420.dp, listPaneWidth(1280.dp))
        assertEquals(420.dp, listPaneWidth(2560.dp))
        assertEquals(320.dp, listPaneWidth(600.dp))
    }
}

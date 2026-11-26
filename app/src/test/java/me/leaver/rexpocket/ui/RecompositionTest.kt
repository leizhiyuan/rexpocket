package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.core.PaneProgress
import me.leaver.rexpocket.core.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What changes on every frame - the keyboard sliding, the cursor's row moving as a program prints,
 * an indeterminate progress bar's sweep - is read in layout or draw, so the screen around it is not
 * composed again for it. Each test counts the compositions of the scope that holds the thing.
 */
@RunWith(AndroidJUnit4::class)
class RecompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** A keyboard inset that a test moves, as the IME animation does frame by frame. */
    private class MovingIme : WindowInsets {
        var bottom by mutableIntStateOf(0)
        override fun getLeft(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getTop(density: Density) = 0
        override fun getRight(density: Density, layoutDirection: LayoutDirection) = 0
        override fun getBottom(density: Density) = bottom
    }

    @Test
    fun theKeyboardSlideAndTheCursorMoveThePaneWithoutComposingTheScreen() {
        val ime = MovingIme()
        val slide = PaneSlide()
        var compositions = 0
        compose.setContent {
            SideEffect { compositions++ }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(100.dp).imeSlide(ime, slide).testTag("pane"))
            }
        }
        compose.runOnIdle { slide.paneHeightPx = 1000 }
        fun top() = compose.onNodeWithTag("pane").fetchSemanticsNode().positionInRoot.y
        assertEquals(0f, top(), 0.5f)

        // The keyboard opening, a frame at a time, over a full screen.
        for (step in 1..20) {
            compose.runOnIdle { ime.bottom = step * 20 }
            compose.waitForIdle()
        }
        assertEquals(-400f, top(), 0.5f)
        // The program's cursor moving up the rows: the pane follows it.
        for (row in 20 downTo 5) {
            compose.runOnIdle { slide.cursorTopPx = row * 10 }
            compose.waitForIdle()
        }
        assertEquals(-50f, top(), 0.5f)
        compose.runOnIdle { slide.drawnBottomPx = 650 }
        compose.waitForIdle()
        assertEquals(-50f, top(), 0.5f)
        assertEquals("composed once, re-placed every step", 1, compositions)
    }

    /** How many times the window's Recomposer has recomposed something and applied it. */
    private fun recompositions(): Long = Recomposer.runningRecomposers.value.sumOf { it.changeCount }

    @Test
    fun anIndeterminateBarSweepsWithoutComposingAgain() {
        val busy = PaneProgress(ProgressState.Indeterminate, null)
        compose.mainClock.autoAdvance = false
        compose.setContent { ProgressLine(busy, Modifier.size(200.dp, 4.dp)) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val before = recompositions()
        repeat(30) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        // The sweep moves in draw alone.
        assertEquals("recompositions over 30 frames of the sweep", before, recompositions())
    }

    private fun opsCalling(onRetry: (String) -> Unit) = HomeOps(
        connect = {}, retry = onRetry, cancel = {}, disconnect = {}, newSession = {}, newTab = { _, _ -> },
        openSession = { _, _ -> }, openTab = { _, _, _ -> }, openPane = { _, _, _, _ -> }, manage = { _, _ -> },
        foldHost = { _, _ -> }, foldSession = { _, _, _ -> }, foldSessions = { _, _, _ -> }, editHost = {},
        copyAddress = {}, tailnetDetails = {},
    )

    /**
     * The home's ops stay one instance while the home recomposes, so the rows handed them can skip;
     * a button still runs what the home built last, and new seen marks (which their queries read)
     * make new ops.
     */
    @Test
    fun theHomeKeepsItsOpsAndTheyRunTheLatestActions() {
        var round by mutableIntStateOf(0)
        var marks by mutableStateOf(mapOf("a" to "1"))
        val retried = mutableListOf<String>()
        val seen = mutableListOf<HomeOps>()
        compose.setContent {
            val r = round
            seen += rememberHomeOps(marks, opsCalling { retried += "$it@$r" })
        }
        compose.runOnIdle { round = 1 }
        compose.runOnIdle { round = 2 }
        compose.runOnIdle { seen.last().retry("mac") }
        assertEquals(3, seen.size)
        assertSame(seen[0], seen[1])
        assertSame(seen[0], seen[2])
        assertEquals(listOf("mac@2"), retried)
        compose.runOnIdle { marks = mapOf("a" to "2") }
        compose.waitForIdle()
        assertNotSame(seen[0], seen.last())
    }
}

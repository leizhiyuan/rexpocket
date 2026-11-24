package me.leaver.rexpocket.ui

import me.leaver.rexpocket.core.BlockInfo
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.WindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the terminal screen has nothing left to show and should return to the session list.
 * Destroying one session while another exists clears `view` briefly while the next session opens;
 * that transition must keep the terminal open rather than bouncing the user back to Home.
 */
class TerminalRouteTest {
    private fun view(sessionId: String, blocks: List<String>): SessionView = SessionView(
        revision = 1,
        sessionId = sessionId,
        label = sessionId,
        activeWindowId = "win:1",
        windows = listOf(
            WindowInfo(
                id = "win:1",
                label = "tab",
                focusedBlockId = blocks.firstOrNull(),
                zoomedBlockId = null,
                blocks = blocks.map { BlockInfo(it, it, null, null, null) },
            ),
        ),
    )

    @Test
    fun `leaves terminal when live and no active session remains`() {
        val state = PocketState(phase = Phase.Live, activeSessionId = null, view = null)
        assertTrue(shouldLeaveTerminal(state))
    }

    @Test
    fun `leaves terminal when live and the active session view has no panes`() {
        val state = PocketState(
            phase = Phase.Live,
            activeSessionId = "session:1",
            view = view("session:1", emptyList()),
        )
        assertTrue(shouldLeaveTerminal(state))
    }

    @Test
    fun `stays on terminal while switching to the next session after destroy`() {
        val state = PocketState(
            phase = Phase.Live,
            activeSessionId = "session:2",
            view = null,
        )
        assertFalse(shouldLeaveTerminal(state))
    }

    @Test
    fun `stays on terminal while view still belongs to a previous session`() {
        val state = PocketState(
            phase = Phase.Live,
            activeSessionId = "session:2",
            view = view("session:1", emptyList()),
        )
        assertFalse(shouldLeaveTerminal(state))
    }

    @Test
    fun `stays on terminal when the active session has a focused pane`() {
        val state = PocketState(
            phase = Phase.Live,
            activeSessionId = "session:1",
            view = view("session:1", listOf("block:1")),
        )
        assertFalse(shouldLeaveTerminal(state))
    }

    @Test
    fun `stays on terminal while reconnecting`() {
        val state = PocketState(phase = Phase.Reconnecting, activeSessionId = null, view = null)
        assertFalse(shouldLeaveTerminal(state))
    }
}

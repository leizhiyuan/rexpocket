package me.leaver.rexpocket.core

import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the person sees when a tab or pane closes under the phone, and when they move on from it.
 * Each scenario is a sequence a person reported or can make: close the tab or pane on the desk or
 * on the phone, then show another tab of the same session, with and without the phone holding the
 * pane. They assert the screen, not the protocol: no line on the banner, the closed note where the
 * pane was, and the next tab on screen and held by the phone without anyone tapping Take over.
 *
 * [ScriptedRexLifecycleTest] runs them against [ScriptedRex] on every build; [RealRexLifecycleTest]
 * runs the same ones against a real Rex when one listens on REX_TEST_HOST:REX_TEST_PORT.
 */
abstract class PaneLifecycleScenarios {
    protected abstract fun rex(): RexUnderTest

    private fun scenario(body: suspend Scenario.() -> Unit) = Scenario.run(rex(), body)

    /** Nothing reached the banner, and nothing does for a while after: no error flashes late. */
    private suspend fun Scenario.quietFor(ms: Long = 1_500) {
        delay(ms)
        assertEquals("the banner showed errors", emptyList<String>(), screen.errors())
        assertNull("the banner shows ${screen.banner()}", screen.banner())
    }

    @Test
    fun theDeskClosesTheShownTabAndThePhoneShowsTheTabThatIsLeft() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        val (window2, block2) = tabs[1]
        phone.openSession(sid, windowId = window2)
        showsAndHolds(sid, block2)

        desk.closeTab(sid, window2)
        val closed = until("the closed note") { it.closed != null }.closed
        assertEquals(ClosedPane(sid, block2, tabClosed = true), closed)
        quietFor(500)

        // "Show <tab>" on the note.
        phone.leaveClosedPane()
        showsAndHolds(sid, tabs[0].second)
        quietFor()
    }

    @Test
    fun theDeskClosesTheShownTabAndThePhoneOpensAnotherTabFromTheList() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 3)
        phone.openSession(sid, windowId = tabs[1].first)
        showsAndHolds(sid, tabs[1].second)

        desk.closeTab(sid, tabs[1].first)
        until("the closed note") { it.closed != null }
        // The tab list, not the note's own button.
        phone.focusWindow(tabs[2].first)
        showsAndHolds(sid, tabs[2].second)
        quietFor()
    }

    @Test
    fun theDeskClosesTheShownTabAndThePhoneMakesANewTab() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        phone.openSession(sid, windowId = tabs[1].first)
        showsAndHolds(sid, tabs[1].second)

        desk.closeTab(sid, tabs[1].first)
        until("the closed note") { it.closed != null }
        phone.newWindow()
        val made = until("the new tab on screen") { st -> screen.shown(st).let { it != null && it != tabs[0].second } }
        showsAndHolds(sid, screen.shown(made)!!)
        quietFor()
    }

    @Test
    fun theDeskClosesTheSessionsOnlyTabAndThePhoneMakesANewOne() = scenario {
        // A session outlives its last window (Rex's reference: session.create makes one with no
        // windows, and no close ends the session), so the note stands and a new tab goes there.
        val (sid, tabs) = desk.newSession(tabs = 1)
        phone.openSession(sid)
        showsAndHolds(sid, tabs[0].second)

        desk.closeTab(sid, tabs[0].first)
        val closed = until("the closed note") { it.closed != null }.closed
        assertEquals(ClosedPane(sid, tabs[0].second, tabClosed = true), closed)
        quietFor(500)

        phone.newWindow()
        val made = until("the new tab on screen") { st -> screen.shown(st).let { it != null && it != tabs[0].second } }
        assertEquals(sid, made.activeSessionId)
        showsAndHolds(sid, screen.shown(made)!!)
        quietFor()
    }

    @Test
    fun theDeskClosesAReleasedPaneAndThePhoneTakesTheNextTabOnSight() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        val (window2, block2) = tabs[1]
        phone.openSession(sid, windowId = window2)
        showsAndHolds(sid, block2)
        phone.releasePane()
        until("released") { it.released }

        desk.closeTab(sid, window2)
        until("the closed note") { it.closed != null }
        phone.leaveClosedPane()
        // The release belonged to the pane that closed; the next one is taken as any pane is.
        showsAndHolds(sid, tabs[0].second)
        quietFor()
    }

    @Test
    fun theDeskTakesThePaneThenClosesItAndThePhoneTakesTheNextTab() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        val (window2, block2) = tabs[1]
        phone.openSession(sid, windowId = window2)
        showsAndHolds(sid, block2)
        desk.take(sid, block2)
        until("left to the desk", timeoutMs = 15_000) { it.released }

        desk.closeTab(sid, window2)
        until("the closed note") { it.closed != null }
        phone.leaveClosedPane()
        showsAndHolds(sid, tabs[0].second)
        quietFor()
    }

    @Test
    fun theDeskClosesTheShownPaneOfASplit() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 1)
        val (_, left) = tabs[0]
        val right = desk.split(sid, left)
        phone.openSession(sid, rememberedBlock = right)
        showsAndHolds(sid, right)

        desk.closePane(sid, right)
        val closed = until("the closed note") { it.closed != null }.closed
        assertEquals(ClosedPane(sid, right, tabClosed = false), closed)
        phone.leaveClosedPane()
        showsAndHolds(sid, left)
        quietFor()
    }

    @Test
    fun thePhoneClosesTheShownTabAndMovesOn() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        phone.openSession(sid, windowId = tabs[1].first)
        showsAndHolds(sid, tabs[1].second)

        phone.closeWindow()
        showsAndHolds(sid, tabs[0].second)
        assertNull("closed here, so nothing to explain", phone.state.value.closed)
        quietFor()
    }

    @Test
    fun thePhoneClosesTheShownPaneAndMovesOn() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 1)
        val (_, left) = tabs[0]
        val right = desk.split(sid, left)
        phone.openSession(sid, rememberedBlock = right)
        showsAndHolds(sid, right)

        phone.closeBlock()
        showsAndHolds(sid, left)
        assertNull(phone.state.value.closed)
        quietFor()
    }

    @Test
    fun thePhoneClosesAPaneTheDeskClosedFirst() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        phone.openSession(sid, windowId = tabs[1].first)
        showsAndHolds(sid, tabs[1].second)
        // Both close it; the phone's request reaches Rex second and is refused.
        desk.closeTab(sid, tabs[1].first)
        runCatching { phone.closeWindow(tabs[1].first) }.exceptionOrNull()?.let { throw AssertionError("closing a closed tab failed: $it") }
        quietFor()
    }

    @Test
    fun aReconnectKeepsThePaneWithoutAnError() = scenario {
        val (sid, tabs) = desk.newSession(tabs = 2)
        phone.openSession(sid, windowId = tabs[1].first)
        showsAndHolds(sid, tabs[1].second)

        rex.drop(phone)
        until("live again") { it.phase == Phase.Live && it.clientId != null }
        showsAndHolds(sid, tabs[1].second, timeoutMs = 15_000)
        assertFalse(phone.state.value.released)
        quietFor()
    }
}

class ScriptedRexLifecycleTest : PaneLifecycleScenarios() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    /** The errors that are real stay on screen: Rex refusing a pane that is still there. */
    @Test
    fun aPaneRexWillNotHandOverIsStillAnErrorUntilItIsHeld() {
        val world = FakeRexUnderTest()
        Scenario.run(world) {
            val (sid, tabs) = desk.newSession(tabs = 2)
            world.rex.refuse("session.own_block")
            phone.openSession(sid, windowId = tabs[1].first)
            val shown = until("the refusal on the banner") { it.notice != null }
            assertEquals(Trouble.Rejected, shown.notice!!.problem.trouble)
            assertEquals(tabs[1].second, screen.shown())
            // Once Rex lets the phone have it, the line goes by itself.
            world.rex.allow("session.own_block")
            showsAndHolds(sid, tabs[1].second, timeoutMs = 15_000)
            until("the banner clears") { it.notice == null }
        }
    }
}

class RealRexLifecycleTest : PaneLifecycleScenarios() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }
}

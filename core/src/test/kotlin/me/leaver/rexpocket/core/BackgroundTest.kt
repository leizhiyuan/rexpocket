package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background mode's choices: which hosts connect at launch and after Android restarted the app
 * without a screen, which are let go while away, and each host's notifications over the defaults.
 * See docs/design/multi-host.md, "Background".
 */
class BackgroundTest {
    private val saved = listOf("a", "b", "c", "d")

    @Test
    fun aRestartWithoutAScreenConnectsTheKeptHostsAndTheAlwaysConnected() {
        val bg = BackgroundHosts()
            .with("b", HostBackground(stay = false))
            .with("c", HostBackground(always = true))
        // a and b were connected at exit; c is always connected; d was never connected.
        assertEquals(listOf("a", "c"), bg.inBackground(on = true, connected = listOf("a", "b"), saved = saved))
        // Background mode off: nothing connects without a screen.
        assertEquals(emptyList<String>(), bg.inBackground(on = false, connected = listOf("a", "b"), saved = saved))
    }

    @Test
    fun aHostDeletedSinceIsNotConnected() {
        val bg = BackgroundHosts().with("gone", HostBackground(always = true))
        assertEquals(listOf("a"), bg.inBackground(on = true, connected = listOf("a", "gone"), saved = saved))
        assertEquals(listOf("a"), bg.atLaunch(last = "gone", connected = listOf("a"), saved = saved))
        assertTrue(bg.keep(saved).hosts.isEmpty())
    }

    @Test
    fun launchConnectsTheLastHostFirstThenTheConnectedThenTheAlwaysConnected() {
        val bg = BackgroundHosts().with("d", HostBackground(always = true)).with("b", HostBackground(stay = false))
        // b is not kept in the background, but it was connected at exit: a launch brings it back.
        assertEquals(listOf("b", "a", "d"), bg.atLaunch(last = "b", connected = listOf("a", "b"), saved = saved))
        // Disconnected (so not in connected), an always-connected host still connects.
        assertEquals(listOf("d"), bg.atLaunch(last = null, connected = emptyList(), saved = saved))
    }

    @Test
    fun onlyHostsNotKeptAreLetGoAndNeverTheOneOnScreen() {
        val bg = BackgroundHosts()
            .with("a", HostBackground(stay = false))
            .with("b", HostBackground(stay = false))
            .with("c", HostBackground(stay = false, always = true))
        assertEquals(listOf("b"), bg.toPark(listOf("a", "b", "c", "d"), onScreen = "a"))
    }

    @Test
    fun aHostsChoiceReplacesTheDefaultAndTheRestFollowIt() {
        val base = AlertPrefs()
        val host = HostBackground().with(AlertEvent.Done, false, base).with(AlertEvent.Bell, true, base)
        val prefs = host.prefs(base)
        assertFalse(prefs.done)
        assertTrue(prefs.bells)
        assertTrue(prefs.waiting)
        // The default changes: what the host did not set follows it, what it set stays.
        val quieter = base.copy(waiting = false, done = true)
        assertFalse(host.prefs(quieter).waiting)
        assertFalse(host.prefs(quieter).done)
        // Setting a host's choice back to the default's forgets it.
        assertEquals(emptyMap<AlertEvent, Boolean>(), host.with(AlertEvent.Done, true, base).with(AlertEvent.Bell, false, base).overrides)
    }

    @Test
    fun aSilencedHostPostsNothingButKeepsItsChoices() {
        val base = AlertPrefs()
        val host = HostBackground(notify = false).with(AlertEvent.Bell, true, base)
        val prefs = host.prefs(base)
        assertTrue(AlertEvent.entries.none { prefs.on(it) })
        assertTrue("vibrating on screen is not the host's", prefs.vibrate)
        assertTrue(host.copy(notify = true).prefs(base).bells)
    }

    @Test
    fun aHostsChoicesDecideItsAlerts() {
        val bg = BackgroundHosts().with("quiet", HostBackground().with(AlertEvent.Waiting, false, AlertPrefs()))
        val of = bg.prefsOf(AlertPrefs())
        val blocked = mapOf("p" to ProgramRecord(id = "", state = "blocked", kind = "question", app = "claude", title = null, msg = "?", progress = null))
        assertEquals(listOf<AlertMove>(AlertMove.Post("p", AlertKind.Waiting, loud = true)), AlertPlanner().status(blocked, away = true, prefs = of("loud")))
        assertEquals(emptyList<AlertMove>(), AlertPlanner().status(blocked, away = true, prefs = of("quiet")))
        // A message and a bell follow the host's choices too.
        val silenced = BackgroundHosts().with("s", HostBackground(notify = false)).prefsOf(AlertPrefs(bells = true))
        assertNull(AlertPlanner().message("p", away = true, prefs = silenced("s")))
        assertEquals(BellMove.Nothing, AlertPlanner().bell("p", onScreen = false, away = true, prefs = silenced("s")))
        assertEquals(BellMove.Notify, AlertPlanner().bell("p", onScreen = false, away = true, prefs = silenced("other")))
    }

    @Test
    fun defaultsAreNotStored() {
        val bg = BackgroundHosts().with("a", HostBackground(stay = false)).with("a", HostBackground())
        assertTrue(bg.hosts.isEmpty())
    }
}

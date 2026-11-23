package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which notification each status, desktop notification and bell makes; see notifications.md. */
class AlertPlannerTest {
    private var now = 1_000_000L
    private val planner = AlertPlanner(clock = { now })
    private val prefs = AlertPrefs()

    private fun record(state: String, kind: String? = null, msg: String? = null) =
        ProgramRecord(id = "", state = state, kind = kind, app = "claude", title = null, msg = msg, progress = null)

    private fun post(block: String, kind: AlertKind, loud: Boolean = true) = AlertMove.Post(block, kind, loud)

    @Test
    fun eachStateHasItsKind() {
        assertEquals(
            listOf(post("a", AlertKind.Waiting), post("b", AlertKind.Failed), post("c", AlertKind.Done)),
            planner.status(mapOf("a" to record("blocked"), "b" to record("error"), "c" to record("done"), "d" to record("working")), away = true, prefs),
        )
    }

    @Test
    fun onScreenNothingIsPostedAndTheReportIsNotNewsLater() {
        assertEquals(emptyList<AlertMove>(), planner.status(mapOf("a" to record("done")), away = false, prefs))
        assertEquals(emptyList<AlertMove>(), planner.status(mapOf("a" to record("done")), away = true, prefs))
    }

    @Test
    fun aKindSwitchedOffIsNotPostedAndClearsTheAlertBeforeIt() {
        val noDone = prefs.copy(done = false)
        assertEquals(listOf(post("a", AlertKind.Waiting)), planner.status(mapOf("a" to record("blocked")), away = true, noDone))
        // The agent got its answer elsewhere and finished: the question is out of date.
        assertEquals(listOf(AlertMove.Cancel("a")), planner.status(mapOf("a" to record("done")), away = true, noDone))
    }

    @Test
    fun aRecordThatGoesCancelsItsAlert() {
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        assertEquals(listOf(AlertMove.Cancel("a")), planner.status(mapOf("a" to record("working")), away = true, prefs))
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        assertEquals(listOf(AlertMove.Cancel("a")), planner.status(emptyMap(), away = true, prefs))
    }

    @Test
    fun aMessageThenAStatusAboutTheSameEventIsOneQuietReplace() {
        assertEquals(post("a", AlertKind.Message), planner.message("a", away = true, prefs))
        now += 2_000
        assertEquals(listOf(post("a", AlertKind.Waiting, loud = false)), planner.status(mapOf("a" to record("blocked", "permission")), away = true, prefs))
    }

    @Test
    fun aStatusThenAMessageAboutTheSameEventIsDropped() {
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        now += 3_000
        assertNull(planner.message("a", away = true, prefs))
        // Much later it is news of its own.
        now += AlertPlanner.FOLD_MS
        assertEquals(post("a", AlertKind.Message), planner.message("a", away = true, prefs))
    }

    @Test
    fun messagesFromOnePaneAreRateLimited() {
        assertEquals(post("a", AlertKind.Message), planner.message("a", away = true, prefs))
        now += 1_000
        assertEquals(post("a", AlertKind.Message, loud = false), planner.message("a", away = true, prefs))
        assertEquals(post("b", AlertKind.Message), planner.message("b", away = true, prefs))
        now += AlertPlanner.QUIET_MS
        assertEquals(post("a", AlertKind.Message), planner.message("a", away = true, prefs))
    }

    @Test
    fun messagesNeedTheAppAwayAndTheirSwitch() {
        assertNull(planner.message("a", away = false, prefs))
        assertNull(planner.message("a", away = true, prefs.copy(messages = false)))
    }

    @Test
    fun aMessageOutlivesTheStatusGoing() {
        planner.message("a", away = true, prefs)
        now += AlertPlanner.FOLD_MS
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        now += AlertPlanner.FOLD_MS
        planner.message("a", away = true, prefs)
        // The status settles, but what shows now is the program's newer message.
        assertEquals(emptyList<AlertMove>(), planner.status(emptyMap(), away = true, prefs))
    }

    @Test
    fun aBellOnScreenVibratesOnceASecond() {
        assertEquals(BellMove.Vibrate, planner.bell("a", onScreen = true, away = false, prefs))
        now += 200
        assertEquals(BellMove.Nothing, planner.bell("a", onScreen = true, away = false, prefs))
        now += AlertPlanner.BELL_GAP_MS
        assertEquals(BellMove.Vibrate, planner.bell("a", onScreen = true, away = false, prefs))
        assertEquals(BellMove.Nothing, planner.bell("b", onScreen = true, away = false, prefs.copy(vibrate = false)))
    }

    @Test
    fun aBellOffScreenNotifiesOnlyWhenAskedAndOnce() {
        assertEquals(BellMove.Nothing, planner.bell("a", onScreen = false, away = false, prefs.copy(bells = true)))
        assertEquals(BellMove.Nothing, planner.bell("a", onScreen = false, away = true, prefs))
        val bells = prefs.copy(bells = true)
        assertEquals(BellMove.Notify, planner.bell("a", onScreen = false, away = true, bells))
        now += 5_000
        assertEquals(BellMove.Nothing, planner.bell("a", onScreen = false, away = true, bells))
    }

    @Test
    fun aBellRightAfterAStatusAlertIsPartOfIt() {
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        assertEquals(BellMove.Nothing, planner.bell("a", onScreen = false, away = true, prefs.copy(bells = true)))
    }

    @Test
    fun openingThePaneEndsItsAlert() {
        planner.status(mapOf("a" to record("blocked")), away = true, prefs)
        assertEquals(AlertMove.Cancel("a"), planner.seen("a"))
        assertNull(planner.seen("a"))
        // The same request is not raised again: it was seen.
        assertEquals(emptyList<AlertMove>(), planner.status(mapOf("a" to record("blocked")), away = true, prefs))
    }

    @Test
    fun programTextIsMadePlain() {
        assertEquals("rm -rf build? [31myes", plainText("rm -rf\tbuild?\n\u001b[31myes"))
        assertEquals("evil", plainText("‮evil​"))
        assertNull(plainText(" \n\u0007 "))
        assertEquals("abcd…", plainText("abcdefgh", max = 5))
    }
}

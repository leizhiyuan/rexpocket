package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pane's gestures, told apart by time and distance alone, for every choice of settings. */
class GesturesTest {
    private val base = TapTiming(doubleTaps = true, waitForDoubleTap = false, doubleTapMs = 300, longPressMs = 500, touchSlop = 10f, doubleTapSlop = 100f)

    /** Runs [steps] and the timer at every deadline up to [until], collecting what came out. */
    private fun run(timing: TapTiming, until: Long = 5_000, steps: TapGestures.(MutableList<Gesture>) -> Unit): List<Gesture> {
        val g = TapGestures(timing)
        val out = mutableListOf<Gesture>()
        g.steps(out)
        while (true) {
            val d = g.deadline ?: break
            if (d > until) break
            out += g.timer(d + 1)
        }
        return out
    }

    private fun TapGestures.tap(out: MutableList<Gesture>, at: Long, x: Float = 50f, y: Float = 50f) {
        out += down(at, x, y)
        out += up(at + 50, x, y)
    }

    @Test
    fun aTapActsAsItLiftsWhenNotWaiting() {
        val g = TapGestures(base)
        assertTrue(g.down(0, 50f, 50f).isEmpty())
        assertEquals(listOf(Gesture.Tap(50f, 50f)), g.up(50, 50f, 50f))
        // Afterwards only the double-tap window is open; closing it says nothing more.
        assertEquals(350L, g.deadline)
        assertTrue(g.timer(351).isEmpty())
        assertNull(g.deadline)
    }

    @Test
    fun aDoubleTapFollowsTheFirstTapThatAlreadyActed() {
        val out = run(base) { tap(it, 0); tap(it, 200, 60f, 55f) }
        assertEquals(listOf(Gesture.Tap(50f, 50f), Gesture.DoubleTap(60f, 55f, firstTapActed = true)), out)
    }

    @Test
    fun waitingMakesTheFirstTapOfADoubleTapActNever() {
        val out = run(base.copy(waitForDoubleTap = true)) { tap(it, 0); tap(it, 200) }
        assertEquals(listOf(Gesture.DoubleTap(50f, 50f, firstTapActed = false)), out)
    }

    @Test
    fun waitingMakesASingleTapActOnceTheWindowPasses() {
        val g = TapGestures(base.copy(waitForDoubleTap = true))
        g.down(0, 50f, 50f)
        assertTrue("not yet", g.up(50, 50f, 50f).isEmpty())
        assertEquals(350L, g.deadline)
        assertTrue("still inside the window", g.timer(340).isEmpty())
        assertEquals(listOf(Gesture.Tap(50f, 50f)), g.timer(351))
        assertNull(g.deadline)
    }

    @Test
    fun aWaitingTapGoesOutBeforeANewPressThatComesTooLate() {
        val g = TapGestures(base.copy(waitForDoubleTap = true))
        g.down(0, 50f, 50f)
        g.up(50, 50f, 50f)
        // The timer was late; the next press still finds the tap and lets it out first.
        assertEquals(listOf(Gesture.Tap(50f, 50f)), g.down(1_000, 50f, 50f))
        assertEquals(emptyList<Gesture>(), g.up(1_050, 50f, 50f))
    }

    @Test
    fun tapsFurtherApartInTimeAreTwoTaps() {
        for (wait in listOf(false, true)) {
            val out = run(base.copy(waitForDoubleTap = wait)) { tap(it, 0); tap(it, 1_000) }
            assertEquals("wait=$wait", listOf(Gesture.Tap(50f, 50f), Gesture.Tap(50f, 50f)), out)
        }
    }

    @Test
    fun tapsFarApartOnScreenAreTwoTaps() {
        for (wait in listOf(false, true)) {
            val out = run(base.copy(waitForDoubleTap = wait)) { tap(it, 0); tap(it, 200, 400f, 50f) }
            assertEquals("wait=$wait", listOf(Gesture.Tap(50f, 50f), Gesture.Tap(400f, 50f)), out)
        }
    }

    @Test
    fun withDoubleTapsOffEveryTapActsAtOnceWaitingOrNot() {
        for (wait in listOf(false, true)) {
            val g = TapGestures(base.copy(doubleTaps = false, waitForDoubleTap = wait))
            g.down(0, 50f, 50f)
            assertEquals(listOf(Gesture.Tap(50f, 50f)), g.up(50, 50f, 50f))
            g.down(150, 50f, 50f)
            assertEquals(listOf(Gesture.Tap(50f, 50f)), g.up(200, 50f, 50f))
        }
    }

    @Test
    fun aPressHeldStillIsALongPressAndNothingElse() {
        for (doubleTaps in listOf(true, false)) for (wait in listOf(false, true)) {
            val g = TapGestures(base.copy(doubleTaps = doubleTaps, waitForDoubleTap = wait))
            g.down(0, 50f, 50f)
            assertEquals(500L, g.deadline)
            assertTrue(g.timer(499).isEmpty())
            assertEquals(listOf(Gesture.LongPress(50f, 50f)), g.timer(500))
            assertTrue("its lift is not a tap", g.up(900, 50f, 50f).isEmpty())
            assertNull(g.deadline)
        }
    }

    @Test
    fun aLiftAfterTheTimeIsStillALongPressWhenTheTimerWasLate() {
        val g = TapGestures(base)
        g.down(0, 50f, 50f)
        assertEquals(listOf(Gesture.LongPress(50f, 50f)), g.up(800, 50f, 50f))
    }

    @Test
    fun aSecondPressHeldIsALongPressAndDropsAWaitingTap() {
        val waiting = run(base.copy(waitForDoubleTap = true)) {
            tap(it, 0)
            it += down(200, 50f, 50f)
        }
        assertEquals(listOf(Gesture.LongPress(50f, 50f)), waiting)

        val acting = run(base) {
            tap(it, 0)
            it += down(200, 50f, 50f)
        }
        assertEquals(listOf(Gesture.Tap(50f, 50f), Gesture.LongPress(50f, 50f)), acting)
    }

    @Test
    fun aDragIsNoGestureHere() {
        for (wait in listOf(false, true)) {
            val out = run(base.copy(waitForDoubleTap = wait)) {
                it += down(0, 50f, 50f)
                it += move(20, 50f, 90f)
                it += up(60, 50f, 120f)
            }
            assertEquals("wait=$wait", emptyList<Gesture>(), out)
        }
    }

    @Test
    fun aSmallTremorIsStillATap() {
        val out = run(base) {
            it += down(0, 50f, 50f)
            it += move(20, 55f, 53f)
            it += up(60, 55f, 53f)
        }
        assertEquals(listOf(Gesture.Tap(55f, 53f)), out)
    }

    @Test
    fun aDraggedSecondPressIsNotADoubleTap() {
        val out = run(base.copy(waitForDoubleTap = true)) {
            tap(it, 0)
            it += down(200, 50f, 50f)
            it += move(220, 50f, 200f)
            it += up(260, 50f, 200f)
        }
        assertEquals(emptyList<Gesture>(), out)
    }

    @Test
    fun aCancelledPressIsNothing() {
        val g = TapGestures(base)
        g.down(0, 50f, 50f)
        g.cancel()
        assertNull(g.deadline)
        assertTrue(g.up(50, 50f, 50f).isEmpty())
    }

    @Test
    fun theTimesComeFromTheSettings() {
        // A longer window makes a slow second tap a double tap; a shorter one makes it two taps.
        val slow = run(base.copy(doubleTapMs = 600)) { tap(it, 0); tap(it, 500) }
        assertEquals(Gesture.DoubleTap(50f, 50f, firstTapActed = true), slow.last())
        val quick = run(base.copy(doubleTapMs = 150)) { tap(it, 0); tap(it, 300) }
        assertEquals(listOf(Gesture.Tap(50f, 50f), Gesture.Tap(50f, 50f)), quick)

        // A shorter long press fires sooner; a longer one lets a slow tap stay a tap.
        val g = TapGestures(base.copy(longPressMs = 250))
        g.down(0, 50f, 50f)
        assertEquals(listOf(Gesture.LongPress(50f, 50f)), g.timer(260))
        val h = TapGestures(base.copy(longPressMs = 1_200))
        h.down(0, 50f, 50f)
        assertEquals(listOf(Gesture.Tap(50f, 50f)), h.up(900, 50f, 50f))
    }

    @Test
    fun settingsResolveToTheirTiming() {
        val system = GestureSettings().timing(systemDoubleTapMs = 300, systemLongPressMs = 400, touchSlop = 8f, doubleTapSlop = 90f)
        assertEquals(TapTiming(true, false, 300, 400, 8f, 90f), system)
        val own = GestureSettings(doubleTap = DoubleTapAction.Nothing, waitForDoubleTap = true, doubleTapMs = 450, longPressMs = 900)
            .timing(300, 400, 8f, 90f)
        assertEquals(TapTiming(false, true, 450, 900, 8f, 90f), own)
    }

    @Test
    fun aTapsKeyboardForEveryActionInAndOutOfFullScreen() {
        // Raising: it only ever opens it, except in full screen where nothing else can close it.
        assertEquals(true, keyboardAfterTap(TapAction.Keyboard, up = false, fullScreen = false))
        assertEquals(true, keyboardAfterTap(TapAction.Keyboard, up = true, fullScreen = false))
        assertEquals(true, keyboardAfterTap(TapAction.Keyboard, up = false, fullScreen = true))
        assertEquals(false, keyboardAfterTap(TapAction.Keyboard, up = true, fullScreen = true))
        for (full in listOf(false, true)) {
            assertEquals(true, keyboardAfterTap(TapAction.Toggle, up = false, fullScreen = full))
            assertEquals(false, keyboardAfterTap(TapAction.Toggle, up = true, fullScreen = full))
            for (up in listOf(false, true)) assertNull(keyboardAfterTap(TapAction.Nothing, up, full))
        }
    }

    @Test
    fun aDoubleTapPutsTheKeyboardWhereItsActionSays() {
        for (before in listOf(false, true)) {
            assertEquals(before, keyboardAfterDoubleTap(before, DoubleTapAction.FullScreen))
            assertEquals(before, keyboardAfterDoubleTap(before, DoubleTapAction.Nothing))
            assertEquals(!before, keyboardAfterDoubleTap(before, DoubleTapAction.Keyboard))
        }
    }

    @Test
    fun theOldDoubleTapSwitchIsCarriedOver() {
        fun stored(doubleTap: String?, legacy: Boolean?) =
            GestureSettings.fromStored(null, doubleTap, null, false, 0, 0, legacy).doubleTap
        assertEquals(DoubleTapAction.FullScreen, stored(null, null))
        assertEquals(DoubleTapAction.FullScreen, stored(null, true))
        assertEquals(DoubleTapAction.Nothing, stored(null, false))
        // Once chosen, the new setting is the one that counts.
        assertEquals(DoubleTapAction.Keyboard, stored("keyboard", false))
        assertEquals(DoubleTapAction.FullScreen, stored("full_screen", false))
        // An unknown value is no choice at all.
        assertEquals(DoubleTapAction.Nothing, stored("zoom", false))
    }

    @Test
    fun nothingStoredIsTodaysBehaviour() {
        assertEquals(GestureSettings(), GestureSettings.fromStored(null, null, null, false, 0, 0, null))
    }

    @Test
    fun storedValuesAreReadAndTimesKeptInRange() {
        val s = GestureSettings.fromStored("toggle", "keyboard", "full_screen", true, 5_000, 50, true)
        assertEquals(GestureSettings(TapAction.Toggle, DoubleTapAction.Keyboard, LongPressAction.FullScreen, true, 600, 200), s)
        val own = GestureSettings.fromStored("nothing", "nothing", "keyboard", false, 250, 700, null)
        assertEquals(GestureSettings(TapAction.Nothing, DoubleTapAction.Nothing, LongPressAction.Keyboard, false, 250, 700), own)
        assertEquals(0, GestureSettings.fromStored(null, null, null, false, -3, 0, null).doubleTapMs)
    }

    @Test
    fun preferencesReadBackAndUnknownOnesFallBackToTheDefaults() {
        TapAction.entries.forEach { assertEquals(it, TapAction.fromPref(it.pref)) }
        DoubleTapAction.entries.forEach { assertEquals(it, DoubleTapAction.fromPref(it.pref)) }
        LongPressAction.entries.forEach { assertEquals(it, LongPressAction.fromPref(it.pref)) }
        assertEquals(TapAction.Keyboard, TapAction.fromPref("?"))
        assertNull(DoubleTapAction.fromPref(null))
        assertEquals(LongPressAction.Select, LongPressAction.fromPref(null))
        assertFalse(GestureSettings().waitForDoubleTap)
    }
}

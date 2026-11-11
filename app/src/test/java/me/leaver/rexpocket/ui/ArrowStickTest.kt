package me.leaver.rexpocket.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrow stick sends one arrow for each slide into a direction. Holding it there used to repeat
 * the arrow on its own, so a finger that only meant to pick "up" walked a whole menu; now an arrow
 * repeats only while the stick is pushed out to its rim.
 */
class ArrowStickTest {
    private val dead = 30f

    @Test
    fun aSlideIntoADirectionPicksItButDoesNotRepeat() {
        val delta = Offset(0f, -dead * 1.5f)
        assertEquals("arrow_up", stickDirection(delta, dead, previous = null))
        assertFalse(stickRepeats(delta, dead))
    }

    @Test
    fun pushedToTheRimItRepeats() {
        assertTrue(stickRepeats(Offset(0f, -dead * STICK_RIM), dead))
    }

    @Test
    fun theMiddlePicksNothing() {
        assertNull(stickDirection(Offset(dead * 0.5f, 0f), dead, previous = null))
    }

    @Test
    fun aSlideAcrossTheDiagonalKeepsTheArrowItHas() {
        // A little more vertical than horizontal, but the stick was already pointing right.
        val delta = Offset(dead * 2f, dead * 2.2f)
        assertEquals("arrow_right", stickDirection(delta, dead, previous = "arrow_right"))
        assertEquals("arrow_down", stickDirection(delta, dead, previous = null))
    }
}

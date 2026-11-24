package me.leaver.rexpocket.net

import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetBoxTest {
    @Test
    fun aNodeNameIsLowercaseLettersDigitsAndDashes() {
        assertEquals("my-pixel-8", sanitizeHostname("My Pixel 8"))
        assertEquals("phone-work", sanitizeHostname("phone_work"))
        assertEquals("pixel", sanitizeHostname("--pixel--"))
    }

    @Test
    fun aLongNodeNameIsCutTo63Characters() {
        val name = sanitizeHostname("a".repeat(100))
        assertEquals(63, name.length)
    }

    @Test
    fun aNameWithNothingUsableIsEmpty() {
        assertEquals("", sanitizeHostname("   "))
    }
}

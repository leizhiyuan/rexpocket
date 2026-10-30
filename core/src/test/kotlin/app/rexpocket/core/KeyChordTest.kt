package app.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyChordTest {
    @Test
    fun aLetterIsItsPhysicalKey() {
        assertEquals(KeyChord("key_r", 'r'.code), KeyChord.forChar('r'))
        // Ctrl-R and Ctrl-Shift-R are the same control character; the unshifted letter is what binds.
        assertEquals(KeyChord("key_r", 'r'.code), KeyChord.forChar('R'))
    }

    @Test
    fun thePunctuationThatHasAControlCharacterIsMapped() {
        assertEquals(KeyChord("bracket_left", '['.code), KeyChord.forChar('['))
        assertEquals(KeyChord("backslash", '\\'.code), KeyChord.forChar('\\'))
        assertEquals(KeyChord("bracket_right", ']'.code), KeyChord.forChar(']'))
        assertEquals(KeyChord("space", ' '.code), KeyChord.forChar(' '))
    }

    @Test
    fun anythingElseIsNotAChord() {
        assertNull(KeyChord.forChar('中'))
        assertNull(KeyChord.forChar('é'))
    }
}

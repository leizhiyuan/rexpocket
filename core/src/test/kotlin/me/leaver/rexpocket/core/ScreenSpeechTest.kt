package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenSpeechTest {
    @Test
    fun wrappedRowsAreOneLineAndBlankRowsAreNone() {
        val rows = listOf("\$ echo a long  ", "line here   ", "      ", "done", "")
        val lines = screenLines(rows, wrapped = listOf(true, false, false, false, false))
        assertEquals(
            listOf(ScreenLine(0, 1, "\$ echo a long  line here"), ScreenLine(3, 3, "done")),
            lines,
        )
    }

    @Test
    fun aWrapFlagOnTheLastRowStillEndsTheLine() {
        assertEquals(listOf(ScreenLine(0, 1, "abcd")), screenLines(listOf("ab", "cd"), listOf(true, true)))
    }

    @Test
    fun onlyLinesThatWereNotThereBeforeAreSaid() {
        val say = OutputAnnouncer(intervalMs = 1000)
        say.start(listOf("\$ ls"))
        // Output scrolls in under the command; the new prompt holds the cursor and is not said.
        say.changed(listOf("\$ ls", "a.txt", "b.txt", "\$ "), cursorLine = 3)
        assertEquals("a.txt\nb.txt", say.take(now = 5_000))
        assertNull(say.take(now = 5_001))
    }

    @Test
    fun aLinePrintedAgainIsNewButOneThatScrolledIsNot() {
        val say = OutputAnnouncer(intervalMs = 0)
        say.start(listOf("one", "ok", "\$ "))
        say.changed(listOf("ok", "\$ make", "ok", "\$ "), cursorLine = 3)
        assertEquals("\$ make\nok", say.take(now = 1))
    }

    @Test
    fun itSpeaksAtMostOncePerIntervalAndKeepsWhatCameMeanwhile() {
        val say = OutputAnnouncer(intervalMs = 1000)
        say.start(emptyList())
        say.changed(listOf("1"), cursorLine = -1)
        assertEquals("1", say.take(now = 10_000))
        say.changed(listOf("1", "2"), cursorLine = -1)
        say.changed(listOf("1", "2", "3"), cursorLine = -1)
        assertEquals(11_000L, say.dueAt())
        assertNull(say.take(now = 10_500))
        assertEquals("2\n3", say.take(now = 11_000))
        assertNull(say.dueAt())
    }

    @Test
    fun typingOnThePromptIsNotSaid() {
        val say = OutputAnnouncer(intervalMs = 0)
        say.start(listOf("\$ "))
        say.changed(listOf("\$ l"), cursorLine = 0)
        say.changed(listOf("\$ ls"), cursorLine = 0)
        assertNull(say.take(now = 1))
    }

    @Test
    fun aFloodIsCutShort() {
        val say = OutputAnnouncer(intervalMs = 0, maxChars = 10)
        say.start(emptyList())
        say.changed(listOf("aaaa", "bbbb", "cccc", "dddd"), cursorLine = -1)
        assertEquals("aaaa\nbbbb", say.take(now = 1))
    }

    @Test
    fun nothingIsSaidWhileNotListening() {
        val say = OutputAnnouncer(intervalMs = 0)
        say.changed(listOf("x"), cursorLine = -1)
        assertNull(say.take(now = 1))
        say.start(listOf("x"))
        say.stop()
        say.changed(listOf("x", "y"), cursorLine = -1)
        assertNull(say.take(now = 2))
    }
}

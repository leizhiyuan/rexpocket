package app.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDraftsTest {
    @Test
    fun eachPaneKeepsItsOwnDraft() {
        val drafts = ComposerDrafts()
        drafts.setDraft("a", "git sta")
        drafts.setDraft("b", "ls")
        assertEquals("git sta", drafts.draft("a"))
        assertEquals("ls", drafts.draft("b"))
        assertEquals("", drafts.draft("c"))
    }

    @Test
    fun sendingClearsTheDraftAndRemembersTheLine() {
        val drafts = ComposerDrafts()
        drafts.setDraft("a", "make test")
        assertEquals("make test", drafts.send("a"))
        assertEquals("", drafts.draft("a"))
        assertEquals("make test", drafts.older("a"))
    }

    @Test
    fun historyWalksBackAndForwardToWhatWasBeingTyped() {
        val drafts = ComposerDrafts()
        listOf("one", "two", "three").forEach { drafts.setDraft("a", it); drafts.send("a") }
        drafts.setDraft("a", "half")
        assertEquals("three", drafts.older("a"))
        assertEquals("two", drafts.older("a"))
        assertEquals("one", drafts.older("a"))
        assertNull("nothing older", drafts.older("a"))
        assertEquals("two", drafts.newer("a"))
        assertEquals("three", drafts.newer("a"))
        assertEquals("half", drafts.newer("a"))
        assertNull("nothing newer", drafts.newer("a"))
    }

    @Test
    fun anEmptyLineOrARepeatIsNotRemembered() {
        val drafts = ComposerDrafts()
        drafts.send("a")
        assertNull(drafts.older("a"))
        repeat(2) { drafts.setDraft("a", "ls"); drafts.send("a") }
        assertEquals("ls", drafts.older("a"))
        assertNull(drafts.older("a"))
    }

    @Test
    fun historyIsSharedAcrossPanesButBounded() {
        val drafts = ComposerDrafts(historySize = 3)
        (1..5).forEach { drafts.setDraft("a", "cmd $it"); drafts.send("a") }
        assertEquals("cmd 5", drafts.older("b"))
        assertEquals("cmd 4", drafts.older("b"))
        assertEquals("cmd 3", drafts.older("b"))
        assertNull(drafts.older("b"))
    }

    @Test
    fun typingStartsANewWalkThroughHistory() {
        val drafts = ComposerDrafts()
        listOf("one", "two").forEach { drafts.setDraft("a", it); drafts.send("a") }
        assertEquals("two", drafts.older("a"))
        drafts.setDraft("a", "tw")
        assertEquals("two", drafts.older("a"))
    }
}

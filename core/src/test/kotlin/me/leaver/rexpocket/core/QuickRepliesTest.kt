package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The quick replies list as Settings edits it and the app stores it. */
class QuickRepliesTest {
    @Test
    fun theDefaults() {
        assertEquals(listOf("yes", "continue", "/compact"), QuickReplies().items)
        assertEquals(QuickReplies(), QuickReplies.fromStored(null))
        assertEquals("a store that cannot be read gives the defaults", QuickReplies(), QuickReplies.fromStored("not json"))
    }

    @Test
    fun addEditMoveDelete() {
        var q = QuickReplies(listOf("a", "b"))
        q = q.add("  c \n")
        assertEquals(listOf("a", "b", "c"), q.items)
        assertEquals("an empty one is not added", q, q.add("   "))
        assertEquals("nor one already there", q, q.add("b"))
        q = q.edit(0, "first")
        assertEquals(listOf("first", "b", "c"), q.items)
        assertEquals("an edit that makes a duplicate is not taken", q, q.edit(0, "c"))
        assertEquals("an edit to nothing deletes it", listOf("b", "c"), q.edit(0, "").items)
        assertEquals(listOf("b", "first", "c"), q.move(0, 1).items)
        assertEquals(listOf("c", "first", "b"), q.move(2, -5).items)
        assertEquals(listOf("first", "b", "c"), q.move(2, 9).items)
        assertEquals(listOf("first", "c"), q.delete(1).items)
        assertEquals(q, q.delete(7))
        assertEquals(q, q.edit(-1, "x"))
    }

    @Test
    fun aLongerPromptKeepsItsLines() {
        val prompt = "Review the diff.\nThen run the tests."
        val q = QuickReplies(emptyList()).add("\n$prompt\n\n")
        assertEquals(listOf(prompt), q.items)
        assertEquals("Review the diff.…", QuickReplies.short(prompt))
        assertEquals("yes", QuickReplies.short("yes"))
        assertEquals(40, QuickReplies.short("x".repeat(80)).length)
    }

    @Test
    fun theListIsFullAtTwenty() {
        var q = QuickReplies(emptyList())
        repeat(25) { q = q.add("r$it") }
        assertEquals(QuickReplies.MAX_ITEMS, q.items.size)
        assertFalse(q.canAdd)
    }

    @Test
    fun aNotificationOffersTheShortOneLineReplies() {
        val q = QuickReplies(listOf("yes", "a\nb", "x".repeat(41), "continue", "/compact", "no", "later", "skip"))
        assertEquals(listOf("yes", "continue", "/compact", "no", "later"), q.choices())
    }

    @Test
    fun storedAndReadBack() {
        val q = QuickReplies(listOf("yes", "two\nlines", "/compact"))
        assertEquals(q, QuickReplies.fromStored(q.stored()))
        assertEquals("an empty list stays empty", QuickReplies(emptyList()), QuickReplies.fromStored("[]"))
        assertEquals(listOf("a"), QuickReplies.fromStored("""["a", "a", "  "]""").items)
    }
}

package me.leaver.rexpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Keys for the home list. Compose refuses two items with one key, so a key must be unique within
 * the list: the same session listed under two saved hosts is two rows and needs two keys.
 */
class HomeKeysTest {
    private val session = "session:01a1a654-cee3-75bc-94b2-cbcf48cd73b3"

    @Test
    fun `one session kept as two hosts gets a key per host`() {
        assertNotEquals(sessionRowKey("host-a", session), sessionRowKey("host-b", session))
        assertNotEquals(tabsRowKey("host-a", session), tabsRowKey("host-b", session))
    }

    @Test
    fun `a row keeps its key while the list is rebuilt`() {
        assertEquals(sessionRowKey("host-a", session), sessionRowKey("host-a", session))
        assertEquals(tabsRowKey("host-a", session), tabsRowKey("host-a", session))
    }

    @Test
    fun `session and tabs keys never collide`() {
        assertNotEquals(sessionRowKey("host-a", session), tabsRowKey("host-b", session))
    }
}

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

    /**
     * The node rebinds its sockets for another network, interface or address set, not for every
     * link change Android reports (DNS servers, routes, the same addresses again).
     */
    @Test
    fun onlyARealLinkChangeResetsTheNode() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        val v4 = ip("192.168.1.20") to 0
        val stable = ip("2001:db8::20") to 0
        val local = ip("fe80::20") to 0
        val links = LinkChanges()
        fun wifi(vararg more: Pair<java.net.InetAddress, Int>) = linkKey("net 100", "wlan0", listOf(v4, stable, local) + more)
        assertEquals("the network it started on", false, links.changed(wifi(ip("2001:db8::aaaa") to 0x01)))
        assertEquals("DNS or routes changed, addresses the same", false, links.changed(wifi(ip("2001:db8::aaaa") to 0x01)))
        assertEquals("a rotated privacy address", false, links.changed(wifi(ip("2001:db8::bbbb") to 0x01)))
        assertEquals("a new address", true, links.changed(wifi(ip("2001:db8::30") to 0)))
        assertEquals("mobile data", true, links.changed(linkKey("net 101", "rmnet_data0", listOf(ip("10.0.0.5") to 0))))
        assertEquals(false, links.changed(linkKey("net 101", "rmnet_data0", listOf(ip("10.0.0.5") to 0))))
    }
}

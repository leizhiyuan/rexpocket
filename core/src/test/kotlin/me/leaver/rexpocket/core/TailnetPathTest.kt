package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetPathTest {
    @Test
    fun aNameIsKnownOnlyOnceTheNetmapListsIt() {
        assertEquals(null, peerFor("my-mac.tail1234.ts.net", emptyMap()))
        val peers = mapOf("my-mac.tail1234.ts.net" to "100.64.0.2", "my-mac" to "100.64.0.2")
        assertEquals("100.64.0.2", peerFor("My-Mac.tail1234.ts.net.", peers))
        assertEquals("100.64.0.2", peerFor("my-mac", peers))
        // An address needs no netmap.
        assertEquals("100.64.0.9", peerFor("100.64.0.9", emptyMap()))
        assertEquals(null, peerFor("", peers))
    }

    @Test
    fun anEndpointMeansADirectPath() {
        assertEquals(
            TailnetPath.Direct("192.168.31.66:61511", 4.2),
            TailnetPath.from(4.2, "192.168.31.66:61511", "", 2, "sfo", ""),
        )
    }

    @Test
    fun aPeerRelayIsNamedWhenThereIsNoDirectPath() {
        assertEquals(
            TailnetPath.PeerRelay("100.64.0.9:7777", 38.0),
            TailnetPath.from(38.0, "", "100.64.0.9:7777", 2, "sfo", null),
        )
    }

    @Test
    fun otherwiseTheTrafficGoesThroughDerp() {
        assertEquals(
            TailnetPath.Derp(2, "sfo", 312.0),
            TailnetPath.from(312.0, null, null, 2, "sfo", null),
        )
    }

    @Test
    fun anErrorWinsOverEverythingElse() {
        assertEquals(
            TailnetPath.Unreachable("timeout"),
            TailnetPath.from(0.0, "1.2.3.4:5", null, 2, "sfo", "timeout"),
        )
    }
}

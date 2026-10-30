package app.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAddressTest {
    @Test
    fun aPlainAddressKeepsRexOwnPort() {
        val address = HostAddressParser.parse("192.0.2.10")!!
        assertEquals("192.0.2.10", address.host)
        assertEquals(7297, address.port)
        assertTrue(address.plaintext)
        assertFalse(address.viaTailscale)
    }

    @Test
    fun anAddressWithAPortUsesThatPort() {
        val address = HostAddressParser.parse("192.0.2.10:7297")!!
        assertEquals("192.0.2.10", address.host)
        assertEquals(7297, address.port)
        assertTrue(address.plaintext)
    }

    @Test
    fun aMagicDnsNameGoesThroughTheTailnetOverTls() {
        val address = HostAddressParser.parse("zhiyuanlei-mc3-rex.tail13fd44.ts.net")!!
        assertEquals("zhiyuanlei-mc3-rex.tail13fd44.ts.net", address.host)
        assertEquals(443, address.port)
        assertFalse(address.plaintext)
        assertTrue(address.viaTailscale)
    }

    @Test
    fun aTailnetNameWithASchemeKeepsItsScheme() {
        val https = HostAddressParser.parse("https://zhiyuanlei-mc3-rex.tail13fd44.ts.net/")!!
        assertEquals(443, https.port)
        assertFalse(https.plaintext)
        assertTrue(https.viaTailscale)

        val ws = HostAddressParser.parse("ws://host.tailnet-1234.ts.net:7300/api")!!
        assertEquals("host.tailnet-1234.ts.net", ws.host)
        assertEquals(7300, ws.port)
        assertTrue(ws.plaintext)
        assertTrue(ws.viaTailscale)
    }

    @Test
    fun aCgnatAddressIsOnTheTailnet() {
        val byDefault = HostAddressParser.parse("100.101.102.103")!!
        assertTrue(byDefault.viaTailscale)
        assertEquals(443, byDefault.port)
        assertFalse(byDefault.plaintext)

        val plainOnRexPort = HostAddressParser.parse("100.101.102.103:7297")!!
        assertTrue(plainOnRexPort.viaTailscale)
        assertEquals(7297, plainOnRexPort.port)
        assertTrue(plainOnRexPort.plaintext)
    }

    @Test
    fun aTailnetIpv6LiteralKeepsItsBrackets() {
        val address = HostAddressParser.parse("[fd7a:115c:a1e0::1]:7297")!!
        assertEquals("[fd7a:115c:a1e0::1]", address.host)
        assertEquals(7297, address.port)
        assertTrue(address.plaintext)
        assertTrue(address.viaTailscale)
    }

    @Test
    fun aPublicAddressIsNotOnTheTailnet() {
        assertFalse(HostAddressParser.isTailnet("8.8.8.8"))
        assertFalse(HostAddressParser.isTailnet("100.128.0.1"))
        assertFalse(HostAddressParser.isTailnet("not-a-tailnet-host"))
        assertTrue(HostAddressParser.isTailnet("100.64.0.1"))
        assertTrue(HostAddressParser.isTailnet("100.127.255.254"))
    }

    @Test
    fun nothingUsableMeansNoAddress() {
        assertNull(HostAddressParser.parse(""))
        assertNull(HostAddressParser.parse("   "))
        assertNull(HostAddressParser.parse("https://"))
        assertNull(HostAddressParser.parse("host:notaport"))
        assertNull(HostAddressParser.parse("host:70000"))
    }
}

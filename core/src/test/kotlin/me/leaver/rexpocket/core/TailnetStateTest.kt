package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TailnetStateTest {
    @Test
    fun aRunningNodeListsItsPeersByNameAndHostName() {
        val snapshot = parseTailnetState(
            """{"backendState":"Running","authUrl":"","tailnet":"example.com",
               "self":"phone.tail1234.ts.net","peers":[
                 {"dnsName":"My-Mac.tail1234.ts.net.","hostName":"My-Mac","ips":["100.64.0.2","fd7a::2"]},
                 {"dnsName":"nas.tail1234.ts.net","hostName":"nas","ips":[]}
               ]}""",
        )
        assertEquals("Running", snapshot.backendState)
        assertEquals("example.com", snapshot.networkName)
        assertEquals(
            mapOf(
                "my-mac.tail1234.ts.net." to "100.64.0.2",
                "my-mac" to "100.64.0.2",
            ),
            snapshot.peerAddresses,
        )
        // The peer with no address is counted, but cannot be dialed.
        assertEquals(2, snapshot.peerCount)
    }

    @Test
    fun aLoginIsReportedWithItsURL() {
        val snapshot = parseTailnetState("""{"backendState":"NeedsLogin","authUrl":"https://login.example/abc"}""")
        assertEquals("NeedsLogin", snapshot.backendState)
        assertEquals("https://login.example/abc", snapshot.authUrl)
        assertEquals(emptyMap<String, String>(), snapshot.peerAddresses)
        assertEquals(0, snapshot.peerCount)
    }

    @Test
    fun aMissingFieldIsEmptyNotAnError() {
        val snapshot = parseTailnetState("{}")
        assertEquals("", snapshot.backendState)
        assertEquals("", snapshot.networkName)
    }
}

class TailnetPathParseTest {
    @Test
    fun aTailscalePingReadsItsDirectPath() {
        assertEquals(
            TailnetPath.Direct("192.168.31.66:61511", 4.25),
            parseTailnetPath("""{"latencyMs":4.25,"endpoint":"192.168.31.66:61511","peerRelay":"","derpRegionId":0,"derpRegionCode":"","error":""}"""),
        )
    }

    @Test
    fun aTailscalePingReadsItsDerpRegion() {
        assertEquals(
            TailnetPath.Derp(2, "sfo", 312.0),
            parseTailnetPath("""{"latencyMs":312,"endpoint":"","peerRelay":"","derpRegionId":2,"derpRegionCode":"sfo","error":""}"""),
        )
    }

    @Test
    fun aNetbirdPingReadsItsRelayServer() {
        assertEquals(
            TailnetPath.Relay("rels://relay.example.com:443", 61.5),
            parseTailnetPath("""{"latencyMs":61.5,"endpoint":"","peerRelay":"","derpRegionId":0,"derpRegionCode":"","relayServer":"rels://relay.example.com:443","error":""}"""),
        )
    }

    @Test
    fun aPingWithAnErrorIsUnreachable() {
        assertEquals(
            TailnetPath.Unreachable("the peer is not connected yet"),
            parseTailnetPath("""{"latencyMs":0,"endpoint":"","peerRelay":"","derpRegionId":0,"derpRegionCode":"","relayServer":"","error":"the peer is not connected yet"}"""),
        )
    }
}

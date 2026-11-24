package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the home screen shows about the node and its devices, read from the nodes' state JSON. */
class TailnetDetailsTest {
    private val tailscale = """{"backendState":"Running","tailnet":"example.com","self":"pixel.tail1234.ts.net",
        "selfInfo":{"dnsName":"pixel.tail1234.ts.net","hostName":"pixel","ips":["100.101.102.103","fd7a:115c:a1e0::1"],
                    "os":"android","user":"me@example.com","relay":"fra","keyExpiry":"2027-03-01T12:00:00Z"},
        "health":["Some peers are advertising routes"],
        "peers":[
          {"dnsName":"mac.tail1234.ts.net","hostName":"Mac","ips":["100.64.0.2"],"os":"macOS","online":true,
           "link":"direct","linkDetail":"192.168.1.20:41641"},
          {"dnsName":"nas.tail1234.ts.net","hostName":"nas","ips":["100.64.0.3"],"os":"linux","online":false,
           "lastSeen":"2026-11-20T08:30:00Z","link":"idle"}
        ]}"""

    @Test
    fun theNodeItselfIsRead() {
        val self = parseTailnetState(tailscale).self!!
        assertEquals("pixel", self.name)
        assertEquals(listOf("100.101.102.103", "fd7a:115c:a1e0::1"), self.ips)
        assertEquals("me@example.com", self.user)
        assertEquals("fra", self.homeRelay)
        assertEquals(java.time.Instant.parse("2027-03-01T12:00:00Z").toEpochMilli(), self.keyExpiry)
    }

    @Test
    fun eachPeerIsReadWithItsStatus() {
        val snapshot = parseTailnetState(tailscale)
        assertEquals(listOf("Some peers are advertising routes"), snapshot.health)
        val (mac, nas) = snapshot.peers
        assertEquals("Mac", mac.name)
        assertEquals("macOS", mac.os)
        assertEquals(true, mac.online)
        assertEquals(PeerLink.Direct, mac.link)
        assertEquals("192.168.1.20:41641", mac.linkDetail)
        assertNull(mac.lastSeen)
        assertEquals(false, nas.online)
        assertEquals(java.time.Instant.parse("2026-11-20T08:30:00Z").toEpochMilli(), nas.lastSeen)
        assertEquals(PeerLink.Idle, nas.link)
    }

    @Test
    fun aNodeThatCannotTellOnlineSaysNothing() {
        val snapshot = parseTailnetState(
            """{"backendState":"Running","tailnet":"api.netbird.io","self":"phone.netbird.cloud",
               "selfInfo":{"dnsName":"phone.netbird.cloud","hostName":"phone","ips":["100.90.1.2"]},
               "peers":[{"dnsName":"mac.netbird.cloud","hostName":"mac","ips":["100.90.1.3"],"link":"relay",
                         "linkDetail":"rels://relay.netbird.io:443","latencyMs":12.5}]}""",
        )
        val peer = snapshot.peers.single()
        assertNull(peer.online)
        assertEquals(PeerLink.Relay, peer.link)
        assertEquals(12.5, peer.latencyMs!!, 0.0)
    }

    @Test
    fun anOlderLibraryStillGivesTheSelfName() {
        val snapshot = parseTailnetState("""{"backendState":"Running","self":"pixel.tail1234.ts.net","peers":[]}""")
        assertEquals("pixel", snapshot.self!!.name)
        assertTrue(snapshot.peers.isEmpty())
        assertTrue(snapshot.health.isEmpty())
    }

    @Test
    fun aPeerAnswersToItsNamesAndAddress() {
        val mac = parseTailnetState(tailscale).peers.first()
        assertTrue(mac.answersTo("mac.tail1234.ts.net"))
        assertTrue(mac.answersTo("MAC.tail1234.ts.net."))
        assertTrue(mac.answersTo("mac"))
        assertTrue(mac.answersTo("100.64.0.2"))
        assertFalse(mac.answersTo("mac.other.ts.net"))
        assertFalse(mac.answersTo(""))
        assertEquals("nas", parseTailnetState(tailscale).peers.named("nas.tail1234.ts.net")?.name)
    }
}

class TailnetStageTest {
    @Test
    fun aNodeNotJoinedIsOffWhateverItLastSaid() {
        assertEquals(TailnetStage.Off, tailnetStage(joined = false, backendState = "Running", device = "pixel"))
    }

    @Test
    fun theStepsOfATailscaleJoin() {
        assertEquals(TailnetStage.Starting, tailnetStage(true, ""))
        assertEquals(TailnetStage.Starting, tailnetStage(true, "NoState"))
        assertEquals(TailnetStage.SigningIn("https://login/x"), tailnetStage(true, "NeedsLogin", authUrl = "https://login/x"))
        assertEquals(TailnetStage.SigningIn(""), tailnetStage(true, "NeedsLogin"))
        assertEquals(TailnetStage.NeedsApproval, tailnetStage(true, "NeedsMachineAuth"))
        assertEquals(TailnetStage.Connecting, tailnetStage(true, "Starting"))
        assertEquals(TailnetStage.Connected("pixel", "example.com"), tailnetStage(true, "Running", device = "pixel", network = "example.com"))
        assertEquals(TailnetStage.Stopped, tailnetStage(true, "Stopped"))
    }

    @Test
    fun runningBeforeTheNetworkNamedTheDeviceIsStillConnecting() {
        assertEquals(TailnetStage.Connecting, tailnetStage(true, "Running"))
    }

    @Test
    fun aFailedStartWinsOverTheLastState() {
        assertEquals(TailnetStage.Failed("no key"), tailnetStage(true, "Running", device = "pixel", error = "no key"))
    }

    @Test
    fun aLoginLinkBeforeTheStateIsASignIn() {
        assertEquals(TailnetStage.SigningIn("https://login/x"), tailnetStage(true, "", authUrl = "https://login/x"))
    }

    @Test
    fun eachStageIsOnAStepOrNone() {
        assertEquals(TailnetStep.Start, TailnetStage.Starting.step())
        assertEquals(TailnetStep.SignIn, TailnetStage.SigningIn("").step())
        assertEquals(TailnetStep.SignIn, TailnetStage.NeedsApproval.step())
        assertEquals(TailnetStep.Connect, TailnetStage.Connecting.step())
        assertNull(TailnetStage.Connected("pixel", "").step())
        assertNull(TailnetStage.Failed("x").step())
        assertEquals(3, tailnetSteps(TailnetBackend.Tailscale).size)
        assertEquals(listOf(TailnetStep.Start, TailnetStep.Connect), tailnetSteps(TailnetBackend.Netbird))
    }

    @Test
    fun onlyWhatThePersonMustActOnIsLoud() {
        assertTrue(TailnetStage.SigningIn("").needsAttention)
        assertTrue(TailnetStage.NeedsApproval.needsAttention)
        assertTrue(TailnetStage.Failed("x").needsAttention)
        assertFalse(TailnetStage.Starting.needsAttention)
        assertFalse(TailnetStage.Connecting.needsAttention)
        assertFalse(TailnetStage.Connected("pixel", "").needsAttention)
    }
}

class ConnectProgressTest {
    @Test
    fun eachStepKeepsHowLongTheOneBeforeTook() {
        val p = ConnectProgress("mac", viaTailnet = true, step = ConnectStep.StartTailnet, startedAt = 1_000)
            .next(ConnectStep.FindPeer, 1_400)
            .next(ConnectStep.FindPeer, 1_500)
            .next(ConnectStep.Reach, 2_000)
        assertEquals(ConnectStep.Reach, p.step)
        assertEquals(2_000, p.stepStartedAt)
        assertEquals(listOf(ConnectStep.StartTailnet to 400L, ConnectStep.FindPeer to 600L), p.took)
    }

    @Test
    fun aHostOffTheTailnetHasTwoSteps() {
        assertEquals(listOf(ConnectStep.Reach, ConnectStep.OpenRex), ConnectProgress("h", false, ConnectStep.Reach, 0).steps)
        assertEquals(4, ConnectProgress("h", true, ConnectStep.Reach, 0).steps.size)
    }
}

class TailnetProblemTest {
    private val mac = TailnetPeer("mac.tail1234.ts.net", "mac", listOf("100.64.0.2"), online = true)
    private val nas = TailnetPeer("nas.tail1234.ts.net", "nas", listOf("100.64.0.3"), online = false, lastSeen = 42L)
    private val socks = Problem(Trouble.Other, "SOCKS server general failure")

    private fun explain(
        problem: Problem = socks,
        host: String = "mac.tail1234.ts.net",
        state: String = "Running",
        peers: List<TailnetPeer> = listOf(mac, nas),
        known: Boolean = true,
        dial: String? = null,
    ) = explainTailnetProblem(problem, host, 7297, state, peers, known, dial)

    @Test
    fun aRefusedPortIsRexNotListening() {
        assertEquals(Problem(Trouble.RexNotListening, "7297"), explain(dial = "connect tcp 100.64.0.2:7297: connection was refused"))
    }

    @Test
    fun anOfflineDeviceSaysWhenItWasSeen() {
        assertEquals(Problem(Trouble.TailnetPeerOffline, "42"), explain(host = "nas", dial = "i/o timeout"))
    }

    @Test
    fun aNameTheTailnetDoesNotHave() {
        assertEquals(Problem(Trouble.TailnetPeerUnknown, null), explain(host = "mini.tail1234.ts.net"))
        assertEquals(Problem(Trouble.TailnetPeerUnknown, null), explain(host = "mini", known = false, dial = "lookup mini: no such host"))
    }

    @Test
    fun aDeviceListNotThereYetIsNoReasonToBlameTheName() {
        assertEquals(socks, explain(host = "mini.tail1234.ts.net", peers = emptyList(), known = false))
    }

    @Test
    fun anAddressIsNeverAnUnknownName() {
        assertEquals(socks, explain(host = "100.64.0.9"))
    }

    @Test
    fun aNodeWaitingForSignInSaysSo() {
        assertEquals(Problem(Trouble.TailnetNeedsLogin, null), explain(state = "NeedsLogin"))
        assertEquals(Problem(Trouble.TailnetNeedsLogin, APPROVAL), explain(state = "NeedsMachineAuth"))
    }

    @Test
    fun theNodesReasonReplacesTheSocksError() {
        assertEquals(Problem(Trouble.TimedOut, "i/o timeout"), explain(problem = Problem(Trouble.TimedOut, "timeout"), dial = "i/o timeout"))
    }

    @Test
    fun whatIsNotAboutReachingTheHostIsLeftAlone() {
        val rejected = Problem(Trouble.Rejected, "unauthorized")
        assertEquals(rejected, explain(problem = rejected, state = "NeedsLogin", dial = "connection refused"))
        val ssh = Problem(Trouble.SshAuth, "bad password")
        assertEquals(ssh, explain(problem = ssh, dial = "connection refused"))
        val tls = Problem(Trouble.Other, "handshake failed")
        assertEquals(tls, explain(problem = tls))
    }

    @Test
    fun dialErrorsAreReadInTheNodesWords() {
        assertEquals(DialFailure.Refused, dialFailure("connect tcp 100.64.0.2:7297: connection was refused"))
        assertEquals(DialFailure.NoSuchHost, dialFailure("lookup mini.tail1234.ts.net: no such host"))
        assertEquals(DialFailure.TimedOut, dialFailure("dial tcp 100.64.0.3:7297: i/o timeout"))
        assertEquals(DialFailure.TimedOut, dialFailure("context deadline exceeded"))
        assertEquals(DialFailure.Unreachable, dialFailure("no route to host"))
        assertEquals(DialFailure.Other, dialFailure("something else"))
    }
}

package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * A host's other addresses: the order they are tried in, which failures move on to the next one,
 * the host key every SSH address is held to, and connections whose own address is down reaching
 * Rex through another - on ScriptedRex directly and through a real SSH server (MINA).
 */
class HostRoutesTest {
    private val closers = mutableListOf<AutoCloseable>()

    @After
    fun close() {
        closers.reversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closed(): T = also { closers += it }

    private fun main(host: String = "mac.local", port: Int = 7297, plaintext: Boolean = true, user: String? = null) =
        HostRoute(HostRoutes.key(host, port, plaintext, user), Endpoint(host, port, plaintext, sshUser = user), HostAddressParser.isTailnet(host), main = true)

    private fun keys(routes: List<HostRoute>) = routes.map { it.key }

    @Test
    fun theHostsOwnAddressFirstThenTheOthersAsListed() {
        val m = main()
        val plan = HostRoutes.plan(m, listOf("ws://192.168.1.20:7297", "ws://100.92.1.2:7297"), null, tryOthers = true, lastKey = null, lastAt = 0, now = 1_000)
        assertEquals(listOf("ws://mac.local:7297", "ws://192.168.1.20:7297", "ws://100.92.1.2:7297"), keys(plan))
        assertTrue(plan[0].main)
        assertFalse(plan[1].viaTailnet)
        assertTrue("a tailnet address goes through the node", plan[2].viaTailnet)
    }

    @Test
    fun offTriesOnlyTheHostsOwnAddress() {
        val plan = HostRoutes.plan(main(), listOf("ws://192.168.1.20:7297"), null, tryOthers = false, lastKey = null, lastAt = 0, now = 0)
        assertEquals(listOf("ws://mac.local:7297"), keys(plan))
    }

    @Test
    fun theLastOneThatWorkedGoesFirstForHalfAnHour() {
        val others = listOf("ws://192.168.1.20:7297", "ws://100.92.1.2:7297")
        val now = 10 * 3_600_000L
        val recent = HostRoutes.plan(main(), others, null, true, "ws://100.92.1.2:7297", now - 60_000, now)
        assertEquals(listOf("ws://100.92.1.2:7297", "ws://mac.local:7297", "ws://192.168.1.20:7297"), keys(recent))
        // Later, the host's own address is tried first again.
        val old = HostRoutes.plan(main(), others, null, true, "ws://100.92.1.2:7297", now - HostRoutes.PREFER_LAST_MS, now)
        assertEquals("ws://mac.local:7297", old[0].key)
        // An address no longer on the list is forgotten.
        val gone = HostRoutes.plan(main(), others, null, true, "ws://10.0.0.9:7297", now - 1, now)
        assertEquals("ws://mac.local:7297", gone[0].key)
    }

    @Test
    fun anAddressOfAnotherKindOrWeakerIsNotDialed() {
        val ssh = main("mac.local", 22, true, "me")
        assertEquals(RouteSkip.OtherKind, HostRoutes.fallback("ws://192.168.1.20:7297", ssh, "me").second)
        val noUser = HostRoutes.fallback("ssh://192.168.1.20", ssh, "me").first!!
        assertEquals("ssh://me@192.168.1.20:22", noUser.key)
        assertEquals("ssh://admin@192.168.1.20:2222", HostRoutes.fallback("ssh://admin@192.168.1.20:2222", ssh, "me").first!!.key)
        assertEquals(RouteSkip.Same, HostRoutes.fallback("ssh://me@mac.local:22", ssh, "me").second)

        val tls = main("mac.example.ts.net", 443, plaintext = false)
        assertEquals(RouteSkip.Weaker, HostRoutes.fallback("ws://192.168.1.20:7297", tls, null).second)
        assertEquals("wss://192.168.1.20:8443", HostRoutes.fallback("wss://192.168.1.20:8443", tls, null).first!!.key)
        assertEquals(RouteSkip.OtherKind, HostRoutes.fallback("ssh://me@192.168.1.20", tls, null).second)
        assertEquals(RouteSkip.Unreadable, HostRoutes.fallback("mac:notaport", tls, null).second)
        // A plain host may go on to TLS.
        assertEquals("wss://192.168.1.20:443", HostRoutes.fallback("wss://192.168.1.20:443", main(), null).first!!.key)
    }

    @Test
    fun onlyAnAddressThatCannotBeReachedFallsThrough() {
        for (t in listOf(Trouble.Unreachable, Trouble.TimedOut, Trouble.TailnetNotJoined, Trouble.Tailnet, Trouble.TailnetNeedsLogin, Trouble.TailnetPeerUnknown, Trouble.TailnetPeerOffline, Trouble.RexNotListening)) {
            assertTrue(t.name, HostRoutes.fallsThrough(Problem(t, null)))
        }
        for (t in listOf(Trouble.SshAuth, Trouble.SshSecretUnreadable, Trouble.SshHostKeyChanged, Trouble.SshHostKeyUnknown, Trouble.SshCommand, Trouble.Rejected, Trouble.Dropped, Trouble.Other, Trouble.NotReady)) {
            assertFalse(t.name, HostRoutes.fallsThrough(Problem(t, null)))
        }
        assertFalse(HostRoutes.fallsThrough(null))
    }

    @Test
    fun theCursorMovesOnWrapsAndStaysOnTheMachinesOwnAnswer() {
        val plan = HostRoutes.plan(main(), listOf("ws://10.0.0.2:7297", "ws://10.0.0.3:7297"), null, true, null, 0, 0)
        var remembered: HostRoute? = null
        val c = RouteCursor(plan) { remembered = it }
        assertEquals(HostRoutes.ROUTE_CONNECT_MS, c.connectTimeoutMs())
        assertTrue(c.failed(Problem(Trouble.Unreachable, "refused")))
        assertEquals("ws://10.0.0.2:7297", c.current.key)
        assertTrue(c.onFallback)
        assertTrue(c.failed(Problem(Trouble.TimedOut, null)))
        assertEquals("the last address of a round waits as long as ever", HostRoutes.FULL_CONNECT_MS, c.connectTimeoutMs())
        assertFalse("every address failed: the usual wait, then the first again", c.failed(Problem(Trouble.Unreachable, null)))
        assertEquals(plan[0], c.current)
        assertEquals(1, c.round)
        assertEquals("after a round with no answer every address gets the full time", HostRoutes.FULL_CONNECT_MS, c.connectTimeoutMs())
        assertEquals(3, c.failures().size)
        assertTrue(c.failed(Problem(Trouble.Unreachable, null)))
        assertFalse("a refused password is the machine's answer", c.failed(Problem(Trouble.SshAuth, "denied")))
        assertEquals("ws://10.0.0.2:7297", c.current.key)
        c.live()
        assertEquals("ws://10.0.0.2:7297", remembered?.key)
        assertEquals(0, c.round)
        assertTrue(c.failures().isEmpty())
    }

    // Connections.

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun live(session: PocketSession) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        closers += AutoCloseable { runBlocking { session.stop() }; scope.cancel() }
        session.start(scope)
        withTimeout(30_000) { session.awaitReady() }
        withTimeout(10_000) { session.state.first { it.phase == Phase.Live } }
    }

    @Test
    fun aRexPortWhoseOwnAddressIsDownIsReachedAtTheNext() {
        val rex = ScriptedRex().closed()
        val m = main("127.0.0.1", closedPort())
        val cursor = RouteCursor(HostRoutes.plan(m, listOf("ws://127.0.0.1:${rex.endpoint.port}"), null, true, null, 0, 0))
        val dialer = HostDialer(cursor, { _, _ -> null }).closed()
        val st = live(PocketSession(m.endpoint, httpFactory = dialer::http, clientFactory = { dialer.client() }, routes = cursor))
        assertEquals(Phase.Live, st.phase)
        assertTrue(cursor.onFallback)
        assertEquals("127.0.0.1:${rex.endpoint.port}", st.endpointLabel)
    }

    @Test
    fun anSshHostWhoseOwnAddressIsDownIsReachedAtTheNextWithItsTrustedKey() {
        val rex = ScriptedRex().closed()
        val sshd = TestSshd(rexPort = rex.endpoint.port).closed()
        val m = main("127.0.0.1", closedPort(), user = "me")
        val cursor = RouteCursor(HostRoutes.plan(m, listOf("ssh://127.0.0.1:${sshd.port}"), "me", true, null, 0, 0))
        val dialer = HostDialer(cursor, { r, v -> SshSettings(r.endpoint.host, r.endpoint.port, r.endpoint.sshUser!!, SshCredentials(password = "pw"), sshd.fingerprint, socksPort = v.socksPort) }).closed()
        val st = live(PocketSession(m.endpoint, httpFactory = dialer::http, clientFactory = { dialer.client() }, routes = cursor))
        assertEquals(Phase.Live, st.phase)
        assertEquals("ssh://me@127.0.0.1:${sshd.port}", cursor.current.key)
    }

    @Test
    fun anotherAddressWithAnotherHostKeyIsNeverTaken() = runBlocking {
        val rex = ScriptedRex().closed()
        val trusted = TestSshd(rexPort = rex.endpoint.port).closed()
        val impostor = TestSshd(rexPort = rex.endpoint.port).closed()
        val m = main("127.0.0.1", closedPort(), user = "me")
        val plan = HostRoutes.plan(m, listOf("ssh://127.0.0.1:${impostor.port}", "ssh://127.0.0.1:${trusted.port}"), "me", true, null, 0, 0)
        val cursor = RouteCursor(plan)
        val dialer = HostDialer(cursor, { r, _ -> SshSettings(r.endpoint.host, r.endpoint.port, "me", SshCredentials(password = "pw"), trusted.fingerprint) }).closed()
        val session = PocketSession(m.endpoint, httpFactory = dialer::http, clientFactory = { dialer.client() }, routes = cursor)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        closers += AutoCloseable { runBlocking { session.stop() }; scope.cancel() }
        session.start(scope)
        val failed = withTimeout(30_000) { session.state.first { it.problem?.trouble == Trouble.SshHostKeyChanged } }
        assertEquals(impostor.fingerprint, failed.problem?.detail)
        delay(1_000)
        // It stopped there: the address that answered with the wrong key, not the one after it.
        assertEquals("ssh://me@127.0.0.1:${impostor.port}", cursor.current.key)
        assertTrue(session.state.value.phase != Phase.Live)
        assertNull(session.state.value.clientId)
    }

    @Test
    fun aRefusedPasswordAtTheHostsOwnAddressDoesNotGoOn() = runBlocking {
        val rex = ScriptedRex().closed()
        val sshd = TestSshd(rexPort = rex.endpoint.port).closed()
        val other = TestSshd(rexPort = rex.endpoint.port).closed()
        val m = main("127.0.0.1", sshd.port, user = "me")
        val cursor = RouteCursor(HostRoutes.plan(m, listOf("ssh://127.0.0.1:${other.port}"), "me", true, null, 0, 0))
        val dialer = HostDialer(cursor, { r, _ -> SshSettings(r.endpoint.host, r.endpoint.port, "me", SshCredentials(password = "wrong"), sshd.fingerprint) }).closed()
        val session = PocketSession(m.endpoint, httpFactory = dialer::http, clientFactory = { dialer.client() }, routes = cursor)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        closers += AutoCloseable { runBlocking { session.stop() }; scope.cancel() }
        session.start(scope)
        withTimeout(30_000) { session.state.first { it.problem?.trouble == Trouble.SshAuth } }
        delay(500)
        assertTrue(cursor.current.main)
    }
}

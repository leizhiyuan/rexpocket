package me.leaver.rexpocket.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.core.PeerLink
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetPeer
import me.leaver.rexpocket.core.TailnetSelf
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.net.TailnetConfig
import me.leaver.rexpocket.net.TailnetException
import me.leaver.rexpocket.net.TailnetNode
import me.leaver.rexpocket.net.TailnetEntry
import me.leaver.rexpocket.net.TailnetNodes
import me.leaver.rexpocket.net.TailnetStatus
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

// The tailnet as the app sees it, for the screen tests: a node that reports what a test tells it
// to, in the shape the real ones report (their Go library only loads on a phone), and a SOCKS5
// proxy on loopback in place of the node's, which forwards to ScriptedRex or fails as a node's
// proxy fails. Everything above the node - the view model, the store, the screens - is the app's.

/** A tailnet node whose state a test sets; [start] hands out [socksPort], or fails with [startError]. */
internal class FakeTailnetNode : TailnetNode {
    val state = MutableStateFlow(TailnetStatus())
    override val status: StateFlow<TailnetStatus> = state

    @Volatile var socksPort = 0
    @Volatile var startError: String? = null
    @Volatile var starts = 0
    @Volatile var stops = 0

    /** The node's reason for its last failed dial, by host. */
    val dialErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    override val proxyPort: Int get() = socksPort
    override fun knows(host: String): Boolean = state.value.peers.any { it.answersTo(host) }
    override suspend fun awaitPeer(host: String, timeoutMs: Long): Boolean =
        // On the IO threads, whose clock is the real one: a delay on the main thread waits for Robolectric's.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) { state.first { s -> s.peers.any { it.answersTo(host) } } } != null
        }
    override fun probePath(host: String): TailnetPath = TailnetPath.Direct("192.168.1.20:41641", 4.0)
    override fun dialError(host: String): String? = dialErrors[host.lowercase()]
    override fun resetNetwork() = Unit
    /** What the node was last started with: its account, among the rest. */
    @Volatile var lastConfig: TailnetConfig? = null

    override fun start(config: TailnetConfig): Int {
        starts++
        lastConfig = config
        startError?.let { throw TailnetException(it) }
        return socksPort
    }
    override fun stop() {
        stops++
    }
    override fun stopAsync() = stop()

    /** The accounts whose login was deleted, in order. */
    val forgotten = java.util.concurrent.CopyOnWriteArrayList<String>()
    override fun forgetProfile(profile: String) {
        forgotten += profile
    }

    /** Whether the node can keep accounts now; a test turns it off as an older helper app would. */
    val accountsKept = MutableStateFlow(true)
    override val keepsAccounts: StateFlow<Boolean> = accountsKept
}

/** What a node reports at each stage, with a tailnet of five devices. */
internal object TailnetSamples {
    private const val HOUR = 3_600_000L
    private val now = System.currentTimeMillis()

    val self = TailnetSelf(
        dnsName = "pixel-8.tail1234.ts.net",
        hostName = "pixel-8",
        ips = listOf("100.101.102.103", "fd7a:115c:a1e0::6a01:1"),
        os = "android",
        user = "me@example.com",
        homeRelay = "fra",
        keyExpiry = now + 150 * 24 * HOUR,
    )
    val mac = TailnetPeer(
        "mac.tail1234.ts.net", "mac", listOf("100.64.0.2", "fd7a:115c:a1e0::2"), os = "macOS", online = true,
        link = PeerLink.Direct, linkDetail = "192.168.1.20:41641",
    )
    val nas = TailnetPeer("nas.tail1234.ts.net", "nas", listOf("100.64.0.3"), os = "linux", online = false, lastSeen = now - 3 * HOUR)
    val buildBox = TailnetPeer("build-box.tail1234.ts.net", "build-box", listOf("100.64.0.4"), os = "linux", online = true, link = PeerLink.Idle)
    val iphone = TailnetPeer("iphone.tail1234.ts.net", "iphone", listOf("100.64.0.5"), os = "iOS", online = true, link = PeerLink.Derp, linkDetail = "fra")
    val desktop = TailnetPeer("desktop.tail1234.ts.net", "desktop", listOf("100.64.0.6"), os = "windows", online = false, lastSeen = now - 50 * HOUR)
    val peers = listOf(nas, buildBox, mac, iphone, desktop)

    val starting = TailnetStatus()
    // A link no app opens: a tap on Sign in opens it, and a browser would take the screen.
    val needsLogin = TailnetStatus(backendState = "NeedsLogin", authUrl = "rexpocket-test://login.tailscale.com/a/1a2b3c4d5e")
    val connecting = TailnetStatus(backendState = "Starting")
    fun connected(peers: List<TailnetPeer> = this.peers, health: List<String> = emptyList()) =
        TailnetStatus(backendState = "Running", networkName = "example.com", self = self, peers = peers, health = health)

    /** A Netbird network as its node reports it: no sign-in, no online state, its own relays. */
    fun netbird() = TailnetStatus(
        backendState = "Running",
        networkName = "api.netbird.io",
        self = TailnetSelf(dnsName = "pixel-8.netbird.cloud", hostName = "pixel-8", ips = listOf("100.90.12.4")),
        peers = listOf(
            TailnetPeer("mac.netbird.cloud", "mac", listOf("100.90.12.7"), link = PeerLink.Direct, linkDetail = "192.168.1.20:51820", latencyMs = 6.0),
            TailnetPeer("nas.netbird.cloud", "nas", listOf("100.90.12.9"), link = PeerLink.Relay, linkDetail = "rels://relay.netbird.io:443", latencyMs = 48.0),
            TailnetPeer("vm.netbird.cloud", "vm", listOf("100.90.12.11"), link = PeerLink.Idle),
        ),
    )

    /** Saved hosts on the tailnet: one on [mac], one on the offline [nas]. */
    val macHost = SavedHost(id = "mac-host", label = "mac", host = "mac.tail1234.ts.net", port = 7297, plaintext = true, viaTailscale = true)
    val nasHost = SavedHost(id = "nas-host", label = "nas", host = "nas.tail1234.ts.net", port = 7297, plaintext = true, viaTailscale = true)
}

/**
 * The app as a phone that joined a tailnet left it: [hosts] saved, [open] the one opened last (and
 * connected on launch), and its view model on [node]. No update check.
 */
internal fun tailnetModel(
    compose: AppRule,
    node: FakeTailnetNode,
    hosts: List<SavedHost> = listOf(TailnetSamples.macHost, TailnetSamples.nasHost),
    open: SavedHost? = null,
    backend: TailnetBackend = TailnetBackend.Tailscale,
    joined: Boolean = true,
    /** Each tailnet's choices and the ones disconnected by hand, as an earlier run left them. */
    policies: me.leaver.rexpocket.core.TailnetPolicies? = null,
): RexViewModel {
    val app = freshApp()
    policies?.let { me.leaver.rexpocket.data.TailnetPolicyStore(app).write(it) }
    val store = HostStore(app)
    hosts.forEach(store::upsert)
    store.setLastHost(open?.id)
    store.setTailnet(TailnetSettings(backend, enabled = joined, hostname = "pixel-8"))
    app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit()
        .putLong("update.checkedAt", System.currentTimeMillis()).commit()
    lateinit var model: RexViewModel
    val other = FakeTailnetNode()
    val nodes = TailnetNodes(
        listOf(
            TailnetEntry(TailnetBackend.Tailscale, if (backend == TailnetBackend.Tailscale) node else other),
            TailnetEntry(TailnetBackend.Netbird, if (backend == TailnetBackend.Netbird) node else other) { backend == TailnetBackend.Netbird },
        ),
    )
    compose.runOnUiThread { model = RexViewModel(app as Application, SavedStateHandle(), nodes) }
    return model
}

/**
 * A SOCKS5 proxy on loopback, as the node's is: each CONNECT goes to where [route] says, or fails
 * with a general failure - all a Tailscale node's proxy ever answers - when it says null. With
 * [stall] it takes the connection and never answers, as a proxy whose peer does not answer.
 */
internal class FakeSocks(
    private val stall: Boolean = false,
    private val route: (host: String, port: Int) -> InetSocketAddress?,
) : AutoCloseable {
    // 127.0.0.1, where the app dials the node's proxy: Android's loopback address is ::1.
    private val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** Each host:port a client asked for, as it asked. */
    val asked = CopyOnWriteArrayList<String>()
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fake-socks") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                sockets += client
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    private fun serve(client: Socket) {
        if (stall) return
        val input = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        // Greeting: version, the methods offered; no authentication.
        input.readUnsignedByte()
        repeat(input.readUnsignedByte()) { input.readUnsignedByte() }
        out.write(byteArrayOf(5, 0))
        // Request: version, CONNECT, reserved, then the address.
        input.readUnsignedByte(); input.readUnsignedByte(); input.readUnsignedByte()
        val host = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 0xff).toString() }
            3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully))
            else -> java.net.InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress.orEmpty()
        }
        val port = input.readUnsignedShort()
        asked += "$host:$port"
        val target = route(host, port)
        if (target == null) {
            out.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            client.close()
            return
        }
        val upstream = Socket(target.address, target.port).also { sockets += it }
        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
        thread(isDaemon = true) { pipe(upstream.getInputStream(), out) }
        pipe(input, upstream.getOutputStream())
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        runCatching {
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}

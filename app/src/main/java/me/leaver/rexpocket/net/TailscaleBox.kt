package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.peerFor
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.go.tailnet.Node
import me.leaver.rexpocket.go.tailnet.Platform
import me.leaver.rexpocket.go.tailnet.Tailnet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the user filled in for the built-in Tailscale node. */
data class TailscaleConfig(val authKey: String, val hostname: String)

/** What the node reports about itself: Tailscale's own state machine. */
data class TailscaleStatus(
    val backendState: String = "",
    val text: String = "",
    val authUrl: String = "",
    val networkName: String = "",
)

class TailscaleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Tag shared by the tailnet diagnostics; `adb logcat -s RexPocketTS` shows the node's own log. */
const val LOG_TAG = "RexPocketTS"

/**
 * A Tailscale node running inside the app - the official tailscale.com tsnet library, through
 * the `tailnet` Go module - and a SOCKS5 proxy on loopback that dials through it. The Rex
 * connection is pointed at that proxy, so a tailnet name such as `host.tailnet-xxxx.ts.net` is
 * resolved and dialed by Tailscale instead of by the phone.
 *
 * Nothing here touches VpnService: the node runs in userspace, the phone keeps its own network,
 * and only the sockets this app opens for Rex go through the tunnel. That is why the Tailscale app
 * does not have to run, and why this composes with whatever VPN the phone already uses.
 */
class TailscaleBox(private val context: Context) {
    /**
     * Whether the node's own UDP sockets skip a VPN on the phone and use the network under it. On
     * by default: a proxy VPN turns every direct path into a DERP relay. Read for every socket.
     */
    @Volatile var bypassVpn: Boolean = true

    private val _status = MutableStateFlow(TailscaleStatus())
    /** Live node state, so the home screen can say whether it still needs a login. */
    val status: StateFlow<TailscaleStatus> = _status

    @Volatile private var node: Node? = null
    @Volatile private var running: TailscaleConfig? = null
    @Volatile private var port: Int = 0

    /** Port of the local SOCKS5 proxy while the node runs, 0 otherwise. */
    val proxyPort: Int get() = port

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Reads the node's state off the Go callback, one read at a time, coalescing a burst. */
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "tailnet-state") }
    private val readPending = AtomicBoolean(false)

    /**
     * Starts the node for [config] and returns the SOCKS5 port, or reuses the running one when the
     * config has not changed. Blocking: call from an IO dispatcher.
     */
    @Synchronized
    fun start(config: TailscaleConfig): Int {
        running?.let {
            if (it == config && node != null) {
                Diagnostics.event(LOG_TAG, "reusing the node on port $port (hostname=${config.hostname.ifBlank { "default" }})")
                return port
            }
            Diagnostics.event(LOG_TAG, "restarting the node: config changed (hostname=${config.hostname.ifBlank { "default" }})")
        }
        Diagnostics.event(LOG_TAG, "starting the node (hostname=${config.hostname.ifBlank { "default" }})")
        stopLocked()
        dropSingBoxLeftovers()
        val started = try {
            Tailnet.start(
                stateDirectory().apply { mkdirs() }.absolutePath,
                sanitizeHostname(config.hostname).ifBlank { defaultHostname() },
                config.authKey,
                platform,
            )
        } catch (e: Exception) {
            throw TailscaleException(tailscaleMessage(e), e)
        }
        node = started
        running = config
        port = started.proxyPort()
        _status.value = TailscaleStatus(backendState = "Starting")
        watchNetwork()
        readState()
        return port
    }

    /** Each peer's MagicDNS name, and its short host name, to its first tailnet address. */
    @Volatile private var peers: Map<String, String> = emptyMap()
        set(value) {
            field = value
            _peerNames.value = value.keys
        }

    private val _peerNames = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Whether the node can route to [host] yet: a `100.x` address always, a MagicDNS name once the
     * netmap lists that peer. The node reports Running before its first netmap arrives, and a name
     * dialed in that gap fails in its DNS, which reaches the app as a SOCKS "general failure".
     */
    fun knows(host: String): Boolean = peerFor(host, peers) != null

    /** Waits until [knows] says yes, for at most [timeoutMs]; true when it did. */
    suspend fun awaitPeer(host: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { _peerNames.first { peerFor(host, peers) != null } } != null

    /**
     * How this phone's node reaches [host], from a few disco pings: straight over UDP, through a
     * peer relay, or through a DERP server. A tailnet name is looked up among the peers the node
     * knows; a `100.x` address is pinged as it is. Blocking: call from an IO dispatcher.
     */
    fun probePath(host: String, pings: Int = PROBE_PINGS): TailnetPath {
        val current = node ?: return TailnetPath.Unreachable("the node is not running")
        val ip = peerFor(host, peers) ?: return TailnetPath.Unreachable("$host is not a peer of this tailnet")
        // The first ping often still goes through DERP while the direct path is being set up;
        // the last one says where the traffic settled.
        var last: TailnetPath = TailnetPath.Unreachable("no answer")
        repeat(pings) {
            last = runCatching { pathOf(current.ping(ip, PROBE_WAIT_MS)) }
                .getOrElse { TailnetPath.Unreachable(it.message ?: "ping failed") }
            if (last is TailnetPath.Direct) return last
        }
        return last
    }

    private fun pathOf(json: String): TailnetPath {
        val o = JSONObject(json)
        return TailnetPath.from(
            o.optDouble("latencyMs", 0.0),
            o.optString("endpoint"),
            o.optString("peerRelay"),
            o.optLong("derpRegionId").toInt(),
            o.optString("derpRegionCode"),
            o.optString("error"),
        )
    }

    /**
     * Tells the node its network may have changed under it: it takes fresh sockets and finds its
     * paths again, at once instead of when its own monitor polls - which on Android is every few
     * minutes, since apps get no netlink. Android cuts an app's sockets soon after it leaves the
     * screen, so this also runs when the app comes back.
     */
    fun resetNetwork() {
        val current = node ?: return
        runCatching { current.networkChanged() }.onFailure { Diagnostics.event(LOG_TAG, "network reset failed: ${it.message}") }
    }

    @Synchronized
    fun stop() {
        if (node != null) Diagnostics.event(LOG_TAG, "stopping the node on port $port")
        runCatching { stopLocked() }.onFailure { Diagnostics.event(LOG_TAG, "stop failed: ${it.message}") }
    }

    /**
     * Stops the node off the caller's thread; closing it talks to the control plane. A failure
     * here must not reach the default handler: that would kill the app over a stop it wanted.
     */
    fun stopAsync() {
        Thread({
            runCatching { stop() }.onFailure { Diagnostics.event(LOG_TAG, "stop failed: ${it.message}") }
        }, "tailscale-stop").start()
    }

    private fun stopLocked() {
        unwatchNetwork()
        val current = node
        node = null
        running = null
        port = 0
        peers = emptyMap()
        _status.value = TailscaleStatus()
        runCatching { current?.close() }
    }

    /** Reads the node's state into [status] and the peer map, once per burst of changes. */
    private fun readState() {
        if (!readPending.compareAndSet(false, true)) return
        reader.execute {
            readPending.set(false)
            val current = node ?: return@execute
            val json = runCatching { current.state() }.getOrElse {
                _status.value = _status.value.copy(text = it.message.orEmpty())
                return@execute
            }
            if (node !== current) return@execute
            val o = JSONObject(json)
            val found = HashMap<String, String>()
            val list = o.optJSONArray("peers") ?: JSONArray()
            for (i in 0 until list.length()) {
                val peer = list.optJSONObject(i) ?: continue
                val first = peer.optJSONArray("ips")?.optString(0)?.takeIf { it.isNotEmpty() } ?: continue
                peer.optString("dnsName").lowercase().takeIf { it.isNotEmpty() }?.let { found[it] = first }
                peer.optString("hostName").lowercase().takeIf { it.isNotEmpty() }?.let { found[it] = first }
            }
            peers = found
            val next = TailscaleStatus(
                backendState = o.optString("backendState"),
                authUrl = o.optString("authUrl"),
                networkName = o.optString("tailnet"),
            )
            if (next.copy(authUrl = "") != _status.value.copy(authUrl = "")) {
                Diagnostics.event(LOG_TAG, "node state ${next.backendState} peers=${list.length()}".trim())
            }
            _status.value = next
        }
    }

    // The node's own monitor polls; the app hears about a new network at once and passes it on.
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork() {
        val manager = connectivity ?: return
        if (networkCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null

            override fun onAvailable(network: Network) {
                if (last != null && last != network) resetNetwork()
                last = network
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == last) resetNetwork()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    private fun stateDirectory(): File = File(context.filesDir, "tailscale")

    /**
     * The node used to run inside sing-box, which kept its cache and crash log in `files/libbox`,
     * and its configuration and command socket beside it. The login itself was always in
     * [stateDirectory], in the format tsnet reads, so it carries over; the rest is of no use now.
     */
    private fun dropSingBoxLeftovers() {
        val files = context.filesDir
        val removed = listOf(File(files, "libbox"), File(files, "configuration.json"), File(files, "command.sock"))
            // A socket is neither a file nor a directory to the tree walk, which then deletes
            // nothing and reports success: only a directory is walked.
            .filter { it.exists() && (if (it.isDirectory) it.deleteRecursively() else it.delete()) }
        if (removed.isNotEmpty()) Diagnostics.event(LOG_TAG, "removed the sing-box leftovers: ${removed.joinToString { it.name }}")
    }

    private fun tailscaleMessage(e: Exception): String {
        val text = e.message.orEmpty()
        return if (text.contains("auth", ignoreCase = true) || text.contains("login", true)) {
            "$text (check the Tailscale auth key)"
        } else {
            text.ifBlank { "Tailscale could not start" }
        }
    }

    private val platform = object : Platform {
        /**
         * The phone's interfaces, for the node's network monitor: Go cannot list them on Android,
         * where apps get no netlink. Every network the phone has, each with its addresses.
         */
        @Suppress("DEPRECATION") // allNetworks also lists networks a callback has not reported yet.
        override fun interfaces(): String {
            val manager = connectivity ?: return "[]"
            val out = JSONArray()
            for (network in manager.allNetworks) {
                val link = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
                val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
                val name = link.interfaceName ?: continue
                val java = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull() ?: continue
                out.put(
                    JSONObject()
                        .put("name", name)
                        .put("index", java.index)
                        .put("mtu", runCatching { java.mtu }.getOrDefault(DEFAULT_MTU))
                        .put("up", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        .put("loopback", runCatching { java.isLoopback }.getOrDefault(false))
                        .put("pointToPoint", runCatching { java.isPointToPoint }.getOrDefault(false))
                        .put("multicast", runCatching { java.supportsMulticast() }.getOrDefault(false))
                        .put(
                            "addrs",
                            JSONArray(link.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }),
                        ),
                )
            }
            return out.toString()
        }

        /**
         * A UDP socket the node is about to punch holes with, bound - with [bypassVpn] - to the
         * phone's network under any VPN. A hole punched through a proxy VPN opens on the proxy's
         * address, which the peer can never reach, so every packet falls back to DERP. Android lets
         * an app leave a VPN this way only when the VPN allows it ("bypassable"); otherwise this
         * throws and the socket keeps the default route. TCP - the control plane, DERP - is never
         * handed here: where tailscale.com is blocked, the VPN is what reaches it at all.
         */
        override fun bindUDP(fd: Int) {
            if (!bypassVpn) return
            val network = underlyingNetwork() ?: return
            // fromFd duplicates the descriptor, so closing the copy leaves the node's own socket.
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
        }

        override fun log(line: String) {
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, line)
        }

        override fun stateChanged() = readState()
    }

    /** The network under any VPN: the one with internet and no VPN transport, preferring the default. */
    @Suppress("DEPRECATION") // allNetworks lists networks a callback has not reported yet.
    private fun underlyingNetwork(): Network? {
        val manager = connectivity ?: return null
        fun usable(network: Network): Boolean {
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val active = manager.activeNetwork
        if (active != null && usable(active)) return active
        return manager.allNetworks.firstOrNull(::usable)
    }

    companion object {
        private const val DEFAULT_MTU = 1500

        /** Pings in one probe: the direct path usually takes a second or two to be found. */
        private const val PROBE_PINGS = 5

        /** How long one ping may take; a DERP hop from behind a proxy can be well over a second. */
        private const val PROBE_WAIT_MS = 4_000
    }
}

/** Tailscale only accepts lowercase letters, digits and dashes in a node name. */
fun sanitizeHostname(raw: String): String = raw.lowercase()
    .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("")
    .trim('-')
    .take(63)

/** Node name Tailscale registers when the settings leave the hostname empty. */
fun defaultHostname(): String = sanitizeHostname(
    listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString("-"),
).ifBlank { "rexpocket" }

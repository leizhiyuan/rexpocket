package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.parseTailnetPath
import me.leaver.rexpocket.core.parseTailnetState
import me.leaver.rexpocket.core.peerFor
import me.leaver.rexpocket.diagnostics.Diagnostics
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Tag shared by the tailnet diagnostics; `adb logcat -s RexPocketTS` shows the nodes' own logs. */
const val LOG_TAG = "RexPocketTS"

/** What a running node tells its box: one line of its log, and that its state changed. */
interface NodeEvents {
    fun log(line: String)
    fun stateChanged()
}

/**
 * A node as the box uses it, whichever backend it is. The state and ping answers are the JSON both
 * backends report; see [parseTailnetState] and [parseTailnetPath].
 */
interface RunningNode {
    val proxyPort: Int
    fun state(): String
    fun ping(ip: String, timeoutMillis: Int): String

    /** The node's reason for its last failed dial to [host], or "" when there is none. */
    fun dialError(host: String): String
    fun networkChanged()
    fun close()
}

/**
 * The part of a tailnet node the backends share: starting and stopping it, reading its state into
 * [status] and its peers, measuring a path, and telling it when the phone's network changes. A
 * backend only opens its own node and says how its platform hooks work.
 */
abstract class TailnetBox(protected val context: Context) : TailnetNode {
    /** The backend's name, for the diagnostics and the errors it shows. */
    protected abstract val name: String

    /** Where the node keeps its login. It survives a restart, so a joined node comes straight back. */
    protected abstract val stateDirectory: File

    /**
     * Where account [profile] keeps its login: the first account (blank, or this backend's key) in
     * [stateDirectory], as before accounts; another one beside it, under `<name>-profiles/`.
     */
    protected fun directoryFor(profile: String): File =
        if (profile.isBlank() || profile == stateDirectory.name) stateDirectory
        else File(stateDirectory.parentFile, stateDirectory.name + "-profiles/" + sanitizeHostname(profile))

    override fun forgetProfile(profile: String) {
        val dir = directoryFor(profile)
        // Never the login of the node that runs: it is stopped first. The first account's is its old directory.
        if (node != null && running?.let { directoryFor(it.profile) } == dir) return
        // The account's login and node key go with it; nothing of it is read or logged.
        if (dir.exists()) dir.deleteRecursively()
        Diagnostics.event(LOG_TAG, "$name: an account's login was removed")
    }

    /** Opens the node for [config], with the hostname already cleaned up. Throws when it cannot. */
    protected abstract fun open(config: TailnetConfig, hostname: String, events: NodeEvents): RunningNode

    /** The message shown for a node that could not start. */
    protected open fun failureMessage(e: Exception): String = e.message?.ifBlank { null } ?: "$name could not start"

    private val _status = MutableStateFlow(TailnetStatus())
    override val status: StateFlow<TailnetStatus> = _status

    @Volatile private var node: RunningNode? = null
    @Volatile private var running: TailnetConfig? = null
    @Volatile private var port: Int = 0

    override val proxyPort: Int get() = port

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Reads the node's state off the callback, one read at a time, coalescing a burst of changes. */
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "tailnet-state") }
    private val readPending = AtomicBoolean(false)

    private val events = object : NodeEvents {
        override fun log(line: String) {
            if (me.leaver.rexpocket.BuildConfig.DEBUG) android.util.Log.d(LOG_TAG, "$name: $line")
        }

        override fun stateChanged() = readState()
    }

    /** Each peer's MagicDNS name, and its short host name, to its first tailnet address. */
    @Volatile private var peers: Map<String, String> = emptyMap()
        set(value) {
            field = value
            _peerNames.value = value.keys
        }

    private val _peerNames = MutableStateFlow<Set<String>>(emptySet())

    override fun knows(host: String): Boolean = peerFor(host, peers) != null

    override suspend fun awaitPeer(host: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { _peerNames.first { peerFor(host, peers) != null } } != null

    @Synchronized
    override fun start(config: TailnetConfig): Int {
        val cleanName = sanitizeHostname(config.hostname).ifBlank { defaultHostname() }
        running?.let {
            if (it.sameNode(config) && it.profile == config.profile && node != null) {
                Diagnostics.event(LOG_TAG, "$name: reusing the node on port $port (hostname=$cleanName)")
                return port
            }
            Diagnostics.event(LOG_TAG, "$name: restarting the node: config changed (hostname=$cleanName)")
        }
        Diagnostics.event(LOG_TAG, "$name: starting the node (hostname=$cleanName)")
        stopLocked()
        val started = try {
            directoryFor(config.profile).mkdirs()
            open(config, cleanName, events)
        } catch (e: Exception) {
            throw TailnetException(failureMessage(e), e)
        }
        node = started
        running = config
        port = started.proxyPort
        // No state of its own yet: the node says whether it needs a sign-in or connects straight away.
        _status.value = TailnetStatus()
        watchNetwork()
        readState()
        return port
    }

    override fun probePath(host: String): TailnetPath {
        val current = node ?: return TailnetPath.Unreachable("the node is not running")
        val ip = peerFor(host, peers) ?: return TailnetPath.Unreachable("$host is not a peer of this tailnet")
        // The first ping often still goes through a relay while the direct path is being set up;
        // the last one says where the traffic settled.
        var last: TailnetPath = TailnetPath.Unreachable("no answer")
        repeat(PROBE_PINGS) {
            last = runCatching { parseTailnetPath(current.ping(ip, PROBE_WAIT_MS)) }
                .getOrElse { TailnetPath.Unreachable(it.message ?: "ping failed") }
            if (last is TailnetPath.Direct) return last
        }
        return last
    }

    override fun dialError(host: String): String? {
        val current = node ?: return null
        // The Netbird proxy resolves a peer's name before it dials, so its failure is under the address.
        val names = listOfNotNull(host, peerFor(host, peers)).distinct()
        return names.firstNotNullOfOrNull { name ->
            runCatching { current.dialError(name) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }

    override fun resetNetwork() {
        val current = node ?: return
        runCatching { current.networkChanged() }.onFailure { Diagnostics.event(LOG_TAG, "$name: network reset failed: ${it.message}") }
    }

    @Synchronized
    override fun stop() {
        if (node != null) Diagnostics.event(LOG_TAG, "$name: stopping the node on port $port")
        runCatching { stopLocked() }.onFailure { Diagnostics.event(LOG_TAG, "$name: stop failed: ${it.message}") }
    }

    override fun stopAsync() {
        Thread({
            runCatching { stop() }.onFailure { Diagnostics.event(LOG_TAG, "$name: stop failed: ${it.message}") }
        }, "$name-stop").start()
    }

    private fun stopLocked() {
        unwatchNetwork()
        val current = node
        node = null
        running = null
        port = 0
        peers = emptyMap()
        _status.value = TailnetStatus()
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
            val snapshot = parseTailnetState(json)
            peers = snapshot.peerAddresses
            val next = TailnetStatus(
                backendState = snapshot.backendState,
                authUrl = snapshot.authUrl,
                networkName = snapshot.networkName,
                self = snapshot.self,
                peers = snapshot.peers,
                health = snapshot.health,
            )
            // Logged when what a report needs changed, not on every path or handshake the peers make.
            if (summary(next) != summary(_status.value)) Diagnostics.event(LOG_TAG, "$name: node ${summary(next)}")
            _status.value = next
        }
    }

    /** The state, the device count and what is wrong, for the diagnostics: names and addresses are left out. */
    private fun summary(s: TailnetStatus): String {
        val online = s.peers.count { it.online == true }
        return "state ${s.backendState.ifBlank { "-" }} self=${s.self != null} peers=${s.peers.size}" +
            (if (s.peers.any { it.online != null }) " online=$online" else "") +
            (if (s.health.isNotEmpty()) " health=${s.health.size}" else "")
    }

    // The node's own monitor may only poll; the app hears about a new network at once and passes it on.
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

    /** The network under any VPN: the one with internet and no VPN transport, preferring the default. */
    @Suppress("DEPRECATION") // allNetworks lists networks a callback has not reported yet.
    protected fun underlyingNetwork(): Network? {
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

    /** The phone's network interfaces as the connectivity manager describes them, for Tailscale. */
    protected val connectivityManager: ConnectivityManager? get() = connectivity

    companion object {
        /** Pings in one probe: the direct path usually takes a second or two to be found. */
        const val PROBE_PINGS = 5

        /** How long one ping may take; a relay hop from behind a proxy can be well over a second. */
        const val PROBE_WAIT_MS = 4_000
    }
}

/** Tailscale only accepts lowercase letters, digits and dashes in a node name. */
fun sanitizeHostname(raw: String): String = raw.lowercase()
    .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("")
    .trim('-')
    .take(63)

/** Node name the backends register when the settings leave the hostname empty. */
fun defaultHostname(): String = sanitizeHostname(
    listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString("-"),
).ifBlank { "rexpocket" }

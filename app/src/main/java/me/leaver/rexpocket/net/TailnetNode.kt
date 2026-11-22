package me.leaver.rexpocket.net

import kotlinx.coroutines.flow.StateFlow
import me.leaver.rexpocket.core.TailnetPath
import me.leaver.rexpocket.core.TailnetPeer
import me.leaver.rexpocket.core.TailnetSelf

/**
 * What the node reports about itself: its own state machine, a login link when one is needed, the
 * device it is on the network as, the network's other devices, and what it says is wrong.
 */
data class TailnetStatus(
    val backendState: String = "",
    val text: String = "",
    val authUrl: String = "",
    val networkName: String = "",
    val self: TailnetSelf? = null,
    val peers: List<TailnetPeer> = emptyList(),
    val health: List<String> = emptyList(),
) {
    /**
     * Whether the node has its device list: it runs and the network told it who it is. Before that
     * an empty list says nothing about whether a name is on the tailnet.
     */
    val peersKnown: Boolean get() = backendState == "Running" && self != null
}

/** What a node is started with. Each backend takes its own credential and settings. */
sealed interface TailnetConfig {
    val hostname: String

    /**
     * Whether [other] is the same node: the same settings, whatever credential it was started with.
     * A credential that was used once to join is not a reason to start the node again.
     */
    fun sameNode(other: TailnetConfig): Boolean = this == other
}

/** The built-in Tailscale node: an auth key is optional, the login is otherwise interactive. */
data class TailscaleConfig(val authKey: String, override val hostname: String) : TailnetConfig

/**
 * The built-in Netbird node. [setupKey] is needed the first time a node joins; afterwards it may be
 * blank, since the node's registration is kept. [managementUrl] is blank for Netbird Cloud.
 */
data class NetbirdConfig(
    val setupKey: String,
    override val hostname: String,
    val managementUrl: String,
) : TailnetConfig {
    override fun sameNode(other: TailnetConfig): Boolean =
        other is NetbirdConfig && other.hostname == hostname && other.managementUrl == managementUrl
}

class TailnetException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A network node the app runs itself, and the SOCKS5 proxy on loopback that dials through it. The
 * Rex connection of a host on the tailnet is pointed at that proxy. Tailscale and Netbird both
 * implement it, and only one is running at a time.
 */
interface TailnetNode {
    /** Live node state, so the home screen can say whether it still needs a login. */
    val status: StateFlow<TailnetStatus>

    /** Port of the local SOCKS5 proxy while the node runs, 0 otherwise. */
    val proxyPort: Int

    /** Whether the node can route to [host] yet: an address always, a name once the netmap lists it. */
    fun knows(host: String): Boolean

    /** Waits until [knows] says yes, for at most [timeoutMs]; true when it did. */
    suspend fun awaitPeer(host: String, timeoutMs: Long): Boolean

    /** How this phone's node reaches [host]. Blocking: call from an IO dispatcher. */
    fun probePath(host: String): TailnetPath

    /**
     * Why the node's last dial to [host] failed, in its own words, or null when it did not fail
     * lately. The SOCKS5 reply the connection saw cannot say: a refused port, an unknown name and a
     * peer that does not answer all look the same there.
     */
    fun dialError(host: String): String?

    /** Tells the node its network changed under it, so it finds its paths again at once. */
    fun resetNetwork()

    /** Starts the node for [config] and returns the SOCKS5 port, or reuses the running one. Blocking. */
    fun start(config: TailnetConfig): Int

    /** Stops the node and its proxy. */
    fun stop()

    /** Stops the node off the caller's thread; closing it can talk to the control plane. */
    fun stopAsync()
}

/**
 * The nodes the app can run, one per backend; [netbirdAvailable] says whether this build has the
 * Netbird one. A test hands in its own: the real nodes need the Go library, which only a phone loads.
 */
class TailnetNodes(
    val tailscale: TailnetNode,
    val netbird: TailnetNode,
    val netbirdAvailable: () -> Boolean,
) {
    companion object {
        fun of(context: android.content.Context): TailnetNodes =
            TailnetNodes(TailscaleBox(context), NetbirdBox(context)) { NetbirdBox.available() }
    }
}

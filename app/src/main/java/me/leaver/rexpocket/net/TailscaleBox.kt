package me.leaver.rexpocket.net

import android.content.Context
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.go.tailnet.Node
import me.leaver.rexpocket.go.tailnet.Platform
import me.leaver.rexpocket.go.tailnet.Tailnet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A Tailscale node running inside the app - the official tailscale.com tsnet library, through the
 * `tailnet` Go module - and a SOCKS5 proxy on loopback that dials through it. Nothing here touches
 * VpnService: the node runs in userspace, the phone keeps its own network, and only the sockets this
 * app opens for Rex go through the tunnel. See [TailnetBox] for the lifecycle both backends share.
 */
class TailscaleBox(context: Context) : TailnetBox(context) {
    /**
     * Whether the node's own UDP sockets skip a VPN on the phone and use the network under it. On
     * by default: a proxy VPN turns every direct path into a DERP relay. Read for every socket.
     */
    @Volatile var bypassVpn: Boolean = true

    override val name: String = "Tailscale"

    override val stateDirectory: File get() = File(context.filesDir, "tailscale")

    override fun open(config: TailnetConfig, hostname: String, events: NodeEvents): RunningNode {
        val tailscale = config as? TailscaleConfig ?: throw IllegalArgumentException("Tailscale needs a Tailscale config")
        dropSingBoxLeftovers()
        val node = Tailnet.start(directoryFor(config.profile).absolutePath, hostname, tailscale.authKey, platform(events))
        return TailscaleRunning(node)
    }

    override fun failureMessage(e: Exception): String {
        val text = e.message.orEmpty()
        return if (text.contains("auth", ignoreCase = true) || text.contains("login", true)) {
            "$text (check the Tailscale auth key)"
        } else {
            text.ifBlank { "Tailscale could not start" }
        }
    }

    /**
     * The node used to run inside sing-box, which kept its cache and crash log in `files/libbox`,
     * and its configuration and command socket beside it. The login itself was always in the state
     * directory, in the format tsnet reads, so it carries over; the rest is of no use now.
     */
    private fun dropSingBoxLeftovers() {
        val files = context.filesDir
        val removed = listOf(File(files, "libbox"), File(files, "configuration.json"), File(files, "command.sock"))
            // A socket is neither a file nor a directory to the tree walk, which then deletes
            // nothing and reports success: only a directory is walked.
            .filter { it.exists() && (if (it.isDirectory) it.deleteRecursively() else it.delete()) }
        if (removed.isNotEmpty()) Diagnostics.event(LOG_TAG, "removed the sing-box leftovers: ${removed.joinToString { it.name }}")
    }

    private fun platform(events: NodeEvents): Platform = object : Platform {
        /**
         * The phone's interfaces, for the node's network monitor: Go cannot list them on Android,
         * where apps get no netlink. Every network the phone has, each with its addresses.
         */
        @Suppress("DEPRECATION") // allNetworks also lists networks a callback has not reported yet.
        override fun interfaces(): String {
            val manager = connectivityManager ?: return "[]"
            val out = JSONArray()
            for (network in manager.allNetworks) {
                val link = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
                val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
                val ifName = link.interfaceName ?: continue
                val java = runCatching { java.net.NetworkInterface.getByName(ifName) }.getOrNull() ?: continue
                out.put(
                    JSONObject()
                        .put("name", ifName)
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

        override fun log(line: String) = events.log(line)

        override fun stateChanged() = events.stateChanged()
    }

    private class TailscaleRunning(private val node: Node) : RunningNode {
        override val proxyPort: Int get() = node.proxyPort()
        override fun state(): String = node.state()
        override fun ping(ip: String, timeoutMillis: Int): String = node.ping(ip, timeoutMillis)
        override fun dialError(host: String): String = node.dialError(host)
        override fun networkChanged() = node.networkChanged()
        override fun close() = node.close()
    }

    companion object {
        private const val DEFAULT_MTU = 1500
    }
}

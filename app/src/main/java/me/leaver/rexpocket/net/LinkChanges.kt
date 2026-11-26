package me.leaver.rexpocket.net

import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import java.net.Inet6Address
import java.net.InetAddress

/** What a tailnet node's sockets depend on: the default network, its interface and its addresses. */
internal data class LinkKey(val network: Any?, val iface: String?, val addresses: Set<String>)

/** [linkProperties] of [network] as a [LinkKey]. */
internal fun linkKey(network: Network, linkProperties: LinkProperties) =
    linkKey(network, linkProperties.interfaceName, linkProperties.linkAddresses.mapNotNull { a -> a.address?.let { it to a.flags } })

/**
 * The key of [addresses] (each with its `IFA_F_*` flags) on [iface]. IPv6 link-local addresses and
 * temporary (privacy) ones are left out: a socket bound to the wildcard keeps working when a
 * privacy address rotates, which on mobile data is every few hours.
 */
internal fun linkKey(network: Any?, iface: String?, addresses: List<Pair<InetAddress, Int>>) = LinkKey(
    network,
    iface,
    addresses
        .filterNot { (a, flags) -> a is Inet6Address && (a.isLinkLocalAddress || (flags and IFA_F_TEMPORARY) != 0) }
        .mapNotNull { it.first.hostAddress }
        .toSet(),
)

/** The kernel's flag for an IPv6 temporary (privacy) address, as [android.net.LinkAddress.getFlags] has it. */
private const val IFA_F_TEMPORARY = 0x01

/**
 * Whether a default network's link changed in a way the node must hear of: another network,
 * interface, or set of addresses. A change of only its DNS servers or routes is not; nor is the
 * first look, which is the network the node started on.
 */
internal class LinkChanges {
    private var last: LinkKey? = null

    @Synchronized
    fun changed(now: LinkKey): Boolean {
        val was = last
        last = now
        return was != null && was != now
    }

    companion object {
        /** How long a burst of link changes has to settle before the node rebinds. */
        const val DEBOUNCE_MS = 2_000L
    }
}

/** Runs [action] on the main thread [delayMs] after the last [poke]. */
internal class Debounce(private val delayMs: Long, action: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val run = Runnable(action)

    fun poke() {
        handler.removeCallbacks(run)
        handler.postDelayed(run, delayMs)
    }

    fun cancel() = handler.removeCallbacks(run)
}

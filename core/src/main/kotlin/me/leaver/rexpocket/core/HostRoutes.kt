package me.leaver.rexpocket.core

// A host's other addresses: which of them are dialed, in what order, which failures move on to the
// next one, and which address is in use now. See docs/design/multi-host.md, "Other addresses".

/**
 * One address a host can be dialed at. [key] is the address as the phone writes it
 * (`ssh://user@host:port`, `ws://host:port`, `wss://host:port`), which is how the last one that
 * worked is remembered; [main] is the host's own address, the others its fallbacks.
 */
data class HostRoute(
    val key: String,
    val endpoint: Endpoint,
    /** Dialed through the app's tailnet node rather than the phone's own network. */
    val viaTailnet: Boolean,
    val main: Boolean,
)

/** Why an address on a host's list is not dialed: the list shows these, and the dial skips them. */
enum class RouteSkip {
    /** Not an address the phone can read. */
    Unreadable,

    /** An `ssh://` address for a host reached on Rex's port, or the other way round. */
    OtherKind,

    /** Plain `ws://` for a host whose own address is TLS: the other address would be weaker. */
    Weaker,

    /** The host's own address again. */
    Same,
}

object HostRoutes {
    /**
     * How long an other address that worked stays first. After that the host's own address is
     * tried first again, so a phone back on the network the main address is for goes back to it on
     * its next connection; a connection that is up is never moved.
     */
    const val PREFER_LAST_MS = 30 * 60_000L

    /**
     * How long one address may take to answer Rex's hello while there are others to try in the
     * first round. Refused and unknown addresses fail at once; this is for one that drops packets
     * (a LAN address away from home). The last address of a round, and every address after a round
     * in which none answered, gets the usual [FULL_CONNECT_MS], so a slow but working one still connects.
     */
    const val ROUTE_CONNECT_MS = 8_000L

    /** The hello's usual time limit ([RexClient]'s own). */
    const val FULL_CONNECT_MS = 12_000L

    /** The key of an address made of these parts. */
    fun key(host: String, port: Int, plaintext: Boolean, sshUser: String?): String =
        if (!sshUser.isNullOrBlank()) "ssh://$sshUser@$host:$port" else "${if (plaintext) "ws" else "wss"}://$host:$port"

    /**
     * The route of [raw], one of a host's other addresses, or why it is not dialed. An `ssh://`
     * address that names no account uses the host's own ([sshUser]). A host reached over SSH is
     * only dialed over SSH, and one reached on Rex's port only on Rex's port, so its credentials and
     * the checks that go with them (the host key, TLS) stay what the person set up; a TLS host is
     * never dialed in plain text.
     */
    fun fallback(raw: String, main: HostRoute, sshUser: String?): Pair<HostRoute?, RouteSkip?> {
        val parsed = HostAddressParser.parse(raw) ?: return null to RouteSkip.Unreadable
        val user = if (parsed.viaSsh) (parsed.sshUser ?: sshUser?.takeIf { it.isNotBlank() } ?: return null to RouteSkip.Unreadable) else null
        if (parsed.viaSsh != main.endpoint.viaSsh) return null to RouteSkip.OtherKind
        if (!main.endpoint.viaSsh && !main.endpoint.plaintext && parsed.plaintext) return null to RouteSkip.Weaker
        val route = HostRoute(
            key = key(parsed.host, parsed.port, parsed.plaintext, user),
            endpoint = Endpoint(parsed.host, parsed.port, parsed.plaintext, sshUser = user),
            viaTailnet = parsed.viaTailscale,
            main = false,
        )
        if (route.key.equals(main.key, ignoreCase = true)) return null to RouteSkip.Same
        return route to null
    }

    /**
     * Every address to try, in order: the host's own first, then the others as listed, but the one
     * that worked last ([lastKey], at [lastAt]) first while it is less than [PREFER_LAST_MS] old.
     * Only the host's own address when [tryOthers] is off.
     */
    fun plan(main: HostRoute, fallbacks: List<String>, sshUser: String?, tryOthers: Boolean, lastKey: String?, lastAt: Long, now: Long): List<HostRoute> {
        if (!tryOthers) return listOf(main)
        val others = fallbacks.mapNotNull { fallback(it, main, sshUser).first }.distinctBy { it.key.lowercase() }
        val all = listOf(main) + others
        val last = lastKey?.takeIf { now - lastAt in 0 until PREFER_LAST_MS }?.let { k -> all.firstOrNull { it.key.equals(k, ignoreCase = true) } }
        return if (last == null || last.main) all else listOf(last) + all.filter { it !== last }
    }

    /**
     * Whether a dial that failed with [problem] moves on to the next address: only when this address
     * could not be reached - no route, refused, an unknown name, no answer, the tailnet or its route
     * to the host down, nothing on Rex's port. A refused password or key, a host key that is not
     * the trusted one, Rex refusing or failing to run, and a connection that was up and dropped are
     * the machine's own answer: another address of the same machine would answer the same, and a
     * host key that differs there is never taken as the host's.
     */
    fun fallsThrough(problem: Problem?): Boolean = when (problem?.trouble) {
        Trouble.Unreachable, Trouble.TimedOut, Trouble.TailnetNotJoined, Trouble.Tailnet, Trouble.TailnetNeedsLogin,
        Trouble.TailnetPeerUnknown, Trouble.TailnetPeerOffline, Trouble.RexNotListening -> true
        else -> false
    }
}

/**
 * The addresses one connection goes through, and the one in use: [current] is what the next dial
 * uses. A dial that fails in a way that [HostRoutes.fallsThrough] moves it to the next address at
 * once; once every address failed, the next round starts from the first again, after the
 * connection's usual wait. Thread-safe: the dial loop moves it, the screens read it.
 */
class RouteCursor(val routes: List<HostRoute>, private val onLive: (HostRoute) -> Unit = {}) {
    init {
        require(routes.isNotEmpty())
    }

    @Volatile private var index = 0

    /** Rounds that ended with no address reached, since the last time one was. */
    @Volatile var round = 0
        private set

    /** Each address's last failure in this round, by key, for the connection's details. */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Problem>()

    val current: HostRoute get() = routes[index]

    /** Whether the address in use is one of the host's others. */
    val onFallback: Boolean get() = !current.main

    /** The failures of this round, in the order the addresses are tried. */
    fun failures(): List<Pair<HostRoute, Problem>> = routes.mapNotNull { r -> failures[r.key]?.let { r to it } }

    /**
     * The dial on [current] failed with [problem]. True when there is another address to try now;
     * false when this is the machine's own answer or the last address of the round, and the cursor
     * is back at the first address for the next round.
     */
    @Synchronized
    fun failed(problem: Problem): Boolean {
        failures[current.key] = problem
        if (HostRoutes.fallsThrough(problem) && index < routes.lastIndex) {
            index++
            return true
        }
        // A refused key or a changed host key stays on the address that said so, for the person to see.
        if (HostRoutes.fallsThrough(problem)) {
            index = 0
            round++
        }
        return false
    }

    /** The connection is up on [current]: it is remembered, and the round's failures are over. */
    fun live() {
        failures.clear()
        round = 0
        onLive(current)
    }

    /** How long the dial on [current] may take to answer: see [HostRoutes.ROUTE_CONNECT_MS]. */
    fun connectTimeoutMs(): Long =
        if (round == 0 && index < routes.lastIndex) HostRoutes.ROUTE_CONNECT_MS else HostRoutes.FULL_CONNECT_MS
}

/** How a route goes through the tailnet: the built-in node's SOCKS port, or the add-on's dialer. */
data class RouteVia(val socksPort: Int = 0, val dialer: StreamDialer? = null)

/**
 * The transports of one host's connection over a [RouteCursor]: a direct or tailnet HTTP client
 * for a Rex port, an SSH tunnel per SSH address, made when that address is first dialed and kept
 * while the connection lives. Every address of a host reached over SSH is checked against the one
 * host key the person trusted for it ([sshSettings] gives it); there is no per-address trust.
 */
class HostDialer(
    val cursor: RouteCursor,
    /** The SSH settings for a route, or null for a host on Rex's port. */
    private val sshSettings: (HostRoute, RouteVia) -> SshSettings?,
    private val trace: (String) -> Unit = {},
) : java.io.Closeable {
    private val via = java.util.concurrent.ConcurrentHashMap<String, RouteVia>()
    private val tunnels = java.util.concurrent.ConcurrentHashMap<String, Pair<RouteVia, SshTunnel>>()

    /** How [route] goes through the tailnet, set before it is dialed; `RouteVia()` for the phone's network. */
    fun setVia(route: HostRoute, value: RouteVia) {
        via[route.key] = value
    }

    private fun viaOf(route: HostRoute) = via[route.key] ?: RouteVia()

    /** The SSH tunnel of the address in use, or null for a host on Rex's port. */
    @Synchronized
    fun tunnel(): SshTunnel? {
        val route = cursor.current
        val v = viaOf(route)
        tunnels[route.key]?.let { (made, t) -> if (made == v) return t else t.close() }
        val settings = sshSettings(route, v) ?: return null
        return SshTunnel(settings, trace).also { tunnels[route.key] = v to it }
    }

    fun http(): okhttp3.OkHttpClient = tunnel()?.http() ?: viaOf(cursor.current).let { RexClient.defaultHttp(it.socksPort, it.dialer) }

    /** A Rex client for the address in use, with its time limit for the hello. */
    fun client(apiRevision: Int = 7): RexClient {
        val timeout = cursor.connectTimeoutMs()
        val t = tunnel()
        return if (t != null) RexClient(t.http(), timeout, apiRevision, transportFailure = { t.lastFailure() })
        else RexClient(http(), timeout, apiRevision)
    }

    override fun close() {
        tunnels.values.forEach { runCatching { it.second.close() } }
        tunnels.clear()
    }
}

package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * A tailnet node's state as its Go side reports it: the backend state, a login URL while one is
 * needed, the network's name, the node itself, and each peer.
 */
data class TailnetSnapshot(
    val backendState: String = "",
    val authUrl: String = "",
    val networkName: String = "",
    /** Each peer's MagicDNS name and short host name, lowercased, to its first address. */
    val peerAddresses: Map<String, String> = emptyMap(),
    /** Every peer the node lists, including one with no address yet. */
    val peerCount: Int = 0,
    /** The node itself, once the network has told it who it is. */
    val self: TailnetSelf? = null,
    /** Every peer the node lists, in the order it listed them. */
    val peers: List<TailnetPeer> = emptyList(),
    /** What the node says is wrong with it, in its own words: Tailscale's health warnings, Netbird's control plane. */
    val health: List<String> = emptyList(),
)

/** This phone's node as the network knows it. */
data class TailnetSelf(
    val dnsName: String = "",
    val hostName: String = "",
    val ips: List<String> = emptyList(),
    val os: String = "",
    /** The account the node is signed in as; Tailscale only. */
    val user: String = "",
    /** The node's home relay region, such as `fra`; Tailscale only. */
    val homeRelay: String = "",
    /** When the node's key expires and it has to sign in again, in epoch milliseconds. */
    val keyExpiry: Long? = null,
) {
    /** The name the device goes by: its host name, or the first label of its MagicDNS name. */
    val name: String get() = hostName.ifBlank { dnsName.substringBefore('.') }
}

/** How the node reaches a peer right now, without a ping: what it already knows. */
enum class PeerLink {
    /** Straight to the peer over UDP. */
    Direct,

    /** Through another node of the tailnet. */
    PeerRelay,

    /** Through one of Tailscale's DERP servers. */
    Derp,

    /** Through one of Netbird's relay servers. */
    Relay,

    /** The connection is being set up. */
    Connecting,

    /** Nothing was sent to the peer yet, so there is no path to tell; it comes up on the first packet. */
    Idle,

    /** A node that does not say. */
    Unknown;

    companion object {
        fun from(value: String): PeerLink = when (value) {
            "direct" -> Direct
            "peerRelay" -> PeerRelay
            "derp" -> Derp
            "relay" -> Relay
            "connecting" -> Connecting
            "idle" -> Idle
            else -> Unknown
        }
    }
}

/** One device of the tailnet, as the node lists it. */
data class TailnetPeer(
    val dnsName: String,
    val hostName: String = "",
    val ips: List<String> = emptyList(),
    val os: String = "",
    /** Whether the control plane sees the device; null where the node cannot tell (Netbird). */
    val online: Boolean? = null,
    /** When an offline device was last seen, in epoch milliseconds. */
    val lastSeen: Long? = null,
    val link: PeerLink = PeerLink.Unknown,
    /** The endpoint, relay or region of [link]. */
    val linkDetail: String = "",
    /** The latency the node measured on its own; Netbird only. */
    val latencyMs: Double? = null,
) {
    /** The name the device goes by: its host name, or the first label of its MagicDNS name. */
    val name: String get() = hostName.ifBlank { dnsName.substringBefore('.') }

    /** Whether [host], as typed for a saved host, names this device: its MagicDNS name, short name or address. */
    fun answersTo(host: String): Boolean {
        val want = host.trim().trim('[', ']').trimEnd('.').lowercase()
        if (want.isEmpty()) return false
        val full = dnsName.trimEnd('.').lowercase()
        return want == full || want == hostName.lowercase() ||
            (full.isNotEmpty() && '.' !in want && want == full.substringBefore('.')) ||
            ips.any { it.equals(want, ignoreCase = true) }
    }
}

/** The peer [host] names among [peers], or null when none does. */
fun List<TailnetPeer>.named(host: String): TailnetPeer? = firstOrNull { it.answersTo(host) }

/**
 * Reads the state JSON both nodes report: `{"backendState", "authUrl", "tailnet", "self",
 * "selfInfo", "health", "peers": [{"dnsName", "hostName", "ips": [...], "os", "online",
 * "lastSeen", "link", "linkDetail", "latencyMs"}]}`. A peer without an address is counted but
 * cannot be dialed, so it is left out of the address map. Every field after the first few may be
 * missing: an older library, or a node that has no answer for it.
 */
fun parseTailnetState(json: String): TailnetSnapshot {
    val o = JSONObject(json)
    val list = o.optJSONArray("peers") ?: JSONArray()
    val addresses = HashMap<String, String>()
    val peers = ArrayList<TailnetPeer>()
    for (i in 0 until list.length()) {
        val peer = list.optJSONObject(i) ?: continue
        val ips = peer.optJSONArray("ips").strings()
        val dnsName = peer.optString("dnsName")
        val hostName = peer.optString("hostName")
        peers += TailnetPeer(
            dnsName = dnsName,
            hostName = hostName,
            ips = ips,
            os = peer.optString("os"),
            online = if (peer.has("online") && !peer.isNull("online")) peer.optBoolean("online") else null,
            lastSeen = instant(peer.optString("lastSeen")),
            link = PeerLink.from(peer.optString("link")),
            linkDetail = peer.optString("linkDetail"),
            latencyMs = peer.optDouble("latencyMs").takeIf { !it.isNaN() && it > 0 },
        )
        val first = ips.firstOrNull() ?: continue
        dnsName.lowercase().takeIf { it.isNotEmpty() }?.let { addresses[it] = first }
        hostName.lowercase().takeIf { it.isNotEmpty() }?.let { addresses[it] = first }
    }
    val selfName = o.optString("self")
    val self = o.optJSONObject("selfInfo")?.let { s ->
        TailnetSelf(
            dnsName = s.optString("dnsName").ifBlank { selfName },
            hostName = s.optString("hostName"),
            ips = s.optJSONArray("ips").strings(),
            os = s.optString("os"),
            user = s.optString("user"),
            homeRelay = s.optString("relay"),
            keyExpiry = instant(s.optString("keyExpiry")),
        )
    } ?: selfName.takeIf { it.isNotBlank() }?.let { TailnetSelf(dnsName = it) }
    return TailnetSnapshot(
        backendState = o.optString("backendState"),
        authUrl = o.optString("authUrl"),
        networkName = o.optString("tailnet"),
        peerAddresses = addresses,
        peerCount = list.length(),
        self = self,
        peers = peers,
        health = o.optJSONArray("health").strings(),
    )
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}

private fun instant(text: String): Long? =
    text.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/**
 * Reads one ping result from either node's JSON: `{"latencyMs", "endpoint", "peerRelay",
 * "derpRegionId", "derpRegionCode", "relayServer", "error"}`. A node that cannot tell a path leaves
 * the fields it has no answer for empty.
 */
fun parseTailnetPath(json: String): TailnetPath {
    val o = JSONObject(json)
    return TailnetPath.from(
        o.optDouble("latencyMs", 0.0),
        o.optString("endpoint"),
        o.optString("peerRelay"),
        o.optLong("derpRegionId").toInt(),
        o.optString("derpRegionCode"),
        o.optString("error"),
        relayServer = o.optString("relayServer"),
    )
}

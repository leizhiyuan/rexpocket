package me.leaver.rexpocket.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A tailnet node's state as its Go side reports it: the backend state, a login URL while one is
 * needed, the network's name, and each peer's addresses.
 */
data class TailnetSnapshot(
    val backendState: String = "",
    val authUrl: String = "",
    val networkName: String = "",
    /** Each peer's MagicDNS name and short host name, lowercased, to its first address. */
    val peerAddresses: Map<String, String> = emptyMap(),
    /** Every peer the node lists, including one with no address yet. */
    val peerCount: Int = 0,
)

/**
 * Reads the state JSON both nodes report: `{"backendState", "authUrl", "tailnet", "self",
 * "peers": [{"dnsName", "hostName", "ips": [...]}]}`. A peer without an address is counted but
 * cannot be dialed, so it is left out of the map.
 */
fun parseTailnetState(json: String): TailnetSnapshot {
    val o = JSONObject(json)
    val list = o.optJSONArray("peers") ?: JSONArray()
    val addresses = HashMap<String, String>()
    for (i in 0 until list.length()) {
        val peer = list.optJSONObject(i) ?: continue
        val first = peer.optJSONArray("ips")?.optString(0)?.takeIf { it.isNotEmpty() } ?: continue
        peer.optString("dnsName").lowercase().takeIf { it.isNotEmpty() }?.let { addresses[it] = first }
        peer.optString("hostName").lowercase().takeIf { it.isNotEmpty() }?.let { addresses[it] = first }
    }
    return TailnetSnapshot(
        backendState = o.optString("backendState"),
        authUrl = o.optString("authUrl"),
        networkName = o.optString("tailnet"),
        peerAddresses = addresses,
        peerCount = list.length(),
    )
}

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

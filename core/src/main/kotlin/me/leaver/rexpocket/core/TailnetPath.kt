package me.leaver.rexpocket.core

/**
 * How this phone's tailnet traffic reaches a peer, as a disco ping saw it. Tailscale tries for a
 * direct UDP path first; when NAT or a firewall stands in the way it falls back to a relay - a peer
 * relay inside the tailnet, or a DERP server run by Tailscale - and every packet takes that detour.
 */
sealed interface TailnetPath {
    /** A round trip in milliseconds, for every path that answered. */
    val latencyMs: Double?

    /** Straight to the peer over UDP: the fast case, as on the same Wi-Fi. */
    data class Direct(val endpoint: String, override val latencyMs: Double) : TailnetPath

    /** Through another node of this tailnet that offered to relay. */
    data class PeerRelay(val relay: String, override val latencyMs: Double) : TailnetPath

    /** Through a DERP server: a relay over TCP and TLS, often far away, and slow from behind a proxy. */
    data class Derp(val regionId: Int, val regionCode: String, override val latencyMs: Double) : TailnetPath

    /** The ping did not come back. */
    data class Unreachable(val reason: String) : TailnetPath {
        override val latencyMs: Double? get() = null
    }

    companion object {
        /**
         * Reads one ping result. Tailscale fills [endpoint] for a direct path, [peerRelay] for a
         * peer relay, and [derpRegionId] otherwise; an [error] means nothing answered.
         */
        fun from(
            latencyMs: Double,
            endpoint: String?,
            peerRelay: String?,
            derpRegionId: Int,
            derpRegionCode: String?,
            error: String?,
        ): TailnetPath = when {
            !error.isNullOrBlank() -> Unreachable(error)
            !endpoint.isNullOrBlank() -> Direct(endpoint, latencyMs)
            !peerRelay.isNullOrBlank() -> PeerRelay(peerRelay, latencyMs)
            derpRegionId > 0 -> Derp(derpRegionId, derpRegionCode.orEmpty(), latencyMs)
            else -> Unreachable("no path reported")
        }
    }
}

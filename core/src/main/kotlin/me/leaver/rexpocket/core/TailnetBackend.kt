package me.leaver.rexpocket.core

/**
 * The network the app runs itself to reach a tailnet: a Tailscale node or a Netbird node. Only one
 * of them runs at a time, and a host marked as on the tailnet is reached through whichever is joined.
 */
enum class TailnetBackend(val storageKey: String) {
    Tailscale("tailscale"),
    Netbird("netbird");

    companion object {
        /** The stored name; anything unknown is Tailscale, the backend the app shipped with. */
        fun fromStorage(value: String?): TailnetBackend = entries.firstOrNull { it.storageKey == value } ?: Tailscale
    }
}

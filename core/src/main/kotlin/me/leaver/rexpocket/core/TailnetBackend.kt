package me.leaver.rexpocket.core

/**
 * The network the app runs itself to reach a tailnet: a Tailscale node or a Netbird node. Only one
 * of them runs at a time, and a host marked as on the tailnet is reached through whichever is joined.
 *
 * Each backend says here what the screens need to know about it: the name it is shown by, and
 * whether its node signs in interactively. A new backend is one entry here and one node registered
 * in the app's TailnetNodes.
 */
enum class TailnetBackend(
    val storageKey: String,
    /** The network's own name, as the screens show it. */
    val displayName: String,
    /** Whether the node signs in through a login link; one that joins with a key skips that step. */
    val signsIn: Boolean = true,
) {
    Tailscale("tailscale", "Tailscale"),
    Netbird("netbird", "Netbird", signsIn = false);

    companion object {
        /** The stored name; anything unknown is Tailscale, the backend the app shipped with. */
        fun fromStorage(value: String?): TailnetBackend = entries.firstOrNull { it.storageKey == value } ?: Tailscale
    }
}

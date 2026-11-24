package me.leaver.rexpocket.core

// The watchdog of a node that can get stuck: one whose control connection fails and does not come
// back on its own (a dial started while Android still blocked the app's network, a stream that died
// in Doze, a network change the node never heard of). It stays "Starting" with a health line such
// as "management: rpc error: code = DeadlineExceeded". Stopping and starting it again is what a
// person did by hand (Leave, then Join); the watchdog does it for them, with a backoff, and only
// while something needs the node. See docs/design/tailnet-control.md, "Watchdog".

/**
 * What is wrong with a node in [backendState] reporting [health], for the watchdog: null while it
 * runs (or waits for a sign-in, or has not said anything yet), else a short reason for the
 * diagnostics: the gRPC code of its control connection's failure, or that it is still starting.
 */
fun nodeTrouble(backendState: String, health: List<String>): String? {
    if (backendState != "Starting") return null
    val failing = health.firstOrNull { line -> CONTROL_FAILURES.any { line.contains(it, ignoreCase = true) } }
        ?: return "still starting"
    val part = failing.substringBefore(':').trim().takeIf { it.isNotEmpty() && it.length <= 16 && ' ' !in it }
    val code = CONTROL_FAILURES.first { failing.contains(it, ignoreCase = true) }
    return "still starting" + (part?.let { " · $it" }.orEmpty()) + " · $code"
}

/** The failures of a control connection that a fresh start can cure. */
private val CONTROL_FAILURES = listOf("DeadlineExceeded", "Unavailable", "deadline exceeded", "connection refused", "network is unreachable")

/**
 * Whether a start that failed with [message] is worth trying again by itself: the network or the
 * control server did not answer. A wrong or missing key is not.
 */
fun transientStartFailure(message: String?): Boolean {
    val m = message ?: return false
    return TRANSIENT.any { m.contains(it, ignoreCase = true) }
}

private val TRANSIENT = listOf(
    "deadline exceeded", "DeadlineExceeded", "Unavailable", "timed out", "timeout", "i/o timeout",
    "connection refused", "connection reset", "network is unreachable", "no route to host", "no such host",
)

/** How long after its [restarts]th restart the watchdog waits before another: 15 s, doubling, at most 5 min. */
fun watchdogBackoffMs(restarts: Int): Long = (15_000L shl (restarts - 1).coerceIn(0, 5)).coerceAtMost(300_000L)

/**
 * The watchdog of one node: when it has been in trouble ([nodeTrouble]) for [stuckAfterMs] while
 * something needs it, it is restarted; then not again before the backoff. Healthy resets it.
 * Pure: the caller feeds it the node's trouble and the clock, and does the restart.
 */
class NodeWatchdog(
    val stuckAfterMs: Long = STUCK_AFTER_MS,
    private val backoff: (Int) -> Long = ::watchdogBackoffMs,
) {
    private var troubleSince: Long? = null
    private var notBefore = 0L

    /** Restarts since the node was last healthy. */
    var restarts = 0
        private set

    /** Notes the node's [trouble] at [now]; true when it should be restarted now. */
    fun observe(trouble: String?, needed: Boolean, now: Long): Boolean {
        if (trouble == null) {
            troubleSince = null
            restarts = 0
            notBefore = 0
            return false
        }
        val since = troubleSince ?: now.also { troubleSince = it }
        return needed && now - since >= stuckAfterMs && now >= notBefore
    }

    /**
     * Whether the node is known to be stuck at [now]: in trouble for the whole bound. A connect is
     * not handed such a node as it is; it is restarted first, backoff or not.
     */
    fun stuck(now: Long): Boolean = troubleSince?.let { now - it >= stuckAfterMs } == true

    /** Whether the node is in trouble at all now: a new network is a reason to restart it at once. */
    val troubled: Boolean get() = troubleSince != null

    /** The node was restarted at [now]: its next trouble is timed afresh, and the backoff grows. */
    fun restarted(now: Long) {
        restarts++
        notBefore = now + backoff(restarts)
        troubleSince = null
    }

    /** The node stopped, or another one runs: nothing is known about it. */
    fun reset() {
        troubleSince = null
        restarts = 0
        notBefore = 0
    }

    companion object {
        /** How long a node may stay in trouble before it is restarted. */
        const val STUCK_AFTER_MS = 20_000L
    }
}

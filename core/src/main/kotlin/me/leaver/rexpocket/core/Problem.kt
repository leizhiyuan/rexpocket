package me.leaver.rexpocket.core

/** Where something went wrong, so the UI can say it in the user's words instead of the exception's. */
enum class Trouble {
    /** The host could not be reached at all: refused, no route, unknown name. */
    Unreachable,

    /** The host took the connection, or the request, and did not answer in time. */
    TimedOut,

    /** A connection that was up went away. */
    Dropped,

    /** Rex answered with an error; [Problem.detail] is its message. */
    Rejected,

    /** The action needs a connection or a session that is not there yet. */
    NotReady,

    /** The host is on a tailnet and the phone has not joined one. */
    TailnetNotJoined,

    /** The app's own Tailscale node could not start. */
    Tailnet,

    /**
     * The phone's tailnet node needs a sign-in before it reaches anything; [Problem.detail] is
     * [APPROVAL] when it is signed in and waits for the tailnet's admin instead.
     */
    TailnetNeedsLogin,

    /** The tailnet has no device by the host's name. */
    TailnetPeerUnknown,

    /** The host's device is offline on the tailnet; [Problem.detail] is when it was last seen, in epoch ms. */
    TailnetPeerOffline,

    /** The host answered over the tailnet, but nothing listens on Rex's port; [Problem.detail] is the port. */
    RexNotListening,

    /** The machine's SSH key has not been confirmed yet; [Problem.detail] is its fingerprint. */
    SshHostKeyUnknown,

    /** The machine's SSH key is not the one that was trusted; [Problem.detail] is the new one. */
    SshHostKeyChanged,

    /** SSH refused the password or the key, or the key could not be read. */
    SshAuth,

    /** SSH reached the machine, but Rex is not installed or cannot run there. */
    SshCommand,

    Other,
}

data class Problem(val trouble: Trouble, val detail: String?)

/**
 * An action failed while the connection itself is fine. [id] tells two equal failures apart.
 * [blockId] is the pane the failure belongs to, when it belongs to one: such a notice lasts only
 * while that pane is the one on screen, and goes once the pane closes, is left, or recovers.
 */
data class Notice(val id: Long, val problem: Problem, val blockId: String? = null)

/** Rex took the request, or the connection, and did not answer in time. Not a cancellation. */
class RexTimeoutException(message: String) : Exception(message)

/** The control connection closed after it was up. */
class ConnectionDroppedException(message: String, cause: Throwable?) : Exception(message, cause)

/** The pane being watched is gone from its session: closed, not failed, so its stream stops. */
class PaneClosedException(message: String) : Exception(message)

/** An action that needs a connection, a session or a pane before there is one. */
class NotReadyException(message: String) : IllegalStateException(message)

/**
 * OkHttp can reduce a failure under a socket it was given to "exhausted all routes", dropping the
 * reason. When the transport knows what happened, and the generic failure says nothing specific,
 * the transport's exception is the one the person needs to see.
 */
fun Throwable.explainedBy(transport: Throwable?): Throwable {
    if (transport == null) return this
    return when (toProblem().trouble) {
        Trouble.Other, Trouble.Unreachable, Trouble.TimedOut -> transport
        else -> this
    }
}

/** Classifies by exception type along the cause chain; the message is kept as detail. */
fun Throwable.toProblem(): Problem {
    val chain = generateSequence(this) { it.cause }.toList()
    chain.firstOrNull { it is SshHostKeyException }?.let { error ->
        val ssh = error as SshHostKeyException
        return Problem(
            if (ssh.changed) Trouble.SshHostKeyChanged else Trouble.SshHostKeyUnknown,
            ssh.fingerprint,
        )
    }
    chain.firstOrNull { it is SshAuthException }?.let { error ->
        return Problem(Trouble.SshAuth, error.message)
    }
    chain.firstOrNull { it is SshCommandException }?.let { error ->
        return Problem(Trouble.SshCommand, error.message)
    }
    val trouble = when {
        this is ConnectionDroppedException -> Trouble.Dropped
        this is NotReadyException -> Trouble.NotReady
        chain.any { it is RexCallException } -> Trouble.Rejected
        chain.any { it is RexTimeoutException || it is java.net.SocketTimeoutException } -> Trouble.TimedOut
        // EOF during the handshake: the port took the connection and closed it, nothing is listening.
        chain.any {
            it is java.net.ConnectException || it is java.net.UnknownHostException ||
                it is java.net.NoRouteToHostException || it is java.io.EOFException
        } -> Trouble.Unreachable
        else -> Trouble.Other
    }
    val detail = (chain.firstOrNull { it is RexCallException } ?: this).message
    return Problem(trouble, detail)
}

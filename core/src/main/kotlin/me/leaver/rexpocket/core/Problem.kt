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

    Other,
}

data class Problem(val trouble: Trouble, val detail: String?)

/** An action failed while the connection itself is fine. [id] tells two equal failures apart. */
data class Notice(val id: Long, val problem: Problem)

/** Rex took the request, or the connection, and did not answer in time. Not a cancellation. */
class RexTimeoutException(message: String) : Exception(message)

/** The control connection closed after it was up. */
class ConnectionDroppedException(message: String, cause: Throwable?) : Exception(message, cause)

/** An action that needs a connection, a session or a pane before there is one. */
class NotReadyException(message: String) : IllegalStateException(message)

/** Classifies by exception type along the cause chain; the message is kept as detail. */
fun Throwable.toProblem(): Problem {
    val chain = generateSequence(this) { it.cause }.toList()
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

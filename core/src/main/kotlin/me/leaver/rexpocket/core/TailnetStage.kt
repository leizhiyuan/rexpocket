package me.leaver.rexpocket.core

/**
 * Where the phone's tailnet node is on its way from "joined" to "connected", in the steps a person
 * sees: it starts, it may need a sign-in or an approval, it connects to the network, and then it is
 * on it as a named device. The node's own states (Tailscale's ipn.State, the Netbird node's two)
 * map onto these in [tailnetStage].
 */
sealed interface TailnetStage {
    /** The phone has not joined a tailnet. */
    data object Off : TailnetStage

    /** The node is coming up: nothing from the network yet. */
    data object Starting : TailnetStage

    /** The node needs a sign-in in the browser; [authUrl] is the page, once the node has it. */
    data class SigningIn(val authUrl: String) : TailnetStage

    /** Signed in, and waiting for the tailnet's admin to approve the device. */
    data object NeedsApproval : TailnetStage

    /** Signed in, and joining the network: the control plane, the device list. */
    data object Connecting : TailnetStage

    /** On the network as [device], on [network] (blank when the node does not say). */
    data class Connected(val device: String, val network: String) : TailnetStage

    /** The node is up but switched off. */
    data object Stopped : TailnetStage

    /** The node could not start; [message] is why. */
    data class Failed(val message: String) : TailnetStage
}

/**
 * The stage of a node from what it reports. [joined] is the setting; [error] is the start's
 * failure, which wins over anything the node said before it. A node that says it is running but has
 * not been told its own name yet is still connecting: its device list is not there either.
 */
fun tailnetStage(
    joined: Boolean,
    backendState: String,
    authUrl: String = "",
    device: String = "",
    network: String = "",
    error: String? = null,
): TailnetStage = when {
    !joined -> TailnetStage.Off
    error != null -> TailnetStage.Failed(error)
    else -> when (backendState) {
        "", "NoState" -> if (authUrl.isNotBlank()) TailnetStage.SigningIn(authUrl) else TailnetStage.Starting
        "NeedsLogin" -> TailnetStage.SigningIn(authUrl)
        "NeedsMachineAuth" -> TailnetStage.NeedsApproval
        "Starting" -> TailnetStage.Connecting
        "Running" -> if (device.isBlank()) TailnetStage.Connecting else TailnetStage.Connected(device, network)
        "Stopped" -> TailnetStage.Stopped
        else -> TailnetStage.Connecting
    }
}

/** The steps a backend goes through to connect, for a "step 2 of 3" line. */
enum class TailnetStep { Start, SignIn, Connect }

/** The steps [backend] shows: a Netbird node joins with a setup key, so it has no sign-in step. */
fun tailnetSteps(backend: TailnetBackend): List<TailnetStep> =
    if (backend == TailnetBackend.Netbird) listOf(TailnetStep.Start, TailnetStep.Connect)
    else listOf(TailnetStep.Start, TailnetStep.SignIn, TailnetStep.Connect)

/** The step [this] stage is on, or null when it is not on its way: off, connected, stopped, failed. */
fun TailnetStage.step(): TailnetStep? = when (this) {
    TailnetStage.Starting -> TailnetStep.Start
    is TailnetStage.SigningIn, TailnetStage.NeedsApproval -> TailnetStep.SignIn
    TailnetStage.Connecting -> TailnetStep.Connect
    else -> null
}

/** True for a stage that needs the person to do something: sign in, get approval, fix a failure. */
val TailnetStage.needsAttention: Boolean
    get() = this is TailnetStage.SigningIn || this == TailnetStage.NeedsApproval ||
        this is TailnetStage.Failed || this == TailnetStage.Stopped

/** The steps between tapping a host and its sessions on screen. */
enum class ConnectStep {
    /** The phone's tailnet node is starting, or signing in. */
    StartTailnet,

    /** The node is up, and waiting for its device list to name the host. */
    FindPeer,

    /** Opening a connection to the host - over the tailnet, over SSH, or straight - until Rex answers. */
    Reach,

    /** Rex answered the connection; its sessions are being read. */
    OpenRex,
}

/**
 * How far a connection to a host has come: the step it is on and since when (in
 * [System.currentTimeMillis] time), and how long each step before it took.
 */
data class ConnectProgress(
    val host: String,
    val viaTailnet: Boolean,
    val step: ConnectStep,
    val startedAt: Long,
    val stepStartedAt: Long = startedAt,
    val took: List<Pair<ConnectStep, Long>> = emptyList(),
) {
    /** On to [next] at [now]; the step it leaves is kept with its time. Moving to the same step does nothing. */
    fun next(next: ConnectStep, now: Long): ConnectProgress =
        if (next == step) this else copy(step = next, stepStartedAt = now, took = took + (step to (now - stepStartedAt)))

    /** The steps the connection goes through, in order. */
    val steps: List<ConnectStep>
        get() = if (viaTailnet) ConnectStep.entries else listOf(ConnectStep.Reach, ConnectStep.OpenRex)
}

/** What a node says about a failed dial, read from its own error text. */
enum class DialFailure { Refused, NoSuchHost, TimedOut, Unreachable, Other }

/**
 * Reads a dial error from a node's netstack: gVisor's "connection was refused", Go's "no such
 * host", a deadline, a missing route. Anything else is [DialFailure.Other].
 */
fun dialFailure(text: String): DialFailure {
    val t = text.lowercase()
    return when {
        "refused" in t || "reset by peer" in t -> DialFailure.Refused
        "no such host" in t || "unknown host" in t || "not found" in t || "no address" in t ||
            "not a peer" in t || "server misbehaving" in t -> DialFailure.NoSuchHost
        "timeout" in t || "timed out" in t || "deadline" in t -> DialFailure.TimedOut
        "unreachable" in t || "no route" in t -> DialFailure.Unreachable
        else -> DialFailure.Other
    }
}

/**
 * Says why a host on the tailnet could not be reached, from what the node knows, instead of the
 * SOCKS error the connection saw: the node needs a sign-in, the port refused (Rex is not
 * listening), the device is offline, or the tailnet has no device by that name. [problem] comes
 * back as it is when it is about something else (Rex refused, SSH, a dropped connection), or when
 * the node knows nothing more.
 *
 * [peers] is the node's device list, [peersKnown] whether it has one yet (a node that is still
 * connecting knows of no device, and that does not mean the name is wrong), [dialError] the node's
 * reason for its last failed dial to the host.
 */
fun explainTailnetProblem(
    problem: Problem,
    host: String,
    port: Int,
    backendState: String,
    peers: List<TailnetPeer>,
    peersKnown: Boolean,
    dialError: String?,
): Problem {
    val reachProblem = problem.trouble == Trouble.Unreachable || problem.trouble == Trouble.TimedOut ||
        (problem.trouble == Trouble.Other && (!dialError.isNullOrBlank() || problem.detail?.contains("SOCKS", ignoreCase = true) == true))
    if (!reachProblem) return problem
    when (backendState) {
        "NeedsLogin" -> return Problem(Trouble.TailnetNeedsLogin, null)
        "NeedsMachineAuth" -> return Problem(Trouble.TailnetNeedsLogin, APPROVAL)
    }
    val failure = dialError?.takeIf { it.isNotBlank() }?.let(::dialFailure)
    if (failure == DialFailure.Refused) return Problem(Trouble.RexNotListening, port.toString())
    val peer = peers.named(host)
    if (peer?.online == false) return Problem(Trouble.TailnetPeerOffline, peer.lastSeen?.toString())
    val literal = host.trim('[', ']').let { it.firstOrNull()?.isDigit() == true || ':' in it }
    if (peer == null && !literal && (peersKnown || failure == DialFailure.NoSuchHost)) {
        return Problem(Trouble.TailnetPeerUnknown, null)
    }
    return dialError?.takeIf { it.isNotBlank() }?.let { problem.copy(detail = it) } ?: problem
}

/** [Problem.detail] of a [Trouble.TailnetNeedsLogin] that waits for an admin's approval, not a sign-in. */
const val APPROVAL = "approval"

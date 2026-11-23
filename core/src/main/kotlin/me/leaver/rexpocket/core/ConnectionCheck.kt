package me.leaver.rexpocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.EOFException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory
import javax.net.ssl.SSLException

/**
 * The host dialog's Test connection: one host as it is typed, tried step by step - the tailnet,
 * the name, the port, the host key, the sign-in, Rex and its API - with each step's answer in
 * words the dialog can show. It saves nothing and changes nothing: the host key it sees is only
 * reported ([CheckReport.seenHostKey]), and a key the person trusts for the test is passed back in
 * as [CheckTarget.hostKey] for the next run. See docs/design/connection-test.md.
 */
data class CheckTarget(
    val host: String,
    val port: Int,
    val plaintext: Boolean,
    /** The SSH account; null when Rex listens on a port. */
    val sshUser: String? = null,
    val credentials: SshCredentials = SshCredentials(),
    /** The pinned key, or one the person trusted during the test; null asks. */
    val hostKey: String? = null,
    val viaTailnet: Boolean = false,
) {
    val viaSsh: Boolean get() = !sshUser.isNullOrBlank()

    override fun toString(): String =
        "CheckTarget(${if (viaSsh) "$sshUser@" else ""}$host:$port tailnet=$viaTailnet ssh=$viaSsh pinned=${hostKey != null})"
}

/**
 * How the check reaches the host: the phone's own network, or the tailnet node's, as the node says
 * a connection goes through it - its SOCKS port, or its own sockets for a node with no proxy. The
 * same route a real connection to the host takes.
 */
class CheckRoute(
    val socksPort: Int = 0,
    /** The node's own sockets, when it has no SOCKS port: each is connected to an unresolved address. */
    val sockets: SocketFactory? = null,
    /** The SSH settings of a connection on this route. */
    val ssh: (SshSettings) -> SshSettings = { it },
    /** The HTTP client of a Rex-port connection on this route. */
    val http: () -> OkHttpClient = { RexClient.defaultHttp(socksPort) },
)

/** A route that could not be made: the tailnet is not joined, or its node would not start. */
class CheckRouteException(val failure: CheckFailure, message: String?) : Exception(message)

enum class CheckStep { Tailnet, Resolve, Connect, HostKey, SignIn, Rex, Api }

enum class CheckFailure {
    TailnetNotJoined, TailnetDown,
    UnknownHost, Refused, Unreachable, TimedOut,
    HostKeyUnknown, HostKeyChanged, Handshake,
    NoCredentials, KeyUnreadable, SignInRefused,
    RexMissing, NotRex, Tls, RexRefused, RexNoAnswer,
    ApiTooOld,
    Canceled,
    Other,
}

sealed interface StepResult {
    data object Waiting : StepResult
    data object Running : StepResult

    /** [detail] is what the step found: the addresses, the fingerprint, the API revision. */
    data class Passed(val detail: String? = null) : StepResult
    data class Failed(val failure: CheckFailure, val detail: String? = null) : StepResult

    /** Not tried, because an earlier step failed. */
    data object NotReached : StepResult
}

data class CheckReport(
    val steps: List<Pair<CheckStep, StepResult>>,
    /** The host key the SSH server showed, for a first-use trust or a mismatch. */
    val seenHostKey: String? = null,
    /** The API revision Rex answered the hello with. */
    val apiRevision: Int? = null,
    val finished: Boolean = false,
) {
    val passed: Boolean get() = finished && steps.all { it.second is StepResult.Passed }
    val failure: StepResult.Failed? get() = steps.firstNotNullOfOrNull { it.second as? StepResult.Failed }
    fun of(step: CheckStep): StepResult? = steps.firstOrNull { it.first == step }?.second
}

/** How long each step may take before it fails as timed out. */
data class CheckTimeouts(
    val tailnetMs: Long = 45_000,
    val resolveMs: Long = 10_000,
    val connectMs: Long = 10_000,
    val sshMs: Long = SshTunnel.CONNECT_TIMEOUT_MS + 5_000L,
    val rexMs: Long = 15_000,
)

class ConnectionCheck(
    private val target: CheckTarget,
    /** Brings the tailnet route up through the app's dial seam; called only for a tailnet host. */
    private val route: suspend () -> CheckRoute = { CheckRoute() },
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val timeouts: CheckTimeouts = CheckTimeouts(),
    /** Asked for in the hello: Rex answers the highest it has up to this one. */
    private val apiRevision: Int = PROBE_API_REVISION,
    private val trace: (String) -> Unit = {},
) {
    private val steps = buildList {
        if (target.viaTailnet) add(CheckStep.Tailnet) else add(CheckStep.Resolve)
        add(CheckStep.Connect)
        if (target.viaSsh) {
            add(CheckStep.HostKey)
            add(CheckStep.SignIn)
        }
        add(CheckStep.Rex)
        add(CheckStep.Api)
    }

    @Volatile private var report = CheckReport(steps.map { it to StepResult.Waiting })

    /**
     * Runs every step in turn and returns the last report; [onReport] hears each change. A step that
     * fails stops the check, and the ones after it are [StepResult.NotReached]. Cancelling the
     * coroutine ends the check at once, with the running step [CheckFailure.Canceled].
     */
    suspend fun run(onReport: (CheckReport) -> Unit = {}): CheckReport {
        fun set(step: CheckStep, result: StepResult, seen: String? = report.seenHostKey, api: Int? = report.apiRevision) {
            report = report.copy(steps = report.steps.map { if (it.first == step) step to result else it }, seenHostKey = seen, apiRevision = api)
            onReport(report)
        }
        fun stop(): CheckReport {
            report = report.copy(
                steps = report.steps.map { (s, r) -> s to if (r == StepResult.Waiting || r == StepResult.Running) StepResult.NotReached else r },
                finished = true,
            )
            onReport(report)
            return report
        }
        var current: CheckStep? = null
        var tunnel: SshTunnel? = null
        var client: RexClient? = null
        try {
            var via = CheckRoute()
            var addresses: List<InetAddress> = emptyList()
            for (step in steps) {
                current = step
                set(step, StepResult.Running)
                val result: StepResult = when (step) {
                    CheckStep.Tailnet -> try {
                        via = withTimeout(timeouts.tailnetMs) { route() }
                        StepResult.Passed()
                    } catch (e: TimeoutCancellationException) {
                        StepResult.Failed(CheckFailure.TailnetDown, "timeout")
                    } catch (e: CheckRouteException) {
                        StepResult.Failed(e.failure, e.message)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        StepResult.Failed(CheckFailure.TailnetDown, e.message)
                    }
                    CheckStep.Resolve -> when (val r = blocking(timeouts.resolveMs, abort = {}) { resolve(target.host) }) {
                        is Outcome.Done -> {
                            addresses = r.value
                            if (addresses.isEmpty()) StepResult.Failed(CheckFailure.UnknownHost, target.host)
                            else StepResult.Passed(addresses.take(3).joinToString(", ") { it.hostAddress ?: it.toString() })
                        }
                        is Outcome.Error -> StepResult.Failed(
                            if (r.error is UnknownHostException) CheckFailure.UnknownHost else CheckFailure.Other,
                            r.error.message ?: target.host,
                        )
                        Outcome.Timeout -> StepResult.Failed(CheckFailure.TimedOut)
                    }
                    CheckStep.Connect -> connect(via, addresses)
                    CheckStep.HostKey -> {
                        val t = SshTunnel(sshSettings(via), trace)
                        tunnel = t
                        sshStep(t, set = { seen -> set(CheckStep.HostKey, StepResult.Passed(seen), seen = seen) })
                    }
                    // The sign-in was done with the host key, in one SSH connect: its answer is already in.
                    CheckStep.SignIn -> StepResult.Passed()
                    CheckStep.Rex -> {
                        val c = if (target.viaSsh) {
                            RexClient.forSsh(tunnel!!, apiRevision)
                        } else {
                            RexClient(via.http(), connectTimeoutMs = timeouts.rexMs, apiRevision = apiRevision)
                        }
                        client = c
                        rexStep(c, tunnel)
                    }
                    CheckStep.Api -> {
                        val revision = report.apiRevision ?: 0
                        if (revision < MIN_API_REVISION) StepResult.Failed(CheckFailure.ApiTooOld, revision.toString())
                        else StepResult.Passed(revision.toString())
                    }
                }
                if (step == CheckStep.HostKey && result is StepResult.Failed && result.failure in SIGN_IN_FAILURES) {
                    // The key was fine; the sign-in after it was not. With nothing to sign in with,
                    // nothing was dialled and the key was not seen.
                    report.seenHostKey?.let { set(CheckStep.HostKey, StepResult.Passed(it)) }
                    set(CheckStep.SignIn, result)
                    return stop()
                }
                set(step, result)
                if (result is StepResult.Failed) return stop()
            }
            return stop()
        } catch (e: CancellationException) {
            current?.let { report = report.copy(steps = report.steps.map { (s, r) -> s to if (s == it) StepResult.Failed(CheckFailure.Canceled) else r }) }
            stop()
            throw e
        } finally {
            client?.close()
            // Closing waits for an SSH connect still under way; the check does not.
            tunnel?.let { t -> Dispatchers.IO.asExecutor().execute { t.close() } }
        }
    }

    private fun sshSettings(via: CheckRoute) = via.ssh(
        SshSettings(
            host = target.host,
            port = target.port,
            user = target.sshUser.orEmpty(),
            credentials = target.credentials,
            hostKey = target.hostKey?.takeIf { it.isNotBlank() },
            socksPort = via.socksPort,
        ),
    )

    /** A TCP connection to the port, then closed: the machine is there and something listens. */
    private suspend fun connect(via: CheckRoute, addresses: List<InetAddress>): StepResult {
        var socket: Socket? = null
        val r = blocking(timeouts.connectMs, abort = { runCatching { socket?.close() } }) {
            val timeout = timeouts.connectMs.toInt()
            when {
                via.sockets != null -> via.sockets.createSocket().also { socket = it }.connect(InetSocketAddress.createUnresolved(target.host, target.port), timeout)
                via.socksPort > 0 -> Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", via.socksPort))).also { socket = it }
                    .connect(InetSocketAddress.createUnresolved(target.host, target.port), timeout)
                else -> {
                    var last: Exception? = null
                    var done = false
                    for (address in addresses.ifEmpty { listOf(InetAddress.getByName(target.host)) }) {
                        try {
                            Socket().also { socket = it }.connect(InetSocketAddress(address, target.port), timeout)
                            done = true
                            break
                        } catch (e: Exception) {
                            runCatching { socket?.close() }
                            last = e
                        }
                    }
                    if (!done) throw last ?: ConnectException("no address")
                }
            }
            runCatching { socket?.close() }
        }
        return when (r) {
            is Outcome.Done -> StepResult.Passed("${target.host}:${target.port}")
            Outcome.Timeout -> StepResult.Failed(CheckFailure.TimedOut)
            is Outcome.Error -> StepResult.Failed(
                when (val e = r.error) {
                    is SocketTimeoutException -> CheckFailure.TimedOut
                    is ConnectException -> CheckFailure.Refused
                    is NoRouteToHostException -> CheckFailure.Unreachable
                    is UnknownHostException -> CheckFailure.UnknownHost
                    else -> if (e.message?.contains("refused", ignoreCase = true) == true) CheckFailure.Refused else CheckFailure.Unreachable
                },
                r.error.message,
            )
        }
    }

    /** The SSH connect: key exchange, host key, sign-in. [set] hears the key as soon as it is known good. */
    private suspend fun sshStep(tunnel: SshTunnel, set: (String?) -> Unit): StepResult {
        if (!target.credentials.usable) return StepResult.Failed(CheckFailure.NoCredentials)
        val r = blocking(timeouts.sshMs, abort = { Dispatchers.IO.asExecutor().execute { tunnel.close() } }) { tunnel.open() }
        val seen = tunnel.seenHostKey()
        report = report.copy(seenHostKey = seen)
        return when (r) {
            is Outcome.Done -> {
                set(seen)
                StepResult.Passed(seen)
            }
            Outcome.Timeout -> StepResult.Failed(CheckFailure.TimedOut)
            is Outcome.Error -> when (val e = r.error) {
                is SshHostKeyException -> StepResult.Failed(if (e.changed) CheckFailure.HostKeyChanged else CheckFailure.HostKeyUnknown, e.fingerprint)
                is SshAuthException -> StepResult.Failed(
                    when {
                        e.message?.contains("No SSH password or key") == true -> CheckFailure.NoCredentials
                        e.message?.contains("could not be") == true -> CheckFailure.KeyUnreadable
                        else -> CheckFailure.SignInRefused
                    },
                    e.message,
                )
                else -> StepResult.Failed(
                    if (e.message?.contains("timeout", ignoreCase = true) == true) CheckFailure.TimedOut else CheckFailure.Handshake,
                    e.message,
                )
            }
        }
    }

    /** Rex's hello and welcome, on the route the app would use. */
    private suspend fun rexStep(client: RexClient, tunnel: SshTunnel?): StepResult {
        val welcome = try {
            withTimeout(timeouts.rexMs) { client.connect(Endpoint(target.host, target.port, target.plaintext, target.sshUser?.takeIf { it.isNotBlank() })) }
        } catch (e: TimeoutCancellationException) {
            return StepResult.Failed(CheckFailure.RexNoAnswer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return rexFailure(e, tunnel?.lastFailure())
        }
        report = report.copy(apiRevision = welcome.apiRevision)
        return StepResult.Passed(welcome.apiRevision.toString())
    }

    private fun rexFailure(e: Throwable, transport: Throwable?): StepResult.Failed {
        if (transport is SshCommandException) return StepResult.Failed(CheckFailure.RexMissing, transport.message)
        val chain = generateSequence(e) { it.cause }.toList()
        val upgrade = chain.firstNotNullOfOrNull { c -> (c as? ProtocolException)?.message?.takeIf { it.contains("Expected HTTP 101") } }
        return when {
            upgrade != null -> {
                val code = Regex("'(\\d{3})").find(upgrade)?.groupValues?.get(1)?.toIntOrNull()
                StepResult.Failed(if (code == 401 || code == 403) CheckFailure.RexRefused else CheckFailure.NotRex, upgrade)
            }
            chain.any { it is SSLException } -> StepResult.Failed(CheckFailure.Tls, chain.first { it is SSLException }.message)
            chain.any { it is RexTimeoutException || it is SocketTimeoutException } -> StepResult.Failed(CheckFailure.RexNoAnswer, e.message)
            chain.any { it is ConnectException } -> StepResult.Failed(CheckFailure.Refused, e.message)
            // The port took the connection and closed it without a word: not Rex, or not over this scheme.
            chain.any { it is EOFException } -> StepResult.Failed(CheckFailure.NotRex, e.message)
            transport != null -> StepResult.Failed(CheckFailure.Other, transport.message)
            else -> StepResult.Failed(CheckFailure.Other, e.message)
        }
    }

    private sealed interface Outcome<out T> {
        data class Done<T>(val value: T) : Outcome<T>
        data class Error(val error: Exception) : Outcome<Nothing>
        data object Timeout : Outcome<Nothing>
    }

    /**
     * Runs blocking [block] off the caller, for at most [timeoutMs]. A cancellation or a timeout
     * returns at once, calling [abort] to unstick the call (a socket closed under it); the thread
     * finishes on its own.
     */
    private suspend fun <T> blocking(timeoutMs: Long, abort: () -> Unit, block: () -> T): Outcome<T> = try {
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { runCatching(abort) }
                Dispatchers.IO.asExecutor().execute {
                    val r: Outcome<T> = try {
                        Outcome.Done(block())
                    } catch (e: Exception) {
                        Outcome.Error(e)
                    }
                    if (cont.isActive) cont.resumeWith(Result.success(r))
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        Outcome.Timeout
    }

    companion object {
        /** What the app asks for on every connection: a Rex below it cannot serve the app. */
        const val MIN_API_REVISION = 7

        /** What the check asks for, to learn the newest revision Rex has (preview.22 has 19). */
        const val PROBE_API_REVISION = 19

        private val SIGN_IN_FAILURES = setOf(CheckFailure.NoCredentials, CheckFailure.KeyUnreadable, CheckFailure.SignInRefused)
    }
}

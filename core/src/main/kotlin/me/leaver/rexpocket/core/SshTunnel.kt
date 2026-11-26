package me.leaver.rexpocket.core

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** How to sign in to the machine that runs Rex. One of the two must be set. */
data class SshCredentials(
    val password: String? = null,
    val privateKey: String? = null,
    /** The passphrase of [privateKey], when it has one. */
    val passphrase: String? = null,
) {
    val usable: Boolean get() = !password.isNullOrEmpty() || !privateKey.isNullOrBlank()
}

/**
 * A host reached by SSH instead of a Rex port. [hostKey] is the `SHA256:...` fingerprint the person
 * confirmed; null means the next connection must ask before it trusts the machine.
 */
data class SshSettings(
    val host: String,
    val port: Int = 22,
    val user: String,
    val credentials: SshCredentials = SshCredentials(),
    val hostKey: String? = null,
    /** The app's own Tailscale SOCKS port, or 0 when the phone's own network reaches the host. */
    val socksPort: Int = 0,
    /** Dials for the tunnel instead, when the tailnet node is in another app (the Netbird add-on). */
    val dialer: StreamDialer? = null,
)

/** Something SSH could not do. The UI turns these into its own words. */
open class SshException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The machine is not the one that was trusted before, or it has not been trusted yet. */
class SshHostKeyException(val fingerprint: String, val changed: Boolean) :
    SshException(
        if (changed) "The host key changed to $fingerprint" else "The host key $fingerprint has not been confirmed",
    ),
    FatalConnectionProblem

/** The password or key was refused, or the key could not be read. Retrying cannot help. */
class SshAuthException(message: String, cause: Throwable? = null) :
    SshException(message, cause),
    FatalConnectionProblem

/** The machine is reachable but cannot run Rex, so there is nothing to talk to. */
class SshCommandException(message: String, cause: Throwable? = null) :
    SshException(message, cause),
    FatalConnectionProblem

/** A failure that a retry cannot fix: the person has to change the host or trust a key. */
interface FatalConnectionProblem

fun Throwable.isFatalConnectionProblem(): Boolean =
    generateSequence(this) { it.cause }.any { it is FatalConnectionProblem }

/** `SHA256:...`, the fingerprint OpenSSH prints, over a host's public key blob. */
object SshFingerprint {
    fun of(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
}

/**
 * One host's SSH connection, and the HTTP connections that ride it.
 *
 * Rex serves its API on a Unix socket on the machine, so there is no port to open: each HTTP
 * connection here is an SSH exec channel running `rex __internal dial-stdio`, which joins its
 * standard input and output to that socket. OkHttp speaks its usual HTTP and WebSocket over the
 * channel as if it were a socket, so the rest of the app does not know the transport changed.
 */
class SshTunnel(
    private val settings: SshSettings,
    private val trace: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private var session: Session? = null
    @Volatile private var hostKey: String? = null
    @Volatile private var lastFailure: SshException? = null
    @Volatile private var client: OkHttpClient? = null

    private fun httpClient(): OkHttpClient = client ?: synchronized(this) {
        client ?: OkHttpClient.Builder()
            .socketFactory(ChannelSocketFactory())
            // Each Rex connection is its own exec channel; a pooled one is a channel whose remote
            // command may already have ended, so there is nothing worth keeping between requests.
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(RexClient.PING_INTERVAL_S, TimeUnit.SECONDS)
            // A failure here is the host's answer, not a flaky route: the app retries on its own.
            .retryOnConnectionFailure(false)
            .build()
            .also { client = it }
    }

    /** The client every Rex connection over this SSH host uses. */
    fun http(): OkHttpClient = httpClient()

    /** The fingerprint this tunnel last saw, for a report or a trust prompt. */
    fun seenHostKey(): String? = hostKey

    /**
     * The last transport failure, when OkHttp reduced it to "exhausted all routes" and lost the
     * reason. A missing `rex`, a refused key or a changed host key still reaches the screen.
     */
    fun lastFailure(): SshException? = lastFailure

    /**
     * Opens one exec channel to `rex __internal dial-stdio`. The session is shared by every channel;
     * a session that died under the network is replaced once before the failure is reported.
     */
    private fun openChannel(timeoutMs: Int, stderr: OutputStream): ChannelExec {
        var current = synchronized(lock) { connectLocked() }
        try {
            return channel(current, timeoutMs, stderr)
        } catch (e: JSchException) {
            // A session can still say it is connected after the network under it went away; replacing
            // it once is cheap, and the next attempt is what tells a dead session from a refusal.
            trace("ssh: opening a channel on the session to ${settings.user}@${settings.host} failed: ${e.message}")
            synchronized(lock) {
                session?.disconnect()
                session = null
                current = connectLocked()
            }
            try {
                return channel(current, timeoutMs, stderr)
            } catch (next: JSchException) {
                throw classify(next).also { lastFailure = it }
            }
        }
    }

    /**
     * Connects the SSH session now, with no channel: the key exchange, the host key and the sign-in,
     * which the host dialog's Test connection tells apart. Throws [SshHostKeyException],
     * [SshAuthException] or [SshException]; blocking, at most [CONNECT_TIMEOUT_MS].
     */
    fun open() {
        synchronized(lock) { connectLocked() }
    }

    /** Connects when there is no live session. Callers hold [lock]. */
    private fun connectLocked(): Session {
        session?.takeIf { it.isConnected }?.let { return it }
        session?.disconnect()
        session = null
        if (closed.get()) throw SshException("The SSH connection is closed")
        if (!settings.credentials.usable) throw SshAuthException("No SSH password or key was given")

        hostKey = null
        lastFailure = null
        val jsch = JSch()
        val credentials = settings.credentials
        if (!credentials.privateKey.isNullOrBlank()) {
            val passphrase = credentials.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
            try {
                jsch.addIdentity("rexpocket", credentials.privateKey.toByteArray(Charsets.UTF_8), null, passphrase)
            } catch (e: JSchException) {
                throw SshAuthException("The private key could not be read: ${e.message}", e).also { lastFailure = it }
            }
        }
        val next = jsch.getSession(settings.user, settings.host, settings.port)
        next.setConfig("StrictHostKeyChecking", "yes")
        // Only the methods this host has a credential for. A password-only host would otherwise
        // spend its retry budget on public keys it does not have and disconnect the connection.
        next.setConfig(
            "PreferredAuthentications",
            when {
                !credentials.privateKey.isNullOrBlank() && !credentials.password.isNullOrEmpty() ->
                    "publickey,password,keyboard-interactive"
                !credentials.privateKey.isNullOrBlank() -> "publickey"
                else -> "password,keyboard-interactive"
            },
        )
        next.setHostKeyRepository(HostKeys())
        next.setSocketFactory(TunnelSocketFactory())
        if (!credentials.password.isNullOrEmpty()) {
            next.setPassword(credentials.password.toByteArray(Charsets.UTF_8))
            next.setUserInfo(Passwords(credentials.password, credentials.passphrase))
        }
        val startedAt = System.nanoTime()
        try {
            next.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            val seen = hostKey
            next.disconnect()
            if (seen != null && seen != settings.hostKey) {
                trace("ssh: ${settings.host}: host key ${if (settings.hostKey == null) "not confirmed" else "changed"}")
                throw SshHostKeyException(seen, changed = !settings.hostKey.isNullOrBlank()).also { lastFailure = it }
            }
            val failure = classify(e)
            trace("ssh: ${settings.user}@${settings.host}:${settings.port} failed after ${elapsed(startedAt)}ms: ${failure.message}")
            lastFailure = failure
            throw failure
        }
        trace("ssh: ${settings.user}@${settings.host}:${settings.port} connected after ${elapsed(startedAt)}ms")
        session = next
        return next
    }

    private fun channel(session: Session, timeoutMs: Int, stderr: OutputStream): ChannelExec {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(REMOTE_COMMAND)
        channel.setErrStream(stderr)
        channel.connect(timeoutMs)
        return channel
    }

    private fun classify(e: JSchException): SshException {
        val message = e.message.orEmpty()
        val lower = message.lowercase()
        val auth = lower.contains("auth fail") || lower.contains("auth cancel") ||
            lower.contains("userauth") || lower.contains("privatekey") ||
            lower.contains("passphrase") || lower.contains("invalid key")
        return if (auth) SshAuthException(humanAuth(message), e) else SshException(message, e)
    }

    private fun humanAuth(message: String): String = when {
        message.contains("Passphrase", ignoreCase = true) -> "The key's passphrase was refused"
        message.contains("privatekey", ignoreCase = true) || message.contains("invalid key", ignoreCase = true) ->
            "The private key could not be used"
        else -> "The password or key was refused"
    }

    private fun elapsed(since: Long) = (System.nanoTime() - since) / 1_000_000

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            session?.disconnect()
            session = null
        }
        client?.connectionPool?.evictAll()
    }

    private inner class ChannelSocketFactory : javax.net.SocketFactory() {
        override fun createSocket(): Socket = ChannelSocket(this@SshTunnel)

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(address: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = createSocket().apply { connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS) }
    }

    /** The machine as seen by JSch: the phone's network, or the app's Tailscale node when asked. */
    private inner class TunnelSocketFactory : com.jcraft.jsch.SocketFactory {
        override fun createSocket(host: String, port: Int): Socket {
            settings.dialer?.let { dialer ->
                return DialedSocket(dialer).apply { connect(InetSocketAddress.createUnresolved(host, port), CONNECT_TIMEOUT_MS) }
            }
            val socket = if (settings.socksPort > 0) {
                Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", settings.socksPort)))
            } else {
                Socket()
            }
            // Through the tailnet the name is MagicDNS's to resolve, not the phone's.
            val address = if (settings.socksPort > 0) InetSocketAddress.createUnresolved(host, port)
            else InetSocketAddress(host, port)
            socket.connect(address, CONNECT_TIMEOUT_MS)
            return socket
        }

        override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

        override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
    }

    /** Trust on first use, then pin: the tunnel only accepts the key this host was saved with. */
    private inner class HostKeys : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fingerprint = SshFingerprint.of(key)
            this@SshTunnel.hostKey = fingerprint
            val pinned = settings.hostKey
            return when {
                pinned.isNullOrBlank() -> HostKeyRepository.NOT_INCLUDED
                pinned == fingerprint -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }

        override fun add(hostkey: HostKey?, userinfo: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID(): String = "RexPocket"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private class Passwords(private val password: String?, private val passphrase: String?) : UserInfo {
        override fun getPassphrase(): String? = passphrase
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?): Boolean = !password.isNullOrEmpty()
        override fun promptPassphrase(message: String?): Boolean = !passphrase.isNullOrEmpty()
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit
    }

    /** The command a phone runs over SSH to reach the server on that machine. */
    companion object {
        const val CONNECT_TIMEOUT_MS = 20_000

        /**
         * `rex server install` puts the binary in `~/.local/bin`, which a non-interactive shell does
         * not always have on its PATH, so the command looks there itself before giving up.
         */
        internal const val REMOTE_COMMAND =
            "exec /bin/sh -c 'command -v rex >/dev/null 2>&1 && exec rex __internal dial-stdio; " +
                "for p in \"\$HOME/.local/bin/rex\" /usr/local/bin/rex /usr/bin/rex; do " +
                "if [ -x \"\$p\" ]; then exec \"\$p\" __internal dial-stdio; fi; done; " +
                "echo \"rex was not found on this machine\" >&2; exit 127'"
    }

    init {
        configureJsch()
    }

    /** A socket whose bytes are one exec channel, enough of [Socket] for OkHttp. */
    private inner class ChannelSocket(private val tunnel: SshTunnel) : Socket() {
        private var channel: ChannelExec? = null
        private var input: InputStream? = null
        private var output: OutputStream? = null
        private val stderr = Stderr { text ->
            tunnel.lastFailure = SshCommandException(text)
        }
        @Volatile private var closed = false

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            if (closed) throw SocketException("The socket is closed")
            val ch = tunnel.openChannel(if (timeout > 0) timeout else CONNECT_TIMEOUT_MS, stderr)
            channel = ch
            input = Ending(ch.getInputStream())
            val raw = ch.getOutputStream()
            // JSch's channel stream buffers until it is flushed; a socket's does not. OkHttp writes
            // one HTTP request or one WebSocket frame at a time, so flushing every write sends each
            // as soon as it is complete.
            output = object : OutputStream() {
                override fun write(b: Int) {
                    raw.write(b)
                    raw.flush()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    raw.write(b, off, len)
                    raw.flush()
                }

                override fun flush() = raw.flush()
                override fun close() = raw.close()
            }
            trace("ssh: channel open")
        }

        override fun getInputStream(): InputStream =
            input ?: throw SocketException("The SSH channel is not open")

        override fun getOutputStream(): OutputStream =
            output ?: throw SocketException("The SSH channel is not open")

        override fun setSoTimeout(timeout: Int) {
            // Rex's connections are idle for minutes at a time and its WebSockets run their own
            // ping; a read timeout here would cut a healthy pane. OkHttp asks for 0 too.
        }

        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setKeepAlive(on: Boolean) = Unit
        override fun setSoLinger(on: Boolean, linger: Int) = Unit

        override fun close() {
            closed = true
            channel?.disconnect()
            channel = null
            input = null
            output = null
        }

        override fun isConnected(): Boolean = channel != null && !closed
        override fun isClosed(): Boolean = closed || channel?.isClosed == true
        override fun isInputShutdown(): Boolean = closed
        override fun isOutputShutdown(): Boolean = closed
        override fun shutdownInput() = Unit
        override fun shutdownOutput() = Unit

        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getLocalAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun getLocalPort(): Int = 0
        override fun getRemoteSocketAddress(): SocketAddress =
            InetSocketAddress(tunnel.settings.host, tunnel.settings.port)
        override fun getLocalSocketAddress(): SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
        override fun toString(): String = "ssh:${tunnel.settings.user}@${tunnel.settings.host}:${tunnel.settings.port}"

        /**
         * Rex's own words when the remote command stopped. A channel that ends with nothing to say
         * is a normal end of the HTTP connection.
         */
        private fun ended(): IOException {
            val text = stderr.text()
            if (text.isBlank()) return IOException("The SSH channel closed before the answer")
            trace("ssh: remote command said: $text")
            val lower = text.lowercase()
            if (looksLikeMissingRex(lower)) {
                return SshCommandException(text).also { tunnel.lastFailure = it }
            }
            return IOException(text)
        }

        private inner class Ending(private val raw: InputStream) : InputStream() {
            override fun read(): Int {
                val byte = raw.read()
                if (byte < 0 && !closed) throw ended()
                return byte
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = raw.read(bytes, offset, length)
                if (count < 0 && !closed) throw ended()
                return count
            }

            override fun available(): Int = runCatching { raw.available() }.getOrDefault(0)
            override fun close() {
                runCatching { raw.close() }
                this@ChannelSocket.close()
            }
        }
    }

    /** The last few kilobytes the remote command wrote to its error stream, for a useful failure. */
    internal class Stderr(private val limit: Int = 4096, private val onText: (String) -> Unit = {}) : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        private var reported = false

        @Synchronized
        override fun write(b: Int) {
            if (bytes.size() < limit) bytes.write(b)
            report()
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            val room = limit - bytes.size()
            if (room > 0) bytes.write(b, off, minOf(len, room))
            report()
        }

        @Synchronized
        fun text(): String = bytes.toString(Charsets.UTF_8.name()).trim()

        /** The first moment the message says Rex is missing, before OkHttp may swallow the channel. */
        private fun report() {
            if (reported) return
            val text = bytes.toString(Charsets.UTF_8.name())
            if (looksLikeMissingRex(text.lowercase())) {
                reported = true
                onText(text.trim())
            }
        }
    }
}

/** What a remote shell says when it cannot find or run the Rex binary. */
private fun looksLikeMissingRex(lower: String): Boolean =
    lower.contains("rex was not found") || lower.contains("command not found") ||
        lower.contains("no such file") || lower.contains("unknown command") ||
        lower.contains("dial-stdio") || lower.contains("unknown flag")

/** JSch's Android-usable Ed25519, wired in before any connection is made. */
private val jschConfigured = AtomicBoolean(false)

internal fun configureJsch() {
    if (!jschConfigured.compareAndSet(false, true)) return
    // JSch names signature classes after the algorithm itself, and uses two key names: one to
    // generate, one to derive a public key from an OpenSSH private key.
    JSch.setConfig("ssh-ed25519", Ed25519Signature::class.java.name)
    JSch.setConfig("keypairgen.eddsa", Ed25519KeyPairGen::class.java.name)
    JSch.setConfig("keypairgen_fromprivate.eddsa", Ed25519KeyPairGen::class.java.name)
    // X25519 needs a JCE implementation Android does not have; ECDH and the group exchanges are
    // offered by every server Rex runs on, and keep NIST's curve rather than a half-working one.
    JSch.setConfig(
        "kex",
        "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
            "diffie-hellman-group14-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512",
    )
}

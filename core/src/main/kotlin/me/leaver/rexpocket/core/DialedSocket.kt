package me.leaver.rexpocket.core

import okhttp3.Dns
import java.io.Closeable
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketImpl
import java.net.SocketOptions
import javax.net.SocketFactory

/**
 * A connection a tailnet node opened for the app, when the app has no socket of its own to it: the
 * Netbird add-on dials in its own process and hands back one end of a socket pair. The Rex
 * connection and the SSH tunnel use it through [DialedSocketFactory], as they use a SOCKS proxy for
 * the built-in nodes.
 */
interface DialedStream : Closeable {
    val input: InputStream
    val output: OutputStream

    /**
     * The descriptor under the streams, when there is one. Android's TLS works on the descriptor
     * itself rather than on the streams, so a socket that has none cannot carry TLS there.
     */
    val fileDescriptor: FileDescriptor? get() = null

    /** How long a read waits before it fails with a SocketTimeoutException; 0 is for ever. */
    fun setReadTimeout(millis: Int)

    fun shutdownInput()
    fun shutdownOutput()
}

/** Opens a [DialedStream] to [host] (a name or an address, unresolved) and [port]. Blocking. */
fun interface StreamDialer {
    fun dial(host: String, port: Int, timeoutMillis: Int): DialedStream
}

/**
 * Sockets whose connect is a dial through [dialer]. For OkHttp, use it with [UnresolvedDns] and no
 * proxy, so a tailnet name reaches the node as it is.
 */
class DialedSocketFactory(private val dialer: StreamDialer) : SocketFactory() {
    override fun createSocket(): Socket = DialedSocket(dialer)

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress.createUnresolved(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)
}

/**
 * OkHttp's resolver for a [DialedSocketFactory]: it resolves nothing, and hands the socket an
 * address that still carries the name, so the node resolves it.
 */
object UnresolvedDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(hostname, byteArrayOf(0, 0, 0, 0)))
}

/** A socket whose connection is a [DialedStream]; see [DialedSocketFactory]. */
class DialedSocket(dialer: StreamDialer) : Socket(DialedSocketImpl(dialer))

private class DialedSocketImpl(private val dialer: StreamDialer) : SocketImpl() {
    @Volatile private var stream: DialedStream? = null
    @Volatile private var readTimeout = 0

    private fun connected(): DialedStream = stream ?: throw SocketException("not connected")

    override fun create(stream: Boolean) = Unit

    override fun connect(host: String, port: Int) = connect(InetSocketAddress.createUnresolved(host, port), 0)

    override fun connect(address: InetAddress, port: Int) = connect(InetSocketAddress(address, port), 0)

    override fun connect(address: SocketAddress, timeout: Int) {
        val target = address as? InetSocketAddress ?: throw IllegalArgumentException("unsupported address $address")
        val dialed = dialer.dial(target.hostString, target.port, timeout)
        dialed.setReadTimeout(readTimeout)
        stream = dialed
        this.address = target.address ?: InetAddress.getByAddress(target.hostString, byteArrayOf(0, 0, 0, 0))
        this.port = target.port
        this.fd = dialed.fileDescriptor
    }

    override fun bind(host: InetAddress, port: Int) = throw SocketException("a dialed socket cannot be bound")
    override fun listen(backlog: Int) = throw SocketException("a dialed socket cannot listen")
    override fun accept(s: SocketImpl) = throw SocketException("a dialed socket cannot accept")
    override fun getInputStream(): InputStream = connected().input
    override fun getOutputStream(): OutputStream = connected().output
    override fun available(): Int = stream?.input?.available() ?: 0
    override fun sendUrgentData(data: Int) = throw SocketException("urgent data is not supported")
    override fun shutdownInput() = connected().shutdownInput()
    override fun shutdownOutput() = connected().shutdownOutput()

    override fun close() {
        stream?.close()
    }

    override fun setOption(optID: Int, value: Any?) {
        if (optID == SocketOptions.SO_TIMEOUT) {
            readTimeout = (value as? Int) ?: 0
            stream?.setReadTimeout(readTimeout)
        }
        // TCP options are the node's business: its connection is not this socket.
    }

    override fun getOption(optID: Int): Any? = when (optID) {
        SocketOptions.SO_TIMEOUT -> readTimeout
        SocketOptions.TCP_NODELAY, SocketOptions.SO_KEEPALIVE, SocketOptions.SO_OOBINLINE,
        SocketOptions.SO_REUSEADDR -> false
        SocketOptions.SO_LINGER -> -1
        SocketOptions.SO_SNDBUF, SocketOptions.SO_RCVBUF -> 64 * 1024
        SocketOptions.SO_BINDADDR -> InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0))
        SocketOptions.IP_TOS -> 0
        else -> null
    }
}

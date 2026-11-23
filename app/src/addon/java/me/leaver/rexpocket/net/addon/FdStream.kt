package me.leaver.rexpocket.net.addon

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import me.leaver.rexpocket.core.DialedStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * A connection the Netbird add-on dialed: one end of a Unix socket pair, which the add-on pipes to
 * its Netbird connection. A read waits at most the socket's read timeout; closing it wakes a read
 * blocked on another thread, as closing a socket does.
 */
internal class FdStream(private val pfd: ParcelFileDescriptor) : DialedStream {
    private val fd: FileDescriptor = pfd.fileDescriptor

    @Volatile private var readTimeout = 0
    @Volatile private var closed = false

    override val fileDescriptor: FileDescriptor get() = fd

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            awaitReadable()
            val n = io { Os.read(fd, b, off, len) }
            return if (n == 0) -1 else n
        }

        override fun close() = this@FdStream.close()
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) done += io { Os.write(fd, b, off + done, len - done) }
        }

        override fun close() = this@FdStream.close()
    }

    override fun setReadTimeout(millis: Int) {
        readTimeout = millis
    }

    override fun shutdownInput() = io { Os.shutdown(fd, OsConstants.SHUT_RD) }

    override fun shutdownOutput() = io { Os.shutdown(fd, OsConstants.SHUT_WR) }

    override fun close() {
        if (closed) return
        closed = true
        // A shutdown wakes a read blocked in another thread; closing the descriptor alone may not.
        runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
        runCatching { pfd.close() }
    }

    /** Waits for something to read, for at most the read timeout. */
    private fun awaitReadable() {
        val timeout = readTimeout
        if (timeout <= 0) return
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) throw SocketTimeoutException("Read timed out")
            val poll = StructPollfd().apply {
                fd = this@FdStream.fd
                events = OsConstants.POLLIN.toShort()
            }
            val ready = try {
                Os.poll(arrayOf(poll), left.toInt())
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw failure(e)
            }
            if (ready > 0) return
        }
    }

    private inline fun <T> io(block: () -> T): T = try {
        if (closed) throw SocketException("Socket closed")
        block()
    } catch (e: ErrnoException) {
        throw failure(e)
    }

    private fun failure(e: ErrnoException): IOException =
        if (closed) SocketException("Socket closed") else SocketException(e.message).apply { initCause(e) }
}

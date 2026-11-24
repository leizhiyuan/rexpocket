package me.leaver.rexpocket.net.addon

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.core.DialedSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The socket a dial through the Netbird add-on gives the connection, on a real socket pair: the
 * add-on's end is played here. Needs no add-on.
 */
@RunWith(AndroidJUnit4::class)
class FdStreamTest {
    private val pair = ParcelFileDescriptor.createSocketPair()
    private val addonEnd = pair[1]

    private fun socket() = DialedSocket { host, port, _ ->
        assertEquals("mac.netbird.cloud" to 7297, host to port)
        FdStream(pair[0])
    }.apply { connect(InetSocketAddress.createUnresolved("mac.netbird.cloud", 7297), 1000) }

    @Test
    fun bytesGoBothWaysAndAHalfCloseIsAnEnd() {
        val s = socket()
        FileOutputStream(addonEnd.fileDescriptor).write("SSH-2.0-x\r\n".toByteArray())
        val buf = ByteArray(11)
        var got = 0
        while (got < buf.size) got += s.getInputStream().read(buf, got, buf.size - got)
        assertEquals("SSH-2.0-x\r\n", String(buf))

        s.getOutputStream().write("hi".toByteArray())
        s.shutdownOutput()
        assertEquals("hi", FileInputStream(addonEnd.fileDescriptor).readBytes().decodeToString())
        s.close()
        addonEnd.close()
    }

    @Test
    fun aReadWaitsNoLongerThanTheTimeout() {
        val s = socket()
        s.soTimeout = 200
        val started = System.nanoTime()
        try {
            s.getInputStream().read()
            fail("read without data")
        } catch (e: SocketTimeoutException) {
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("waited $ms ms", ms in 150..5_000)
        }
        s.close()
        addonEnd.close()
    }

    @Test
    fun closingWakesABlockedRead() {
        val s = socket()
        val done = CountDownLatch(1)
        thread {
            runCatching { s.getInputStream().read() }
            done.countDown()
        }
        Thread.sleep(200)
        s.close()
        assertTrue("the read is still blocked", done.await(5, TimeUnit.SECONDS))
        addonEnd.close()
    }

    @Test
    fun thePeerClosingIsTheEndOfInput() {
        val s = socket()
        addonEnd.close()
        assertEquals(-1, s.getInputStream().read())
        s.close()
    }
}

package me.leaver.rexpocket.core

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The sockets the Netbird add-on's dials make: OkHttp and the SSH tunnel go through them unchanged. */
class DialedSocketTest {
    private val server = MockWebServer().apply { start() }
    private val dialed = CopyOnWriteArrayList<String>()

    /** A dialer that, whatever it is asked for, reaches [server]: the node's job, here on loopback. */
    private val dialer = StreamDialer { host, port, _ ->
        dialed += "$host:$port"
        SocketStream(Socket().apply { connect(InetSocketAddress("127.0.0.1", server.port)) })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun okHttpHandsTheNameToTheDialerUnresolved() {
        server.enqueue(MockResponse().setBody("rex"))
        val http = RexClient.defaultHttp(dialer = dialer)
        val body = http.newCall(Request.Builder().url("http://mac.netbird.cloud:7297/api").build()).execute().use { it.body!!.string() }
        assertEquals("rex", body)
        assertEquals(listOf("mac.netbird.cloud:7297"), dialed)
        assertEquals("mac.netbird.cloud:7297", server.takeRequest().getHeader("Host"))
    }

    @Test
    fun anAddressIsDialedAsItIs() {
        server.enqueue(MockResponse().setBody("ok"))
        RexClient.defaultHttp(dialer = dialer)
            .newCall(Request.Builder().url("http://100.86.1.2:7297/").build()).execute().close()
        assertEquals(listOf("100.86.1.2:7297"), dialed)
    }

    @Test
    fun aReadTimeoutReachesTheStream() {
        server.enqueue(MockResponse().setBody("late").setHeadersDelay(2, TimeUnit.SECONDS))
        val http = RexClient.defaultHttp(dialer = dialer).newBuilder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        try {
            http.newCall(Request.Builder().url("http://mac:7297/").build()).execute().close()
            fail("the response came in time")
        } catch (e: SocketTimeoutException) {
            // what OkHttp reports for a read that waited too long
        }
    }

    @Test
    fun aFailedDialIsTheConnectionsFailure() {
        val refusing = StreamDialer { host, _, _ -> throw IOException("$host is not a peer") }
        try {
            RexClient.defaultHttp(dialer = refusing).newCall(Request.Builder().url("http://gone:7297/").build()).execute()
            fail("the call went through")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("gone is not a peer"))
        }
    }

    @Test
    fun theSocketReportsWhereItGoesAndCloses() {
        ServerSocket(0).use { listener ->
            val socket = DialedSocket { host, port, timeout ->
                assertEquals("peer" to 22, host to port)
                assertEquals(1234, timeout)
                SocketStream(Socket().apply { connect(InetSocketAddress("127.0.0.1", listener.localPort)) })
            }
            socket.soTimeout = 50
            socket.connect(InetSocketAddress.createUnresolved("peer", 22), 1234)
            assertTrue(socket.isConnected)
            assertEquals(22, socket.port)
            assertEquals(50, socket.soTimeout)
            listener.accept().use { peer ->
                peer.getOutputStream().write("SSH-2.0-x\r\n".toByteArray())
                assertEquals('S'.code, socket.getInputStream().read())
            }
            socket.close()
            assertTrue(socket.isClosed)
        }
    }
}

/** A [DialedStream] on an ordinary socket. */
private class SocketStream(private val socket: Socket) : DialedStream {
    override val input = socket.getInputStream()
    override val output = socket.getOutputStream()
    override fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }
    override fun shutdownInput() = socket.shutdownInput()
    override fun shutdownOutput() = socket.shutdownOutput()
    override fun close() = socket.close()
}

package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

class RexClientTest {
    @Test
    fun plainClientHasNoProxy() {
        assertNull(RexClient.defaultHttp().proxy)
    }

    @Test
    fun socksClientSendsTheHostnameToTheLocalInbound() {
        val proxy = RexClient.defaultHttp(10800).proxy
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        assertEquals("127.0.0.1", proxy?.address()?.let { (it as java.net.InetSocketAddress).address.hostAddress })
        assertEquals(10800, (proxy?.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun everyAttemptSharesOneDispatcherAndKeepsItsOwnPool() {
        // A host down all night makes an attempt every few minutes: their threads are one set.
        val plain = RexClient.defaultHttp()
        val socks = RexClient.defaultHttp(10800)
        assertSame(plain.dispatcher, socks.dispatcher)
        assertSame(plain.dispatcher, RexClient.defaultHttp().dispatcher)
        // Each pane's stream is a WebSocket held on a dispatcher thread: one host's are never queued.
        assertTrue(plain.dispatcher.maxRequestsPerHost >= 1_000)
        assertTrue(plain.dispatcher.maxRequests >= 1_000)
        // A new attempt never picks up an idle connection an earlier one left, which may be dead.
        assertNotSame(plain.connectionPool, RexClient.defaultHttp().connectionPool)
    }
}

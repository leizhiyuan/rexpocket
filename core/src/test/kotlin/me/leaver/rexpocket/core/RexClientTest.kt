package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}

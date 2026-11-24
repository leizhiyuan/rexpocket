package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `rexpocket://add?addr=&name=`: what it may name, and what it never carries. See docs/design/host-link.md. */
class HostLinkTest {
    private fun ok(link: String) = HostLink.parse(link) as? HostLink.Read.Ok ?: throw AssertionError("refused: ${HostLink.parse(link)}")

    private fun refused(link: String) = assertTrue(link, HostLink.parse(link) is HostLink.Read.Refused)

    @Test
    fun aLinkNamesAnAddressAndAName() {
        assertEquals(HostLink.Read.Ok("desk.local:7297", "Desk mac"), ok("rexpocket://add?addr=desk.local%3A7297&name=Desk%20mac"))
        assertEquals(HostLink.Read.Ok("ssh://me@vm.example.com:2222", null), ok("rexpocket://add?addr=ssh%3A%2F%2Fme%40vm.example.com%3A2222"))
        assertEquals("studio.example.ts.net", ok("REXPOCKET://add/?name=&addr=studio.example.ts.net").address)
        assertEquals("[fd7a:115c::1]:7297", ok("rexpocket://add?addr=%5Bfd7a%3A115c%3A%3A1%5D%3A7297").address)
    }

    @Test
    fun whatALinkWritesItReads() {
        for ((address, name) in listOf("ssh://me@agents.example.com:22" to "agents", "wss://studio.example.ts.net" to "Studio & co", "desk.local" to null)) {
            assertEquals(HostLink.Read.Ok(address, name), HostLink.parse(HostLink.write(address, name)))
        }
    }

    @Test
    fun aLinkWithASecretOrNotAHostIsRefused() {
        refused("rexpocket://add?addr=ssh%3A%2F%2Fme%3Ahunter2%40vm.example.com")
        refused("rexpocket://add?name=nothing")
        refused("rexpocket://add?addr=")
        refused("rexpocket://remove?addr=desk.local")
        refused("https://add?addr=desk.local")
        refused("rexpocket://add?addr=desk.local&addr=evil.example.com")
        refused("rexpocket://add?addr=desk.local%3Fx%3D1")
        refused("rexpocket://add?addr=desk%20local")
        refused("rexpocket://add?addr=desk.local&name=%0Aevil")
        refused("rexpocket://add?addr=desk.local&name=" + "x".repeat(65))
        refused("rexpocket://add?addr=" + "a".repeat(2000))
        refused("rexpocket://add?addr=desk.local%3A99999")
    }
}

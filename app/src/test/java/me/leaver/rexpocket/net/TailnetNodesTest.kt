package me.leaver.rexpocket.net

import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.ui.FakeTailnetNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TailnetNodesTest {
    @Test
    fun aRegisteredBackendHasItsOwnNode() {
        val tailscale = FakeTailnetNode()
        val nodes = TailnetNodes(listOf(TailnetEntry(TailnetBackend.Tailscale, tailscale)))
        assertSame(tailscale, nodes[TailnetBackend.Tailscale])
        assertEquals(listOf(TailnetBackend.Tailscale), nodes.registered)
        assertEquals(listOf(TailnetBackend.Tailscale), nodes.available)
        assertEquals(listOf<TailnetNode>(tailscale), nodes.all)
    }

    @Test
    fun aBackendThisBuildLacksIsRegisteredButNotOffered() {
        val tailscale = FakeTailnetNode()
        val nodes = TailnetNodes(listOf(TailnetEntry(TailnetBackend.Tailscale, tailscale) { false }))
        assertEquals(listOf(TailnetBackend.Tailscale), nodes.registered)
        assertTrue(nodes.available.isEmpty())
        assertEquals(listOf<TailnetNode>(tailscale), nodes.all)
    }

    @Test(expected = IllegalArgumentException::class)
    fun tailscaleIsAlwaysRegistered() {
        TailnetNodes(emptyList())
    }

    @Test
    fun theBackendsAreInTheirOwnOrderWhateverTheRegistrationOrder() {
        val entries = TailnetBackend.entries.reversed().map { TailnetEntry(it, FakeTailnetNode()) }
        assertEquals(TailnetBackend.entries.toList(), TailnetNodes(entries).registered)
    }

    @Test
    fun anUnregisteredBackendFallsBackToTailscale() {
        val tailscale = FakeTailnetNode()
        val nodes = TailnetNodes(listOf(TailnetEntry(TailnetBackend.Tailscale, tailscale)))
        TailnetBackend.entries.forEach { assertSame(tailscale, nodes[it]) }
    }
}

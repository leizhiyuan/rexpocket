package me.leaver.rexpocket.netbird

import me.leaver.rexpocket.netbird.api.NetbirdAddon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The add-on's node and its accounts, with an engine played here: where each account keeps its
 * registration, one running at a time, the same settings kept, and a removed account's files gone.
 */
class NodesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeNode(val dir: File, val hostname: String) : EngineNode {
        var closed = false
        override fun state() = """{"backendState":"Running","peers":[]}"""
        override fun ping(ip: String, timeoutMillis: Int) = "{}"
        override fun dialError(host: String) = ""
        override fun dialFd(host: String, port: Int, timeoutMillis: Int) = -1
        override fun close() {
            closed = true
        }
    }

    private val started = mutableListOf<FakeNode>()
    private var changes = 0

    private fun nodes(fail: String? = null) = Nodes(tmp.root, engine = { dir, hostname, setupKey, _ ->
        fail?.let { throw IllegalStateException(it) }
        // A node writes its registration where it is told: the key stands in for it here.
        if (setupKey.isNotEmpty()) File(dir, "config.json").writeText("{}")
        FakeNode(dir, hostname).also { started += it }
    }) { changes++ }

    @Test
    fun theFirstAccountKeepsTheDirectoryTheAddonAlwaysHad() {
        val nodes = nodes()
        nodes.start(NetbirdAddon.FIRST_PROFILE, "phone", "KEY", "")
        assertEquals(File(tmp.root, "netbird"), started.single().dir)
        assertTrue(File(tmp.root, "netbird/config.json").isFile)
        assertEquals(1, changes)
    }

    @Test
    fun eachAccountHasADirectoryOfItsOwn() {
        val nodes = nodes()
        nodes.start("netbird-addon-work", "phone", "KEY", "")
        assertEquals(File(tmp.root, "netbird-profiles/netbird-addon-work"), started.single().dir)
    }

    @Test
    fun aNameCannotReachOutsideTheAccountsDirectory() {
        val nodes = nodes()
        assertEquals(File(tmp.root, "netbird-profiles/etc"), nodes.directoryFor("../../etc"))
        assertEquals(File(tmp.root, "netbird-profiles/a-b"), nodes.directoryFor("A/b"))
        try {
            nodes.directoryFor("../")
            fail("a name of dots alone was taken")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not an account name"))
        }
        assertNull(NetbirdAddon.profileDirectory("/.."))
    }

    @Test
    fun theSameAccountAndSettingsKeepTheRunningNode() {
        val nodes = nodes()
        nodes.start("work", "phone", "KEY", "https://nb.example")
        nodes.start("work", "phone", "", "https://nb.example")
        assertEquals(1, started.size)
        nodes.start("work", "tablet", "", "https://nb.example")
        assertEquals(2, started.size)
        assertTrue("the old node stopped first", started[0].closed)
    }

    @Test
    fun anotherAccountStopsTheRunningOneFirst() {
        val nodes = nodes()
        nodes.start(NetbirdAddon.FIRST_PROFILE, "phone", "KEY", "")
        nodes.start("work", "phone", "KEY2", "")
        assertTrue(started[0].closed)
        assertFalse(started[1].closed)
        assertEquals(started[1], nodes.node)
    }

    @Test
    fun aFailedStartLeavesNoNode() {
        val nodes = nodes(fail = "netbird: a setup key is needed to join the network")
        try {
            nodes.start("work", "phone", "", "")
            fail("started")
        } catch (e: IllegalStateException) {
            assertEquals("netbird: a setup key is needed to join the network", e.message)
        }
        assertNull(nodes.node)
        try {
            nodes.running()
            fail("a node runs")
        } catch (e: IllegalStateException) {
            assertEquals("the Netbird node is not running", e.message)
        }
    }

    @Test
    fun aRemovedAccountsRegistrationIsDeleted() {
        val nodes = nodes()
        nodes.start("work", "phone", "KEY", "")
        nodes.start(NetbirdAddon.FIRST_PROFILE, "phone", "KEY", "")
        val work = File(tmp.root, "netbird-profiles/work")
        assertTrue(File(work, "config.json").isFile)
        nodes.forget("work")
        assertFalse(work.exists())
        // Nothing kept is no failure: a second remove, or one of an account that never ran.
        nodes.forget("work")
        nodes.forget("never")
        assertTrue(File(tmp.root, "netbird/config.json").isFile)
    }

    @Test
    fun neitherTheRunningAccountNorTheFirstIsRemoved() {
        val nodes = nodes()
        nodes.start("work", "phone", "KEY", "")
        for (profile in listOf("work", NetbirdAddon.FIRST_PROFILE)) {
            try {
                nodes.forget(profile)
                fail("removed $profile")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        assertTrue(File(tmp.root, "netbird-profiles/work/config.json").isFile)
        nodes.stop()
        assertNull(nodes.node)
        nodes.forget("work")
        assertFalse(File(tmp.root, "netbird-profiles/work").exists())
    }
}

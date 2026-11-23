package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Import from Rex: the phone reads the connected Rex's host list (`hosts.list`) as hosts to import.
 * The desk puts a host there with `hosts.put` and removes it after. On ScriptedRex every run, and on
 * a real Rex where one listens (the `rex-real` workflow).
 */
abstract class RexHostListTest {
    abstract fun rex(): RexUnderTest

    @Test
    fun thePhoneReadsRexsHostsAsHostsToImport() = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = "host:0199abcd-1234-7abc-8def-" + (100000000000L + (System.nanoTime() % 899999999999L)).toString()
        val label = "rexpocket-import-" + id.takeLast(6)
        try {
            desk.connect()
            desk.putHost(id, label, "ssh://me@192.0.2.10:2222", "192.0.2.10:7297")
            phone.start(scope)
            withTimeout(15_000) { phone.awaitReady() }
            val read = phone.hostList() as HostImportParse.Parsed
            val host = read.hosts.single { it.name == label }
            assertEquals(ImportAddress("me", "192.0.2.10", 2222, true), host.address)
            assertEquals(listOf(ImportAddress(null, "192.0.2.10", 7297, true)), host.fallbacks)
            assertNull(host.fingerprint)
            // Planned like a file's: a new SSH host, which gets a key of its own, and no host key pinned.
            val planned = (ImportPlan.of(HostImportParse.Parsed(listOf(host)), emptyList()) as HostImportPlan.Planned).plan.hosts.single()
            assertEquals(ImportStatus.New, planned.status)
            assertEquals(KeyChange.Kept, planned.key)
            assertTrue(planned.needsKey)
            desk.removeHost(id)
            assertTrue((phone.hostList() as HostImportParse.Parsed).hosts.none { it.name == label })
        } finally {
            desk.removeHost(id)
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

class ScriptedRexHostListTest : RexHostListTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()
}

class RealRexHostListTest : RexHostListTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }
}

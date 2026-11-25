package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A session can hold blocks that are not terminals: a desk forwarding a port has a tunnel block,
 * whose owner is the one Rex hands each forwarded connection to. The phone showing that tab took the
 * block "on sight" as it takes a pane, so the desk's forwarding went to the phone and stopped
 * working, and the phone redialed a terminal stream the block does not have, again and again.
 */
abstract class OtherBlocksTest {
    abstract fun rex(): RexUnderTest

    @Test
    fun aTunnelOnScreenIsNeitherTakenNorStreamed() = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, _) = desk.newSession(tabs = 1)
            val (_, tunnel) = desk.newTunnel(sid)
            val deskOwns = desk.view(sid).allBlocks().first { it.id == tunnel }.owner
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            withTimeout(10_000) { while (phone.state.value.view?.focusedBlock()?.id != tunnel) delay(20) }

            val feeds = AtomicInteger()
            val watcher = scope.launch { phone.watchPane(tunnel).collect { feeds.incrementAndGet() } }
            delay(3_000)
            watcher.cancel()

            assertEquals("who has the tunnel", deskOwns, desk.view(sid).allBlocks().first { it.id == tunnel }.owner)
            assertEquals("a terminal stream of a tunnel", 0, feeds.get())
            assertEquals("the phone's notice", null, phone.state.value.notice)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

class ScriptedOtherBlocksTest : OtherBlocksTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()
}

class RealRexOtherBlocksTest : OtherBlocksTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }
}

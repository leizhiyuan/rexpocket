package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebSocket ping follows Rex's 120 s schedule, so a dead connection is found by the app's own
 * check while someone looks: a `session.list` every so often, on screen only (multi-host.md,
 * "Network and battery").
 */
class LivenessTest {
    private fun bootstraps(rex: ScriptedRex) = rex.calls.count { it.startsWith("session.bootstrap") }
    private fun lists(rex: ScriptedRex) = rex.calls.count { it.startsWith("session.list") }

    @Test
    fun onScreenAConnectionThatStopsAnsweringIsReplaced() = runBlocking {
        val rex = ScriptedRex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(rex.endpoint, livenessEveryMs = 300)
        try {
            session.start(scope)
            withTimeout(LOADED_MS) { session.awaitReady() }
            session.setActive(true)
            withTimeout(LOADED_MS) { while (lists(rex) < 2) delay(20) }
            assertEquals(1, bootstraps(rex))
            // The socket stays open, but nothing comes back any more.
            rex.hold("session.list")
            withTimeout(LOADED_MS) { while (bootstraps(rex) < 2) delay(50) }
        } finally {
            rex.release("session.list")
            session.stop()
            scope.cancel()
            rex.close()
        }
    }

    @Test
    fun awayFromTheScreenNothingIsAsked() = runBlocking {
        val rex = ScriptedRex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(rex.endpoint, livenessEveryMs = 200)
        try {
            session.setActive(false)
            session.start(scope)
            withTimeout(LOADED_MS) { session.awaitReady() }
            val before = lists(rex)
            delay(1_500)
            assertEquals("liveness calls while away", before, lists(rex))
            session.setActive(true)
            withTimeout(LOADED_MS) { while (lists(rex) < before + 2) delay(20) }
            assertTrue(session.state.value.phase == Phase.Live)
        } finally {
            session.stop()
            scope.cancel()
            rex.close()
        }
    }

    /**
     * A network change: a host reached through a tailnet tunnel, which follows the phone onto the
     * new network, keeps a connection that still answers; a direct one redials at once.
     */
    @Test
    fun aNetworkChangeKeepsATunnelledConnectionThatAnswers() = runBlocking {
        val rex = ScriptedRex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = PocketSession(rex.endpoint)
        try {
            session.start(scope)
            withTimeout(LOADED_MS) { session.awaitReady() }
            val asked = lists(rex)
            session.networkChanged(roams = true)
            withTimeout(LOADED_MS) { while (lists(rex) == asked) delay(20) }
            // The answer is on its way back; a connection replaced would bootstrap again.
            delay(1_000)
            assertEquals("a tunnelled connection that answers is kept", 1, bootstraps(rex))
            assertEquals(Phase.Live, session.state.value.phase)
            rex.hold("session.list")
            session.networkChanged(roams = true)
            withTimeout(LOADED_MS) { while (bootstraps(rex) < 2) delay(50) }
            rex.release("session.list")
            withTimeout(LOADED_MS) { while (session.state.value.phase != Phase.Live) delay(20) }
            session.networkChanged(roams = false)
            withTimeout(LOADED_MS) { while (bootstraps(rex) < 3) delay(50) }
        } finally {
            rex.release("session.list")
            session.stop()
            scope.cancel()
            rex.close()
        }
    }

    private companion object {
        /**
         * How long a wait for something that will happen may take: generous, as these run beside
         * the whole suite. Each wait ends as soon as its condition holds.
         */
        const val LOADED_MS = 30_000L
    }
}

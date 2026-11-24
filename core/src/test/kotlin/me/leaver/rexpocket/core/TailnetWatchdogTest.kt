package me.leaver.rexpocket.core

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class TailnetWatchdogTest {
    private val deadline = listOf("management: rpc error: code = DeadlineExceeded desc = context deadline exceeded")

    @Test
    fun aRunningNodeIsNotInTrouble() {
        assertNull(nodeTrouble("Running", deadline))
        assertNull(nodeTrouble("NeedsLogin", emptyList()))
        assertNull(nodeTrouble("", emptyList()))
    }

    @Test
    fun aStartingNodeSaysWhichConnectionFailsAndHow() {
        assertEquals("still starting · management · DeadlineExceeded", nodeTrouble("Starting", deadline))
        assertEquals("still starting · signal · Unavailable", nodeTrouble("Starting", listOf("signal: rpc error: code = Unavailable desc = x")))
        assertEquals("still starting", nodeTrouble("Starting", emptyList()))
    }

    @Test
    fun theNetworkNotAnsweringIsWorthAnotherStartAKeyIsNot() {
        assertTrue(transientStartFailure("create auth client: create connection: dial context: context deadline exceeded"))
        assertTrue(transientStartFailure("rpc error: code = Unavailable desc = connection error"))
        assertFalse(transientStartFailure("a setup key is needed to join the network"))
        assertFalse(transientStartFailure(null))
    }

    @Test
    fun theBackoffDoublesFrom15sTo5Minutes() {
        assertEquals(15_000L, watchdogBackoffMs(1))
        assertEquals(30_000L, watchdogBackoffMs(2))
        assertEquals(240_000L, watchdogBackoffMs(5))
        assertEquals(300_000L, watchdogBackoffMs(9))
    }

    @Test
    fun aNodeInTroubleForTheBoundIsRestartedOnlyWhileNeeded() {
        val dog = NodeWatchdog(stuckAfterMs = 20_000)
        assertFalse(dog.observe("still starting", needed = true, now = 0))
        assertFalse(dog.observe("still starting", needed = true, now = 19_999))
        assertFalse(dog.observe("still starting", needed = false, now = 20_000))
        assertTrue(dog.stuck(20_000))
        assertTrue(dog.observe("still starting", needed = true, now = 20_000))
    }

    @Test
    fun afterARestartTheNextWaitsForTheBackoffAndHealthResetsIt() {
        val dog = NodeWatchdog(stuckAfterMs = 20_000)
        dog.observe("x", true, 0)
        dog.restarted(20_000)
        assertEquals(1, dog.restarts)
        assertFalse(dog.stuck(20_000))
        dog.observe("x", true, 20_000)
        // The bound has passed again, but the backoff (15 s from the restart, then the bound) holds.
        assertTrue(dog.observe("x", true, 40_000))
        dog.restarted(40_000)
        dog.observe("x", true, 40_000)
        assertFalse(dog.observe("x", true, 65_000))
        assertTrue(dog.observe("x", true, 70_000))
        assertFalse(dog.observe(null, true, 71_000))
        assertEquals(0, dog.restarts)
        assertFalse(dog.troubled)
    }
}

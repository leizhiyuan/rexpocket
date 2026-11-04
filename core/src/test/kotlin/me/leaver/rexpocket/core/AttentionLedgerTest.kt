package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionLedgerTest {
    private fun record(state: String, kind: String? = null, msg: String? = null, title: String? = null) =
        ProgramRecord(id = "r", state = state, kind = kind, app = "pi", title = title, msg = msg, progress = null)

    @Test
    fun aPaneThatStartsWaitingIsRaisedOnce() {
        val ledger = AttentionLedger()
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("working"))).raised)
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("blocked", "permission"))).raised)
        // The same request reported again, or with a new title while it animates, is not news.
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("blocked", "permission"))).raised)
        assertEquals(
            emptyList<String>(),
            ledger.observe(mapOf("a" to record("blocked", "permission", title = "⠋ pi"))).raised,
        )
    }

    @Test
    fun aNewQuestionOrAnErrorIsRaisedAgain() {
        val ledger = AttentionLedger()
        ledger.observe(mapOf("a" to record("blocked", "permission", msg = "run ls?")))
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("blocked", "permission", msg = "run rm?"))).raised)
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("error", msg = "crashed"))).raised)
    }

    @Test
    fun runningAndFinishedAreNeverRaised() {
        val ledger = AttentionLedger()
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("working"), "b" to record("done"))).raised)
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("idle"), "b" to record("working"))).raised)
    }

    @Test
    fun aPaneThatStopsWaitingOrGoesAwayIsSettled() {
        val ledger = AttentionLedger()
        ledger.observe(mapOf("a" to record("blocked"), "b" to record("error"), "c" to record("working")))
        val changes = ledger.observe(mapOf("a" to record("working")))
        assertEquals(emptyList<String>(), changes.raised)
        assertEquals(setOf("a", "b"), changes.settled.toSet())
        // Once settled, the next wait is news again.
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("blocked"))).raised)
    }

    @Test
    fun anAlertTokenOpensItsTargetOnce() {
        val tokens = AlertTokens<String>(limit = 2)
        val first = tokens.issue("pane a")
        assertEquals("pane a", tokens.consume(first))
        assertEquals(null, tokens.consume(first))
        assertEquals(null, tokens.consume("made-up"))
        assertEquals(null, tokens.consume(null))
    }

    @Test
    fun theOldestTokensGoFirst() {
        val tokens = AlertTokens<String>(limit = 2)
        val a = tokens.issue("a")
        val b = tokens.issue("b")
        val c = tokens.issue("c")
        assertEquals(null, tokens.consume(a))
        assertEquals("b", tokens.consume(b))
        assertEquals("c", tokens.consume(c))
    }
}

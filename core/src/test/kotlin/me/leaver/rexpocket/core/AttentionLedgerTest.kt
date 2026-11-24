package me.leaver.rexpocket.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun runningAndIdleAreNeverRaised() {
        val ledger = AttentionLedger()
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("working"), "b" to record("idle"))).raised)
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("idle"), "b" to record("made-up"))).raised)
    }

    @Test
    fun aResultReadyIsRaisedAndANewOneAgain() {
        val ledger = AttentionLedger()
        ledger.observe(mapOf("a" to record("working")))
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("done", msg = "built"))).raised)
        assertEquals(emptyList<String>(), ledger.observe(mapOf("a" to record("done", msg = "built"))).raised)
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("done", msg = "tested"))).raised)
        // Back to work: the result was looked at, or replaced.
        assertEquals(listOf("a"), ledger.observe(mapOf("a" to record("working"))).settled)
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
    fun anAlertsSignatureOpensExactlyItsTarget() {
        val key = AlertSigner.newKey()
        val signer = AlertSigner(key)
        val sig = signer.sign("open", "host", "session", "block")
        assertTrue(signer.verify(sig, "open", "host", "session", "block"))
        // Another pane, another kind of link, or the fields run together: refused.
        assertFalse(signer.verify(sig, "open", "host", "session", "other"))
        assertFalse(signer.verify(sig, "group", "host", "session", "block"))
        assertFalse(signer.verify(signer.sign("open", "ab", "c"), "open", "a", "bc"))
        assertFalse(signer.verify(null, "open"))
        assertFalse(signer.verify("", "open"))
        assertFalse(signer.verify("not base64!", "open"))
        val tampered = (if (sig.first() == 'A') "B" else "A") + sig.drop(1)
        assertFalse(signer.verify(tampered, "open", "host", "session", "block"))
    }

    /** A signature outlives the process (the same key, read again) and any number of later alerts; another install's key refuses it. */
    @Test
    fun aSignatureStillOpensAfterARestartButNotWithAnotherKey() {
        val key = AlertSigner.newKey()
        val sig = AlertSigner(key).sign("open", "h", "s", "b")
        val again = AlertSigner(key.copyOf())
        repeat(100) { again.sign("open", "h", "s", "b$it") }
        assertTrue(again.verify(sig, "open", "h", "s", "b"))
        assertFalse(AlertSigner(AlertSigner.newKey()).verify(sig, "open", "h", "s", "b"))
        assertEquals("AlertSigner", again.toString())
    }
}

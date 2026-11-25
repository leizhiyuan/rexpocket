package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Rex takes a message of at most its welcome's `max_message_bytes` (1 MiB) and ends the whole
 * connection on a larger one (1009, "read limited at 1048577 bytes"). A big paste - a log shared
 * into the app, a clipboard of output - took every pane of the host down with it and was lost. It
 * now goes in pieces that fit, in order, on the same connection.
 */
abstract class LargePasteTest {
    abstract fun rex(): RexUnderTest

    /** Readies [block] to take [size] pasted bytes and say how many it got. */
    abstract suspend fun expect(desk: Desk, sid: String, block: String, size: Int)

    /** Waits until [block] got all of [text]. */
    abstract suspend fun awaitPasted(rex: RexUnderTest, desk: Desk, sid: String, block: String, text: String)

    /** Lines of a log, 2.5 MB in all: more than two of Rex's messages. */
    private val log = (0 until 25_000).joinToString("") { "line %05d ".format(it) + "x".repeat(88) + "\n" }

    @Test
    fun aPasteLargerThanOneMessageArrivesWholeOnTheSameConnection() = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 1)
            val block = tabs.first().second
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            val client = phone.state.value.clientId
            expect(desk, sid, block, log.length)

            withTimeout(60_000) { phone.paste(log, sid, block) }

            awaitPasted(rex, desk, sid, block, log)
            assertEquals("the connection the paste went on", client, phone.state.value.clientId)
            assertEquals(Phase.Live, phone.state.value.phase)
            assertTrue("a resumed connection means it dropped", phone.state.value.resumed != true)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

class ScriptedLargePasteTest : LargePasteTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    @Test
    fun piecesFitAndJoinBackToTheText() {
        val text = "é\"\\/\u0001😀\n".repeat(40_000) + "end"
        val pieces = requestPieces(text, MIN_MESSAGE_BYTES)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.size > 1)
        for (p in pieces) {
            val json = org.json.JSONObject().put("type", "request").put("id", "123456789").put("method", "block.event")
                .put("payload", org.json.JSONObject().put("session_id", "session:" + "0".repeat(36)).put("block_id", "block:" + "0".repeat(36))
                    .put("event", org.json.JSONObject().put("type", "paste").put("payload", org.json.JSONObject().put("text", p))))
            assertTrue("a piece's request is ${json.toString().toByteArray().size} bytes", json.toString().toByteArray().size <= MIN_MESSAGE_BYTES)
            assertTrue("a piece ends inside a surrogate pair", !Character.isHighSurrogate(p.last()))
        }
        assertEquals(listOf("small"), requestPieces("small", MIN_MESSAGE_BYTES))
    }

    /** Any request too large for one message fails by itself, before it is sent; the connection stays. */
    @Test
    fun aRequestTooLargeFailsAloneAndTheConnectionStays() = runBlocking {
        ScriptedRex().use { rex ->
            val client = RexClient()
            client.connect(rex.endpoint)
            val refused = runCatching {
                client.call("session.list", org.json.JSONObject().put("filler", "x".repeat(ScriptedRex.MAX_MESSAGE_BYTES)))
            }.exceptionOrNull()
            assertEquals("too_large", (refused as? RexCallException)?.code)
            client.call("session.list")
            client.close()
        }
    }

    override suspend fun expect(desk: Desk, sid: String, block: String, size: Int) = Unit

    override suspend fun awaitPasted(rex: RexUnderTest, desk: Desk, sid: String, block: String, text: String) {
        val fake = (rex as FakeRexUnderTest).rex
        val got = { fake.pastes.filter { it.first == block }.joinToString("") { it.second } }
        withTimeoutOrNull(10_000) { while (got().length < text.length) delay(50) }
        assertEquals("what reached the pane", text.length, got().length)
        assertTrue("the paste came in order", got() == text)
    }
}

class RealRexLargePasteTest : LargePasteTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    /** `head` takes the bytes as the terminal hands them over and `wc` says how many came. */
    override suspend fun expect(desk: Desk, sid: String, block: String, size: Int) {
        desk.type(sid, block, "stty -echo; head -c $size | wc -c | sed 's/^ */pasted-/'\n")
        delay(1_000)
    }

    override suspend fun awaitPasted(rex: RexUnderTest, desk: Desk, sid: String, block: String, text: String) {
        val want = "pasted-${text.length}"
        withTimeoutOrNull(60_000) { while (!desk.text(sid, block).contains(want)) delay(500) }
            ?: throw AssertionError("never saw $want in: ${desk.text(sid, block).takeLast(300)}")
    }
}

package me.leaver.rexpocket.core

import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDecoderTest {
    private val nonce = 17907718046957349611uL

    private fun snapshot(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("GHOSTSNP".toByteArray())
        out.write(byteArrayOf(1, 0))
        for ((tag, payload) in listOf(1 to "terminal", 2 to "screen", 5 to "", 6 to "")) {
            val body = payload.toByteArray()
            out.write(byteArrayOf(tag.toByte(), 0))
            out.write(le32(body.size))
            out.write(byteArrayOf(0, 0, 0, 0)) // the CRC is the decoder's business, not the splitter's
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun rex(json: String, nonce: ULong = this.nonce) =
        "\u001b_rex1;$nonce;$json\u001b\\".toByteArray()

    @Test
    fun theSnapshotIsSplitOffAndTheOutputAfterItIsKept() {
        val stream = snapshot() + "hello\r\n".toByteArray()
        val feeds = StreamDecoder(nonce).feed(stream)
        assertEquals(2, feeds.size)
        assertArrayEquals(snapshot(), (feeds[0] as TerminalFeed.Snapshot).bytes)
        assertEquals("hello\r\n", (feeds[1] as TerminalFeed.Output).bytes.decodeToString())
    }

    @Test
    fun aStreamSplitAcrossMessagesComesOutTheSame() {
        val stream = snapshot() + "line 1\r\n".toByteArray() + rex("""{"type":"resize","cols":80}""") + "line 2\r\n".toByteArray()
        val decoder = StreamDecoder(nonce)
        val out = java.io.ByteArrayOutputStream()
        var snapshots = 0
        // One byte at a time: the worst case a WebSocket can hand over.
        for (b in stream) {
            for (feed in decoder.feed(byteArrayOf(b))) {
                when (feed) {
                    is TerminalFeed.Snapshot -> snapshots++
                    is TerminalFeed.Output -> out.write(feed.bytes)
                }
            }
        }
        assertEquals(1, snapshots)
        assertEquals("line 1\r\nline 2\r\n", out.toString())
    }

    @Test
    fun rexOwnMessagesAreDropped() {
        val stream = snapshot() + "a".toByteArray() + rex("""{"type":"theme"}""") + "b".toByteArray()
        val feeds = StreamDecoder(nonce).feed(stream)
        assertEquals("ab", (feeds.last() as TerminalFeed.Output).bytes.decodeToString())
    }

    @Test
    fun aMessageAProgramPrintedIsKept() {
        // Another client's message: the nonce differs, so it is program output and must reach the screen.
        val printed = rex("""{"type":"resize"}""", nonce = nonce + 1uL)
        val feeds = StreamDecoder(nonce).feed(snapshot() + printed)
        assertArrayEquals(printed, (feeds.last() as TerminalFeed.Output).bytes)
    }

    @Test
    fun anOrdinaryApcSequenceIsUntouched() {
        val kitty = "\u001b_Gi=1;AAAA\u001b\\".toByteArray()
        val feeds = StreamDecoder(nonce).feed(snapshot() + kitty)
        assertArrayEquals(kitty, (feeds.last() as TerminalFeed.Output).bytes)
    }

    @Test
    fun bytesThatCannotBeASnapshotAreShownAsOutput() {
        val feeds = StreamDecoder(nonce).feed("plain output".toByteArray())
        assertEquals("plain output", (feeds.single() as TerminalFeed.Output).bytes.decodeToString())
    }

    @Test
    fun aTruncatedSnapshotWaitsForTheRest() {
        val stream = snapshot()
        val decoder = StreamDecoder(nonce)
        assertTrue(decoder.feed(stream.copyOf(stream.size - 3)).isEmpty())
        val feeds = decoder.feed(stream.copyOfRange(stream.size - 3, stream.size))
        assertEquals(1, feeds.size)
        assertTrue(feeds.single() is TerminalFeed.Snapshot)
    }

    @Test
    fun anUnfinishedRexMessageHoldsBackOnlyItsOwnBytes() {
        val decoder = StreamDecoder(nonce)
        val first = decoder.feed(snapshot() + "before".toByteArray() + "\u001b_rex1;${nonce};{\"ty".toByteArray())
        assertEquals("before", (first.last() as TerminalFeed.Output).bytes.decodeToString())
        val second = decoder.feed("pe\":\"resize\"}\u001b\\after".toByteArray())
        assertEquals("after", (second.single() as TerminalFeed.Output).bytes.decodeToString())
    }
}

/** The two handshakes a data connection can open with, byte for byte. */
class HandshakeTest {
    @Test
    fun aHelloCarriesTheOpcodeAndTheTicket() {
        val ticket = ByteArray(16) { it.toByte() }
        val bytes = TerminalStream.helloHandshake(ticket)
        assertEquals(17, bytes.size)
        assertEquals(0x01, bytes[0].toInt())
        assertArrayEquals(ticket, bytes.copyOfRange(1, 17))
    }

    @Test
    fun aResumeCarriesTheTokenAndHowManyBytesWereUsed() {
        val token = ByteArray(16) { (it + 100).toByte() }.toByteString()
        val bytes = TerminalStream.resumeHandshake(token, received = 0x0102030405L)
        assertEquals(25, bytes.size)
        assertEquals(0x02, bytes[0].toInt())
        assertArrayEquals(token.toByteArray(), bytes.copyOfRange(1, 17))
        // Little-endian, so the low byte comes first.
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1, 0, 0, 0), bytes.copyOfRange(17, 25))
    }
}

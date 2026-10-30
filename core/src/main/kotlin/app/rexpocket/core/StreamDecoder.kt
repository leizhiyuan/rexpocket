package app.rexpocket.core

/** Splits the first bytes of a Rex terminal stream off: they are a `GHOSTSNP` snapshot of the pane. */
internal object SnapshotFrame {
    private val MAGIC = "GHOSTSNP".toByteArray(Charsets.US_ASCII)
    private const val VERSION_BYTES = 2
    private const val RECORD_HEADER = 10
    private const val TAG_FINISH = 6

    /** Longest record this accepts; the format's pages are far smaller, and a wrong guess must not wait forever. */
    private const val MAX_RECORD = 64 * 1024 * 1024

    /**
     * Length of the snapshot at the start of [bytes], or null while more bytes are needed. Malformed
     * input returns -1: the caller then treats it as program output rather than losing it.
     */
    fun length(bytes: ByteArray, size: Int = bytes.size): Int {
        for (i in 0 until minOf(size, MAGIC.size)) if (bytes[i] != MAGIC[i]) return -1
        if (size < MAGIC.size + VERSION_BYTES) return 0
        var at = MAGIC.size + VERSION_BYTES
        while (true) {
            if (at + RECORD_HEADER > size) return 0
            val tag = readShort(bytes, at)
            val payload = readInt(bytes, at + 2)
            if (payload < 0 || payload > MAX_RECORD) return -1
            val end = at + RECORD_HEADER + payload
            if (end > size) return 0
            if (tag == TAG_FINISH) return end
            at = end
        }
    }

    private fun readShort(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
}

/** Something to put into the terminal's emulator, in the order it arrived. */
sealed interface TerminalFeed {
    /** The whole pane as Rex has it: the start of every stream, and again after a reconnect. */
    class Snapshot(val bytes: ByteArray) : TerminalFeed

    /** Program output, with Rex's own messages taken out. */
    class Output(val bytes: ByteArray) : TerminalFeed
}

/**
 * Turns the bytes of a terminal data stream into what the emulator takes. A stream begins with a
 * `GHOSTSNP` snapshot; after it, program output arrives with Rex's own messages in it, each an
 * `ESC _ rex1;<nonce>;<json> ESC \` carrying a resize or a theme. Those are the client's own
 * business, and are dropped here; one whose nonce differs was printed by a program and is kept.
 *
 * Not thread-safe: one reader feeds it in order.
 */
internal class StreamDecoder(private val nonce: ULong) {
    private val nonceText = nonce.toString()
    private var pending = ByteArray(0)
    private var snapshotDone = false

    /** Takes the next bytes of the stream and returns what is ready to be shown. */
    fun feed(bytes: ByteArray): List<TerminalFeed> {
        if (bytes.isEmpty()) return emptyList()
        val buffer = if (pending.isEmpty()) bytes else pending + bytes
        pending = ByteArray(0)
        val out = ArrayList<TerminalFeed>(2)
        var at = 0
        if (!snapshotDone) {
            when (val length = SnapshotFrame.length(buffer)) {
                0 -> {
                    // Still incomplete, unless it cannot be a snapshot at all.
                    pending = buffer
                    return out
                }
                -1 -> snapshotDone = true
                else -> {
                    out += TerminalFeed.Snapshot(buffer.copyOfRange(0, length))
                    at = length
                    snapshotDone = true
                }
            }
        }
        val text = stripRexMessages(buffer, at)
        if (text != null) {
            pending = text.second
            if (text.first.isNotEmpty()) out += TerminalFeed.Output(text.first)
        }
        return out
    }

    /** Output from [from], and the unfinished message held back at the end (if any). */
    private fun stripRexMessages(buffer: ByteArray, from: Int): Pair<ByteArray, ByteArray>? {
        val out = java.io.ByteArrayOutputStream(buffer.size - from)
        var i = from
        while (i < buffer.size) {
            // An escape sequence split by the end of this message: the next byte says whether it is
            // one of Rex's, so it waits.
            if (buffer[i] == ESC && i + 1 >= buffer.size) {
                return out.toByteArray() to buffer.copyOfRange(i, buffer.size)
            }
            if (buffer[i] == ESC && buffer[i + 1] == APC) {
                when (val match = matchRexMessage(buffer, i)) {
                    is RexMatch.Mine -> i = match.end
                    RexMatch.Partial -> {
                        if (buffer.size - i <= MAX_HELD) {
                            return out.toByteArray() to buffer.copyOfRange(i, buffer.size)
                        }
                        out.write(buffer[i].toInt())
                        i++
                    }
                    RexMatch.NotMine -> {
                        out.write(buffer[i].toInt())
                        i++
                    }
                }
                continue
            }
            out.write(buffer[i].toInt())
            i++
        }
        return out.toByteArray() to ByteArray(0)
    }

    /** Whether the APC at [start] is one of Rex's, complete ([RexMatch.Mine]) or still arriving. */
    private fun matchRexMessage(buffer: ByteArray, start: Int): RexMatch {
        var i = start + 2
        for (c in PREFIX) {
            if (i >= buffer.size) return RexMatch.Partial
            if (buffer[i] != c) return RexMatch.NotMine
            i++
        }
        for (c in nonceText) {
            if (i >= buffer.size) return RexMatch.Partial
            if (buffer[i] != c.code.toByte()) return RexMatch.NotMine
            i++
        }
        if (i >= buffer.size) return RexMatch.Partial
        if (buffer[i] != SEMICOLON) return RexMatch.NotMine
        i++
        while (i < buffer.size) {
            if (buffer[i] == ESC && i + 1 < buffer.size && buffer[i + 1] == ST) return RexMatch.Mine(i + 2)
            i++
        }
        return RexMatch.Partial
    }

    private sealed interface RexMatch {
        class Mine(val end: Int) : RexMatch
        data object Partial : RexMatch
        data object NotMine : RexMatch
    }

    private companion object {
        val PREFIX = "rex1;".toByteArray(Charsets.US_ASCII)
        const val ESC = 0x1b.toByte()
        const val APC = '_'.code.toByte()
        const val ST = '\\'.code.toByte()
        const val SEMICOLON = ';'.code.toByte()

        /** A program that prints the start of a Rex message and never ends it must not stall the screen. */
        const val MAX_HELD = 64 * 1024
    }
}

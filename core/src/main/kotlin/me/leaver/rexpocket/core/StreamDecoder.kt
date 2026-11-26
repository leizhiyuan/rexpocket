package me.leaver.rexpocket.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn

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
 * These feeds with the output that is already waiting joined into one [TerminalFeed.Output] of at
 * most [maxBytes] (a piece larger than that on its own still goes whole), so a fast program costs
 * one write into the emulator and one redraw per batch rather than per network message. Order is
 * kept: a snapshot is never moved past the output around it. What arrives while the collector is
 * busy waits in a buffer of [buffered] feeds; when that is full, the stream upstream waits.
 */
fun Flow<TerminalFeed>.coalesced(maxBytes: Int = COALESCE_MAX_BYTES, buffered: Int = 64): Flow<TerminalFeed> = flow {
    coroutineScope {
        val waiting = this@coalesced.buffer(buffered).produceIn(this)
        var next: TerminalFeed? = null
        while (true) {
            val feed = next ?: waiting.receiveCatching().let { it.getOrNull() ?: it.exceptionOrNull()?.let { e -> throw e } ?: return@coroutineScope }
            next = null
            if (feed !is TerminalFeed.Output) {
                emit(feed)
                continue
            }
            var parts: ArrayList<ByteArray>? = null
            var total = feed.bytes.size
            while (total < maxBytes) {
                val more = waiting.tryReceive().getOrNull() ?: break
                if (more !is TerminalFeed.Output || total + more.bytes.size > maxBytes) {
                    next = more
                    break
                }
                if (parts == null) parts = arrayListOf(feed.bytes)
                parts += more.bytes
                total += more.bytes.size
            }
            if (parts == null) {
                emit(feed)
            } else {
                val joined = ByteArray(total)
                var at = 0
                for (part in parts) {
                    part.copyInto(joined, at)
                    at += part.size
                }
                emit(TerminalFeed.Output(joined))
            }
        }
    }
}

/** The most output [coalesced] joins into one write: a few frames' worth at a fast `cat`. */
const val COALESCE_MAX_BYTES = 256 * 1024

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

    /**
     * The bytes of the stream not decoded yet, and how many of [buffer] they occupy. A large
     * snapshot arrives in whatever pieces the network hands over - many small ones over SSH - so
     * appending grows the array geometrically instead of copying all of it on every piece.
     */
    private var buffer = ByteArray(0)
    private var size = 0
    private var snapshotDone = false

    /** Takes the next bytes of the stream and returns what is ready to be shown. */
    fun feed(bytes: ByteArray): List<TerminalFeed> {
        if (bytes.isEmpty()) return emptyList()
        if (snapshotDone && size == 0) {
            // The usual case once the picture is in: nothing held back, so the bytes are read
            // where they are, and handed on as they came when there was nothing of Rex's in them.
            val text = stripRexMessages(bytes, 0, bytes.size, share = true)
            if (text.second.isNotEmpty()) consume(text.second)
            return if (text.first.isEmpty()) emptyList() else listOf(TerminalFeed.Output(text.first))
        }
        append(bytes)
        val out = ArrayList<TerminalFeed>(2)
        var at = 0
        if (!snapshotDone) {
            when (val length = SnapshotFrame.length(buffer, size)) {
                0 -> return out // Still incomplete, and it may be a snapshot yet.
                -1 -> snapshotDone = true
                else -> {
                    out += TerminalFeed.Snapshot(buffer.copyOfRange(0, length))
                    at = length
                    snapshotDone = true
                }
            }
        }
        val text = stripRexMessages(buffer, at, size)
        if (text.first.isNotEmpty()) out += TerminalFeed.Output(text.first)
        consume(text.second)
        return out
    }

    /** Appends without copying the old bytes every time: a snapshot can be megabytes. */
    private fun append(bytes: ByteArray) {
        val needed = size + bytes.size
        if (needed > buffer.size) {
            var capacity = if (buffer.isEmpty()) 256 else buffer.size
            while (capacity < needed) capacity = maxOf(capacity * 2, needed)
            buffer = buffer.copyOf(capacity)
        }
        bytes.copyInto(buffer, size)
        size = needed
    }

    /** Keeps [rest] as the start of the next message, in the same array when there is room. */
    private fun consume(rest: ByteArray) {
        if (rest.size > buffer.size) buffer = rest.copyOf() else rest.copyInto(buffer, 0)
        size = rest.size
    }

    /**
     * Output from [from], up to [end], and the unfinished message held back (empty if none). Runs
     * between escapes are copied whole, not byte by byte; with [share], [buffer] itself is the
     * output when it is all output.
     */
    private fun stripRexMessages(buffer: ByteArray, from: Int, end: Int, share: Boolean = false): Pair<ByteArray, ByteArray> {
        val out = ByteArray(end - from)
        var n = 0
        // The output not copied yet starts at [run].
        var run = from
        var i = from
        fun take(upTo: Int) {
            buffer.copyInto(out, n, run, upTo)
            n += upTo - run
        }
        while (i < end) {
            if (buffer[i] != ESC) {
                i++
                continue
            }
            // An escape sequence split by the end of this message: the next byte says whether it is
            // one of Rex's, so it waits.
            if (i + 1 >= end) {
                take(i)
                return out.copyOf(n) to buffer.copyOfRange(i, end)
            }
            if (buffer[i + 1] != APC) {
                i++
                continue
            }
            when (val match = matchRexMessage(buffer, i, end)) {
                is RexMatch.Mine -> {
                    take(i)
                    i = match.end
                    run = i
                }
                RexMatch.Partial -> {
                    if (end - i <= MAX_HELD) {
                        take(i)
                        return out.copyOf(n) to buffer.copyOfRange(i, end)
                    }
                    i++
                }
                RexMatch.NotMine -> i++
            }
        }
        if (share && run == 0 && from == 0 && end == buffer.size) return buffer to NOTHING
        take(end)
        return (if (n == out.size) out else out.copyOf(n)) to NOTHING
    }

    /** Whether the APC at [start] is one of Rex's, complete ([RexMatch.Mine]) or still arriving. */
    private fun matchRexMessage(buffer: ByteArray, start: Int, end: Int): RexMatch {
        var i = start + 2
        for (c in PREFIX) {
            if (i >= end) return RexMatch.Partial
            if (buffer[i] != c) return RexMatch.NotMine
            i++
        }
        for (c in nonceText) {
            if (i >= end) return RexMatch.Partial
            if (buffer[i] != c.code.toByte()) return RexMatch.NotMine
            i++
        }
        if (i >= end) return RexMatch.Partial
        if (buffer[i] != SEMICOLON) return RexMatch.NotMine
        i++
        while (i < end) {
            if (buffer[i] == ESC && i + 1 < end && buffer[i + 1] == ST) return RexMatch.Mine(i + 2)
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

        val NOTHING = ByteArray(0)

        /** A program that prints the start of a Rex message and never ends it must not stall the screen. */
        const val MAX_HELD = 64 * 1024
    }
}

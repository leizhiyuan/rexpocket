package app.rexpocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Base64

/**
 * One terminal's bytes from Rex: a WebSocket on `/api/data` carrying a `GHOSTSNP` snapshot of the
 * pane and then its output as it comes. Typing still goes over the control connection; bytes only
 * flow this way.
 *
 * A connection that drops is resumed from the last byte handed over, so nothing is lost or shown
 * twice; when Rex cannot resume it, the stream starts over with a new snapshot. [ticket] asks the
 * control connection for the one-time ticket a new stream needs.
 */
class TerminalStream(
    private val endpoint: Endpoint,
    private val httpFactory: () -> OkHttpClient,
    private val ticket: suspend () -> String,
) {
    private val frames = Channel<Frame>(Channel.UNLIMITED)

    /** Stream bytes handed over so far: what a RESUME tells Rex it does not need again. */
    @Volatile private var received = 0L
    @Volatile private var resumeToken: ByteString? = null
    @Volatile private var active: WebSocket? = null
    @Volatile private var stopped = false

    /** The pane's bytes in order, for as long as this flow is collected. */
    fun feeds(): Flow<TerminalFeed> = flow {
        var attempt = 0
        while (!stopped) {
            val socket = try {
                open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt > MAX_ATTEMPTS) throw e
                delay(backoff(attempt))
                continue
            }
            attempt = 0
            val decoder = StreamDecoder(socket.nonce)
            try {
                for (frame in frames) {
                    when (frame) {
                        is Frame.Bytes -> {
                            received += frame.bytes.size
                            for (feed in decoder.feed(frame.bytes)) emit(feed)
                        }
                        is Frame.Closed -> throw frame.cause ?: DroppedStream("The terminal stream ended")
                    }
                }
                return@flow
            } catch (e: CancellationException) {
                // The screen stopped collecting: not a failure, and not worth reconnecting.
                throw e
            } catch (e: DroppedStream) {
                if (e.fatal || stopped) throw e
                if (++attempt > MAX_ATTEMPTS) throw e
                delay(backoff(attempt))
            } catch (e: Exception) {
                if (stopped) return@flow
                if (++attempt > MAX_ATTEMPTS) throw e
                delay(backoff(attempt))
            } finally {
                // A deliberate stop ends the stream; a drop keeps it waiting for a resume.
                if (stopped) socket.webSocket.close(CLOSE_NORMAL, "bye") else socket.webSocket.cancel()
                active = null
            }
        }
    }

    fun close() {
        stopped = true
        active?.close(CLOSE_NORMAL, "bye")
    }

    /** Opens one connection and waits for its WELCOME. */
    private suspend fun open(): Connected {
        val hello = handshake()
        val welcome = CompletableDeferred<Connected>()
        val request = Request.Builder()
            .url(endpoint.ws("/api/data"))
            .header("Sec-WebSocket-Protocol", PROTOCOL)
            .build()
        val ws = httpFactory().newWebSocket(request, listener(hello, welcome))
        active = ws
        try {
            return welcome.await()
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (e: Exception) {
            ws.cancel()
            // Only a refusal that names the token or the stream means it is gone; anything else,
            // and Rex still holds the stream for a resume.
            if (e is DroppedStream && e.restart) resumeToken = null
            throw e
        }
    }

    private fun listener(hello: ByteArray, welcome: CompletableDeferred<Connected>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(ByteString.of(*hello))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!welcome.isCompleted) {
                when (val answer = parseWelcome(bytes.toByteArray())) {
                    is Welcome.Ok -> {
                        resumeToken = answer.token
                        welcome.complete(Connected(webSocket, answer.nonce))
                    }
                    is Welcome.Rejected -> welcome.completeExceptionally(
                        DroppedStream(
                            "Rex refused the stream (${answer.reason})",
                            fatal = answer.reason == REJECT_CLOSED,
                            // A stream that already exists answers the same way; resuming replaces it.
                            restart = answer.reason != REJECT_IN_USE,
                        ),
                    )
                }
                return
            }
            frames.trySend(Frame.Bytes(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val dropped = DroppedStream(describe(code, reason), fatal = code == CLOSE_BLOCK_CLOSED)
            if (!welcome.isCompleted) welcome.completeExceptionally(dropped)
            frames.trySend(Frame.Closed(dropped))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!welcome.isCompleted) welcome.completeExceptionally(t)
            frames.trySend(Frame.Closed(t))
        }
    }

    /** HELLO with a fresh ticket, or RESUME where the last stream stopped. */
    private suspend fun handshake(): ByteArray {
        val token = resumeToken
        if (token != null && received > 0) {
            val out = ByteArray(RESUME_BYTES)
            out[0] = OP_RESUME.toByte()
            token.toByteArray().copyInto(out, 1)
            for (i in 0 until 8) out[1 + TOKEN_BYTES + i] = ((received ushr (8 * i)) and 0xff).toByte()
            return out
        }
        val fresh = Base64.getUrlDecoder().decode(ticket())
        val out = ByteArray(HELLO_BYTES)
        out[0] = OP_HELLO.toByte()
        fresh.copyInto(out, destinationOffset = 1)
        return out
    }

    private fun parseWelcome(bytes: ByteArray): Welcome = when (bytes.firstOrNull()) {
        OP_WELCOME.toByte() ->
            if (bytes.size < WELCOME_BYTES) {
                Welcome.Rejected(REJECT_MALFORMED)
            } else {
                // The nonce is the last eight bytes, little-endian and unsigned: the first of them
                // is the least significant, so Rex's own messages carry it in decimal.
                var nonce = 0uL
                for (i in 0 until 8) nonce = nonce or (bytes[WELCOME_BYTES - 8 + i].toUByte().toULong() shl (8 * i))
                Welcome.Ok(bytes.copyOfRange(1, 1 + TOKEN_BYTES).toByteString(), nonce)
            }
        OP_REJECT.toByte() -> Welcome.Rejected(bytes.getOrElse(1) { REJECT_UNKNOWN })
        else -> Welcome.Rejected(REJECT_UNKNOWN)
    }

    private fun describe(code: Int, reason: String): String = when {
        code == CLOSE_BLOCK_CLOSED -> "The pane closed"
        reason.isNotBlank() -> reason
        else -> "The terminal stream closed ($code)"
    }

    private class Connected(val webSocket: WebSocket, val nonce: ULong)

    private sealed interface Frame {
        class Bytes(val bytes: ByteArray) : Frame
        class Closed(val cause: Throwable?) : Frame
    }

    private sealed interface Welcome {
        class Ok(val token: ByteString, val nonce: ULong) : Welcome
        class Rejected(val reason: Byte) : Welcome
    }

    companion object {
        /** The subprotocol Rex uses for a terminal's output. */
        const val PROTOCOL = "rex.data.terminal.v1"

        private const val OP_HELLO = 0x01
        private const val OP_RESUME = 0x02
        private const val OP_WELCOME = 0x81
        private const val OP_REJECT = 0x82

        private const val TOKEN_BYTES = 16
        private const val HELLO_BYTES = 17
        private const val RESUME_BYTES = 25
        private const val WELCOME_BYTES = 32

        /** Rex closes with 4000 when the block is gone; no reconnect helps there. */
        private const val CLOSE_BLOCK_CLOSED = 4000
        private const val CLOSE_NORMAL = 1000

        private const val REJECT_UNKNOWN = 0x00.toByte()
        /** Another connection to the same pane exists, which a RESUME replaces. */
        private const val REJECT_IN_USE = 0x02.toByte()
        private const val REJECT_MALFORMED = 0x05.toByte()
        /** The stream is no longer available, such as a block that closed. */
        private const val REJECT_CLOSED = 0x03.toByte()

        private const val MAX_ATTEMPTS = 5

        private fun backoff(attempt: Int): Long = (300L * attempt).coerceAtMost(2_000L)
    }
}

/**
 * The stream ended. [fatal] when connecting again cannot help, such as a pane that closed;
 * [restart] when the resume token is spent and a new stream is the only way forward.
 */
class DroppedStream(message: String, val fatal: Boolean = false, val restart: Boolean = true) : Exception(message)

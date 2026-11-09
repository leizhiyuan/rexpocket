package me.leaver.rexpocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

/** How far one file has got on its way to a Rex pane. */
enum class UploadPhase {
    /** Reserving the file with Rex, before any byte moves. */
    Preparing,

    /** The bytes are going over their own data connection. */
    Sending,

    /** Every byte is sent and the stream is half-closed; Rex has not confirmed the insert yet. */
    Finishing,

    /** Rex confirmed it and put the file's path into the pane. */
    Done,

    /** The server is older than uploads: API revision 11. */
    Unsupported,

    /** Nothing was inserted. A new upload may be safe. */
    Failed,

    /**
     * The half-close was sent, so Rex may already have inserted the path even though the answer was
     * lost. Never retried automatically; the user checks the pane first.
     */
    Uncertain,

    /** Stopped before the half-close; Rex was told and keeps nothing. */
    Canceled,
}

data class UploadProgress(
    val phase: UploadPhase,
    val sent: Long = 0,
    val total: Long = 0,
    val error: String? = null,
)

/**
 * One file to one pane, using Rex's native upload: a dedicated control connection asks
 * `com.superlogical.terminal.upload_begin` for a ticket, then the bytes go over `/api/data` with
 * the `rex.data.bytes.v1` subprotocol. Rex stores the file and inserts its path itself, so nothing
 * is typed into the terminal and no second paste can duplicate the file.
 *
 * A reservation is never replayed: a failure after the half-close is [UploadPhase.Uncertain], and
 * the caller must ask the user to look at the pane rather than retry.
 */
class FileUpload(
    private val endpoint: Endpoint,
    private val httpFactory: () -> OkHttpClient,
    private val controlFactory: () -> RexClient = { RexClient(apiRevision = API_REVISION) },
    private val trace: (String) -> Unit = {},
) {
    /**
     * Sends [file] as [name] to [blockId] in [sessionId]. [onProgress] hears every phase, including
     * the final one; the returned value is that final state. Cancelling the coroutine stops the
     * upload: canceling before the half-close leaves nothing on the server.
     */
    suspend fun upload(
        sessionId: String,
        blockId: String,
        file: File,
        name: String,
        onProgress: (UploadProgress) -> Unit = {},
    ): UploadProgress {
        val size = file.length()
        if (size <= 0) return report(onProgress, UploadProgress(UploadPhase.Failed, 0, 0, "This file is empty"))
        if (size > MAX_BYTES) {
            return report(onProgress, UploadProgress(UploadPhase.Failed, 0, size, "Choose a file smaller than 100 MiB"))
        }
        var phase = UploadPhase.Preparing
        var control: RexClient? = null
        var data: WebSocket? = null

        fun progress(next: UploadPhase, sent: Long = 0, error: String? = null): UploadProgress {
            phase = next
            return report(onProgress, UploadProgress(next, sent, size, error))
        }

        progress(UploadPhase.Preparing)
        try {
            val client = controlFactory().also { control = it }
            val welcome = client.connect(endpoint)
            if (welcome.apiRevision < API_REVISION) return progress(UploadPhase.Unsupported, error = UPDATE_REX)
            client.call("session.attach", JSONObject().put("session_id", sessionId))
            val begun = try {
                client.call(
                    UPLOAD_BEGIN,
                    JSONObject()
                        .put("session_id", sessionId)
                        .put("block_id", blockId)
                        .put(
                            "args",
                            JSONObject()
                                .put("ref", "upload:${UUID.randomUUID()}")
                                .put("name", name)
                                .put("size", size),
                        ),
                )
            } catch (e: RexCallException) {
                return when (e.code) {
                    "method_not_found", "revision_too_low", "unsupported" ->
                        progress(UploadPhase.Unsupported, error = UPDATE_REX)
                    "too_large" ->
                        progress(UploadPhase.Failed, error = "This file exceeds the Rex server's upload limit")
                    "conflict" ->
                        progress(UploadPhase.Failed, error = "Rex cannot take another upload right now. Try again shortly")
                    "not_found", "session_not_found" ->
                        progress(UploadPhase.Failed, error = "The pane is no longer available")
                    else -> throw e
                }
            }
            val ticket = begun.optString("ticket").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Rex did not issue an upload ticket")
            val ticketBytes = runCatching { Base64.getUrlDecoder().decode(ticket) }
                .getOrElse { throw IllegalStateException("Rex issued an invalid upload ticket") }
            if (ticketBytes.size != TICKET_BYTES) throw IllegalStateException("Rex issued an invalid upload ticket")

            val stream = openData(ticketBytes).also { data = it.webSocket }
            progress(UploadPhase.Sending)
            val sent = stream(file, stream, size) { count ->
                onProgress(UploadProgress(UploadPhase.Sending, count, size))
            }
            if (sent != size) throw IllegalStateException("The file changed during the upload")

            // Past this point Rex may insert the path even if the answer never arrives.
            progress(UploadPhase.Finishing, sent)
            if (!stream.webSocket.send(ByteString.EMPTY)) throw IllegalStateException("The upload connection closed")
            val close = withTimeoutOrNull(CONFIRM_MS) { stream.closed.await() }
                ?: throw IllegalStateException("Rex did not confirm the upload in time")
            return if (close.code == CLOSE_NORMAL) {
                progress(UploadPhase.Done, sent)
            } else {
                // The bytes and the half-close went out; a refusal here may still have inserted.
                progress(
                    UploadPhase.Uncertain,
                    sent,
                    "Rex closed the upload (${close.code}${close.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}). $UNCERTAIN",
                )
            }
        } catch (e: CancellationException) {
            return if (phase == UploadPhase.Finishing) {
                UploadProgress(UploadPhase.Uncertain, size, size, UNCERTAIN)
            } else {
                UploadProgress(UploadPhase.Canceled)
            }
        } catch (e: Exception) {
            trace("upload $phase failed: ${e.javaClass.simpleName}: ${e.message}")
            return if (phase == UploadPhase.Finishing) {
                UploadProgress(UploadPhase.Uncertain, size, size, UNCERTAIN)
            } else {
                UploadProgress(UploadPhase.Failed, 0, size, e.message ?: "Upload failed")
            }
        } finally {
            // A normal close before the half-close is Rex's cancellation; no path is inserted.
            withContext(NonCancellable) {
                runCatching { data?.close(CLOSE_NORMAL, "canceled") }
                runCatching { control?.close() }
            }
        }
    }

    /** Opens the byte stream and waits for its WELCOME, which has the same shape as a terminal's. */
    private suspend fun openData(ticket: ByteArray): Stream {
        val welcome = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Close>()
        val request = Request.Builder()
            .url(endpoint.ws("/api/data"))
            .header("Sec-WebSocket-Protocol", PROTOCOL)
            .build()
        val socket = httpFactory().newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val hello = ByteArray(1 + TICKET_BYTES)
                    hello[0] = OP_HELLO
                    ticket.copyInto(hello, destinationOffset = 1)
                    webSocket.send(hello.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (welcome.isCompleted) return
                    val answer = bytes.toByteArray()
                    if (answer.size >= WELCOME_BYTES && answer[0] == OP_WELCOME) {
                        welcome.complete(Unit)
                    } else {
                        welcome.completeExceptionally(IllegalStateException("Rex refused the upload stream"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!welcome.isCompleted) welcome.completeExceptionally(IllegalStateException("Rex refused the upload stream"))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!welcome.isCompleted) welcome.completeExceptionally(IllegalStateException("The upload stream closed before it started"))
                    closed.complete(Close(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!welcome.isCompleted) welcome.completeExceptionally(t)
                    closed.completeExceptionally(t)
                }
            },
        )
        try {
            withTimeoutOrNull(HANDSHAKE_MS) { welcome.await() }
                ?: throw IllegalStateException("Rex did not answer the upload stream")
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
        return Stream(socket, closed)
    }

    /** Raw bytes in bounded chunks, waiting while too much sits in the socket's send queue. */
    private suspend fun stream(file: File, stream: Stream, size: Long, onSent: (Long) -> Unit): Long {
        var sent = 0L
        var reported = 0L
        val buffer = ByteArray(CHUNK)
        file.inputStream().use { input ->
            while (true) {
                if (stream.closed.isCompleted) throw IllegalStateException("The upload connection closed")
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                while (stream.webSocket.queueSize() > IN_FLIGHT) {
                    // A dead socket never drains its queue; OkHttp's pings finish the deferred.
                    if (stream.closed.isCompleted) throw IllegalStateException("The upload connection closed")
                    delay(5)
                }
                if (!stream.webSocket.send(buffer.copyOf(count).toByteString())) {
                    throw IllegalStateException("The upload connection closed")
                }
                sent += count
                if (sent - reported >= PROGRESS_STEP || sent == size) {
                    reported = sent
                    onSent(sent)
                }
            }
        }
        return sent
    }

    private class Stream(val webSocket: WebSocket, val closed: CompletableDeferred<Close>)

    private data class Close(val code: Int, val reason: String)

    private fun report(onProgress: (UploadProgress) -> Unit, progress: UploadProgress): UploadProgress {
        onProgress(progress)
        return progress
    }

    companion object {
        /** The subprotocol Rex uses for raw upload bytes. */
        const val PROTOCOL = "rex.data.bytes.v1"

        /** Uploads arrived in Rex's API revision 11; the live terminal connection stays on its own. */
        const val API_REVISION = 11

        const val UPLOAD_BEGIN = "com.superlogical.terminal.upload_begin"

        const val MAX_BYTES = 100L * 1024 * 1024

        private const val OP_HELLO = 0x01.toByte()
        private const val OP_WELCOME = 0x81.toByte()
        private const val TICKET_BYTES = 16
        private const val WELCOME_BYTES = 32
        private const val CLOSE_NORMAL = 1000

        private const val CHUNK = 16 * 1024
        /** How much may wait in OkHttp's queue before the reader is paused. */
        private const val IN_FLIGHT = 4L * 1024 * 1024
        private const val PROGRESS_STEP = 64L * 1024

        private const val HANDSHAKE_MS = 15_000L
        private const val CONFIRM_MS = 30_000L

        private const val UPDATE_REX = "Update the Rex server to enable uploads (API revision 11 or newer)."
        private const val UNCERTAIN =
            "The upload stopped while finishing. Rex may already have put the file in the pane; check it before sending again."
    }
}

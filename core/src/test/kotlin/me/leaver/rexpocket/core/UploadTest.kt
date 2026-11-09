package me.leaver.rexpocket.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The upload wire: a reserved ticket, raw bytes on their own data stream, then a normal close. */
class UploadTest {
    @Test
    fun aFileGoesOverItsOwnByteStreamAndRexConfirmsIt() = runBlocking {
        MockRex(revision = 11).use { rex ->
            val file = File.createTempFile("rexpocket-upload", ".txt").apply {
                writeText("hello from the phone\n".repeat(2_000))
            }
            try {
                val phases = mutableListOf<UploadPhase>()
                val result = FileUpload(
                    rex.endpoint,
                    { RexClient.defaultHttp() },
                    { RexClient(apiRevision = FileUpload.API_REVISION) },
                ).upload("session:test", "block:test", file, "notes.txt") { phases += it.phase }

                assertEquals(UploadPhase.Done, result.phase)
                assertEquals(file.length(), result.sent)
                assertEquals(file.length(), result.total)
                assertTrue("no sending phase: $phases", UploadPhase.Sending in phases)
                assertEquals(UploadPhase.Done, phases.last())

                val begin = rex.began.await()
                assertEquals("session:test", begin.getString("session_id"))
                assertEquals("block:test", begin.getString("block_id"))
                val args = begin.getJSONObject("args")
                assertEquals("notes.txt", args.getString("name"))
                assertEquals(file.length(), args.getLong("size"))
                assertTrue(args.getString("ref"), args.getString("ref").startsWith("upload:"))

                assertTrue("the half-close never arrived", rex.halfClosed.await(5, TimeUnit.SECONDS))
                assertEquals(null, rex.dataError.get())
                assertArrayEquals(file.readBytes(), rex.received.toByteArray())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun aServerBeforeRevisionElevenSaysToUpdateWithoutReservingAFile() = runBlocking {
        MockRex(revision = 8).use { rex ->
            val file = File.createTempFile("rexpocket-upload", ".txt").apply { writeText("old server") }
            try {
                val result = FileUpload(
                    rex.endpoint,
                    { RexClient.defaultHttp() },
                    { RexClient(apiRevision = FileUpload.API_REVISION) },
                ).upload("session:test", "block:test", file, "notes.txt")

                assertEquals(UploadPhase.Unsupported, result.phase)
                assertTrue(result.error.orEmpty().contains("API revision 11"))
                assertFalse("the old server was asked to reserve a file", rex.began.isCompleted)
                assertEquals(null, rex.dataError.get())
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun aServerThatRefusesTheFinishLeavesTheResultUncertain() = runBlocking {
        MockRex(revision = 11, closeCode = 4006).use { rex ->
            val file = File.createTempFile("rexpocket-upload", ".txt").apply { writeText("may have arrived") }
            try {
                val result = FileUpload(
                    rex.endpoint,
                    { RexClient.defaultHttp() },
                    { RexClient(apiRevision = FileUpload.API_REVISION) },
                ).upload("session:test", "block:test", file, "notes.txt")

                // The half-close went out, so Rex may have the file: never call this a safe retry.
                assertEquals(UploadPhase.Uncertain, result.phase)
                assertTrue(result.error.orEmpty().contains("check", ignoreCase = true))
                assertTrue("the half-close never arrived", rex.halfClosed.await(5, TimeUnit.SECONDS))
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun anEmptyFileFailsBeforeAnythingIsSent() = runBlocking {
        val file = File.createTempFile("rexpocket-upload", ".txt")
        try {
            val result = FileUpload(
                Endpoint("127.0.0.1", 1, plaintext = true),
                { RexClient.defaultHttp() },
                { RexClient(apiRevision = FileUpload.API_REVISION) },
            ).upload("session:test", "block:test", file, "empty.txt")
            assertEquals(UploadPhase.Failed, result.phase)
        } finally {
            file.delete()
        }
    }
}

/** A Rex that reserves uploads and takes their bytes, enough to prove the client's side of it. */
private class MockRex(private val revision: Int, private val closeCode: Int = 1000) : AutoCloseable {
    val server = MockWebServer()
    val began = CompletableDeferred<JSONObject>()
    val received = ByteArrayOutputStream()
    val halfClosed = CountDownLatch(1)

    /** A listener failure lands here instead of on OkHttp's thread, where it would only close a socket. */
    val dataError = AtomicReference<String?>(null)

    private val ticket = ByteArray(16) { (it + 1).toByte() }
    private val ticketText = Base64.getUrlEncoder().withoutPadding().encodeToString(ticket)

    val endpoint: Endpoint
        get() = Endpoint(server.hostName, server.port, plaintext = true)

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/control" -> MockResponse().withWebSocketUpgrade(control())
                "/api/data" -> MockResponse().withWebSocketUpgrade(data())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun control() = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            when (message.optString("type")) {
                "hello" -> webSocket.send(
                    JSONObject()
                        .put("type", "welcome")
                        .put("api_revision", revision)
                        .put("limits", JSONObject().put("max_message_bytes", 1_048_576))
                        .put("client", JSONObject().put("id", "client:test"))
                        .toString(),
                )
                "request" -> {
                    val id = message.getString("id")
                    when (message.getString("method")) {
                        "session.attach" -> webSocket.send(
                            response(id, JSONObject().put("client_id", "client:test").put("view", JSONObject())),
                        )
                        FileUpload.UPLOAD_BEGIN -> {
                            began.complete(message.getJSONObject("payload"))
                            webSocket.send(
                                response(id, JSONObject().put("ticket", ticketText).put("expires_in_ms", 60_000)),
                            )
                        }
                        else -> webSocket.send(
                            JSONObject().put("type", "response").put("id", id).put(
                                "error",
                                JSONObject().put("code", "method_not_found").put("status", 404).put("message", "no"),
                            ).toString(),
                        )
                    }
                }
            }
        }
    }

    private fun response(id: String, payload: JSONObject) = JSONObject()
        .put("type", "response")
        .put("id", id)
        .put("seq", 0)
        .put("payload", payload)
        .toString()

    private fun data() = object : WebSocketListener() {
        private var greeted = false

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!greeted) {
                val hello = bytes.toByteArray()
                if (hello.size != 17 || hello[0] != 1.toByte() || !hello.copyOfRange(1, 17).contentEquals(ticket)) {
                    dataError.set("bad hello: ${hello.toList()}")
                    webSocket.close(4006, "data stream broke a rule")
                    return
                }
                greeted = true
                val welcome = ByteArray(32)
                welcome[0] = 0x81.toByte()
                ByteArray(16) { (it + 0x20).toByte() }.copyInto(welcome, 1)
                welcome[24] = 7
                webSocket.send(welcome.toByteString())
                return
            }
            if (bytes.size == 0) {
                halfClosed.countDown()
                webSocket.close(closeCode, "rule")
                return
            }
            synchronized(received) { received.write(bytes.toByteArray()) }
        }
    }

    override fun close() {
        server.shutdown()
    }
}

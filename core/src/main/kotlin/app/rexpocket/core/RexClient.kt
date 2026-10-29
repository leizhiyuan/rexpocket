package app.rexpocket.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One Rex control connection: WebSocket `GET /api/control`, subprotocol `rex.control.v1`.
 * Every message is one JSON object. See the server's `/llms.txt`.
 */
class RexClient(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val opened = CompletableDeferred<Welcome>()
    private val closed = AtomicBoolean(false)
    private var socket: WebSocket? = null

    private val finished = CompletableDeferred<Throwable?>()
    private val _events = MutableSharedFlow<JSONObject>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    suspend fun awaitClosed(): Throwable? = finished.await()

    var welcome: Welcome? = null
        private set

    suspend fun connect(endpoint: Endpoint, reconnectToken: String? = null): Welcome {
        val request = Request.Builder()
            .url(endpoint.ws("/api/control"))
            .header("Sec-WebSocket-Protocol", "rex.control.v1")
            .build()
        socket = http.newWebSocket(request, listener(reconnectToken))
        return withTimeout(12_000) { opened.await() }
    }

    suspend fun call(method: String, payload: JSONObject? = null, timeoutMs: Long = 20_000): JSONObject {
        val ws = socket ?: throw IllegalStateException("Not connected to Rex")
        val id = ids.incrementAndGet().toString()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        val msg = JSONObject().put("type", "request").put("id", id).put("method", method)
        if (payload != null) msg.put("payload", payload)
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            throw IllegalStateException("Control connection closed")
        }
        try {
            val response = withTimeout(timeoutMs) { waiter.await() }
            if (response.has("error") && !response.isNull("error")) {
                val err = response.getJSONObject("error")
                throw RexCallException(
                    code = err.optString("code", "internal"),
                    status = err.optInt("status", 500),
                    message = err.optString("message", "request failed"),
                )
            }
            return response.optJSONObject("payload") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "bye")
        failPending(IllegalStateException("Connection closed"))
        if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("Connection closed"))
        finish(null)
    }

    private fun listener(reconnectToken: String?): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("api_revision", 7)
                .put("api_min_revision", 0)
                .put(
                    "client",
                    JSONObject()
                        .put("kind", "app")
                        .put("platform", "other")
                        .put("name", "RexPocket")
                        .put("version", "0.1.0"),
                )
            if (!reconnectToken.isNullOrBlank()) hello.put("reconnect_token", reconnectToken)
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            when (obj.optString("type")) {
                "welcome" -> {
                    val client = obj.optJSONObject("client")
                    val w = Welcome(
                        clientId = client?.optNullableString("id") ?: "",
                        reconnectToken = client?.optNullableString("reconnect_token"),
                        resumed = client?.optBoolean("resumed") ?: false,
                        apiRevision = obj.optInt("api_revision"),
                        maxMessageBytes = obj.optJSONObject("limits")?.optInt("max_message_bytes") ?: 65536,
                    )
                    welcome = w
                    if (!opened.isCompleted) opened.complete(w)
                }
                "response" -> {
                    val id = obj.optString("id")
                    pending.remove(id)?.complete(obj)
                }
                "event" -> {
                    val payload = obj.optJSONObject("payload") ?: return
                    _events.tryEmit(payload)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val err = IllegalStateException(reason.ifBlank { "Connection closed ($code)" })
            failPending(err)
            if (!opened.isCompleted) opened.completeExceptionally(err)
            finish(err)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val err = IllegalStateException(response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "Connection failed", t)
            failPending(err)
            if (!opened.isCompleted) opened.completeExceptionally(err)
            finish(err)
        }
    }

    private fun finish(err: Throwable?) {
        if (!finished.isCompleted) finished.complete(err)
    }

    private fun failPending(err: Throwable) {
        val left = pending.values.toList()
        pending.clear()
        left.forEach { if (!it.isCompleted) it.completeExceptionally(err) }
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}

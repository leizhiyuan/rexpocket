package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Does Rex tell one client which tab or pane another client is showing? Asked of a real Rex only
 * (rex-real), for the "a done tab seen on the Mac counts as seen on the phone" idea: two desk
 * clients on one session, the second moving Rex's focus to the other tab and taking its pane, and a
 * third client - the phone - reading everything Rex offers about clients. See rex-parity.md,
 * "What other clients show".
 */
class ClientViewProbeTest {
    @Test
    fun rexSaysWhoOwnsAPaneButNotWhatAClientShows() = runBlocking {
        val real = RealRex()
        assumeTrue("rex plaintext listener on ${real.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", real.reachable)
        val desk = Desk(real.endpoint)
        val other = RexClient()
        val phone = RexClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<JSONObject>()
        var sid: String? = null
        try {
            desk.connect()
            val (made, tabs) = desk.newSession(tabs = 2)
            sid = made
            other.connect(real.endpoint)
            phone.connect(real.endpoint)
            phone.call("session.attach", JSONObject().put("session_id", made))
            scope.launch(start = CoroutineStart.UNDISPATCHED) { phone.events.collect { events += it } }
            val (secondTab, secondPane) = tabs[1]
            other.call("session.attach", JSONObject().put("session_id", made))
            // What a desk does when its window shows the second tab: Rex's focus goes there, and it takes the pane.
            other.call("session.focus_window", JSONObject().put("session_id", made).put("window_id", secondTab))
            other.call("session.own_block", JSONObject().put("session_id", made).put("block_ids", JSONArray().put(secondPane)).put("mode", "take"))
            delay(500)
            val otherId = other.welcome!!.clientId
            val clients = phone.call("session.list_clients", JSONObject().put("session_id", made))
            val all = phone.call("client.list")
            val inspected = phone.call("client.inspect", JSONObject().put("client_id", otherId))
            val size = phone.call("com.superlogical.terminal.size", JSONObject().put("session_id", made).put("block_id", secondPane))
            val view = phone.call("session.view", JSONObject().put("session_id", made))
            println("probe session.list_clients: $clients")
            println("probe client.list: $all")
            println("probe client.inspect: $inspected")
            println("probe terminal.size: $size")
            println("probe session.view: $view")
            events.forEach { println("probe event: $it") }

            // Nothing about a client names a window or a block: what it shows is its own.
            for (entry in listOf(clients, all, inspected)) {
                val keys = keysOf(entry)
                assertTrue("a client field names a view: $keys", keys.none { it.contains("window") || it.contains("block") || it.contains("focus") })
            }
            // What a client says it is, which the desk inference reads (PocketSession.noteDeskLooks).
            val info = inspected.optJSONObject("client")?.optJSONObject("info")
            assertEquals("app", info?.optString("kind"))
            assertEquals("other", info?.optString("platform"))
            // What there is: the pane's owner, by client id, and the session's one active tab.
            assertEquals(otherId, size.optString("owner"))
            assertEquals(secondTab, view.optString("active_window_id").ifEmpty { view.optJSONObject("view")?.optString("active_window_id") })
            val ownerEvent = events.map { it.toString() }.firstOrNull { "block_owner_changed" in it && secondPane in it }
            assertTrue("no block_owner_changed for the taken pane: $events", ownerEvent != null && otherId in ownerEvent)
        } finally {
            sid?.let { runCatching { desk.destroy(it) } }
            scope.cancel()
            other.close()
            phone.close()
            desk.close()
        }
    }

    private fun keysOf(json: Any?, prefix: String = ""): List<String> = when (json) {
        is JSONObject -> json.keys().asSequence().flatMap { key -> listOf(prefix + key) + keysOf(json.opt(key), "$prefix$key.") }.toList()
        is JSONArray -> (0 until json.length()).flatMap { keysOf(json.opt(it), prefix) }
        else -> emptyList()
    }.filterNot { it.contains("principal") || it.contains("actions") }
}

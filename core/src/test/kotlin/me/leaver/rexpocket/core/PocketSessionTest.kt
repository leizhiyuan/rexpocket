package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket

class PocketSessionTest {
    @Test
    fun echoesThroughPlaintextServer() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            var blockId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                val ready = session.state.value
                assertEquals(Phase.Live, ready.phase)
                assertTrue("handshake client id missing", ready.clientId.orEmpty().startsWith("client:"))

                session.setSize(80, 24, 8, 16)
                val label = "rexpocket-test-" + System.currentTimeMillis()
                session.createShell(label)
                val created = session.state.value
                sessionId = created.activeSessionId
                val view = created.view
                assertNotNull("session.view missing after create", view)
                assertEquals(sessionId, view!!.sessionId)
                assertTrue("session.view has no windows", view.windows.isNotEmpty())
                blockId = view.focusedBlock()?.id
                assertTrue("focused block missing", blockId.orEmpty().startsWith("block:"))

                val owned = withTimeout(15_000) {
                    while (true) {
                        val owner = session.state.value.view?.focusedBlock()?.owner
                        if (owner == created.clientId) return@withTimeout owner
                        delay(50)
                    }
                    null
                }
                assertEquals(created.clientId, owned)

                val stream = StringBuilder()
                val feeder = collectPane(scope, session, blockId!!, stream)
                try {
                    val token = "rexpocket-ok-" + System.nanoTime()
                    session.sendText("printf '$token\\n'\n")
                    val seen = withTimeoutOrNull(15_000) {
                        while (!stripAnsi(stream.toString()).contains(token)) delay(100)
                        true
                    } ?: false
                    assertTrue("stream did not carry $token:\n${stream.toString()}", seen)
                    // The stream opens with the pane as Rex has it, which is what the emulator restores.
                    assertTrue("stream did not start with a snapshot", stream.startsWith(SNAPSHOT_MARK))
                } finally {
                    feeder.cancel()
                }

                // Stop joins the control coroutine so disown runs before the socket closes.
                session.stop()
                assertDisowned(endpoint, sessionId!!, blockId!!)
            } finally {
                session.stop()
                scope.cancel()
                if (sessionId != null) {
                    destroyQuietly(endpoint, sessionId!!)
                }
            }
        }
    }

    /**
     * Typing, the keys in the dock and a paste go out through different methods. They must still
     * reach the shell in the order they were typed.
     */
    @Test
    fun mixedInputArrivesInTheOrderItWasTyped() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(200, 24, 8, 16)
                session.createShell("rexpocket-test-order-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                val block = awaitOwnedFocus(session)
                val stream = StringBuilder()
                val feeder = collectPane(scope, session, block, stream)

                // Digits come from the terminal view, letters from the toolbar, interleaved.
                val expected = StringBuilder("echo ")
                session.sendText("echo ")
                for (i in 0 until 40) {
                    val letter = 'a' + (i % 26)
                    for (d in 0 until 4) {
                        expected.append((i + d) % 10)
                        session.writeBytes("${(i + d) % 10}".toByteArray())
                    }
                    expected.append(letter)
                    session.sendKey("key_$letter", text = "$letter")
                }
                session.sendKey("enter")
                val line = expected.toString().removePrefix("echo ")
                // The shell's echo of the command line and its output; the terminal may wrap either,
                // so line breaks are taken out before looking.
                // The typed characters must reach the shell in the order they were sent: the shell
                // echoes them, and the stream carries that echo.
                val seen = withTimeoutOrNull(15_000) {
                    while (!stripAnsi(stream.toString()).replace("\r", "").replace("\n", "").contains(line)) delay(100)
                    true
                } ?: false
                feeder.cancel()
                assertTrue("expected $line, stream:\n${stripAnsi(stream.toString())}", seen)
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Stopping the collector ends the stream; collecting again starts over from a new snapshot. */
    @Test
    fun aStreamThatStopsStartsAgainWithASnapshot() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                session.createShell("rexpocket-test-again-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                val block = awaitOwnedFocus(session)

                val first = StringBuilder()
                val feeder = collectPane(scope, session, block, first)
                withTimeout(10_000) { while (!first.startsWith(SNAPSHOT_MARK)) delay(50) }
                session.sendText("echo first-round\n")
                withTimeout(10_000) {
                    while (!stripAnsi(first.toString()).contains("first-round")) delay(50)
                }
                feeder.cancel()
                // Rex takes a moment to notice the stream is gone before the next one can open.
                delay(500)

                val second = StringBuilder()
                val again = collectPane(scope, session, block, second)
                try {
                    // A new collection starts its own stream, and every stream opens with a snapshot.
                    withTimeout(10_000) { while (!second.startsWith(SNAPSHOT_MARK)) delay(50) }
                    session.sendText("echo second-round\n")
                    withTimeout(10_000) {
                        while (!stripAnsi(second.toString()).contains("second-round")) delay(50)
                    }
                } finally {
                    again.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Rex announces a new session as it creates it; the list must still hold it once. */
    @Test
    fun aCreatedSessionIsListedOnce() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.createShell("rexpocket-test-once-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                // The created event can land just after the call returns.
                delay(500)
                val ids = session.state.value.sessions.map { it.id }
                assertEquals("listed twice: $ids", ids.distinct(), ids)
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * The desktop takes a pane when its window is focused. The phone must not take it back: the two
     * trading it every few seconds resized the program each time and left its prompt half drawn. The
     * pane stays with the desktop and is reported released, until the phone takes it over.
     */
    @Test
    fun aPaneTakenByTheDesktopStaysWithIt() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val desktop = RexClient()
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-owner-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId!!
                val blockId = awaitOwnedFocus(session)

                val watched = StringBuilder()
                val watcher = collectPane(scope, session, blockId, watched)
                try {
                    val mine = session.state.value.clientId
                    // Watching the pane takes it first; only then can the desktop take it away.
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockId) != mine) delay(100) }
                    // The desktop focuses the pane and takes it, as Rex does while someone is at the desk.
                    desktop.connect(endpoint)
                    desktop.call("session.attach", JSONObject().put("session_id", sessionId))
                    desktop.call(
                        "session.own_block",
                        JSONObject()
                            .put("session_id", sessionId)
                            .put("block_ids", JSONArray().put(blockId))
                            .put("mode", "take"),
                    )
                    val desk = desktop.welcome!!.clientId
                    withTimeout(10_000) { while (!session.state.value.released) delay(100) }
                    assertTrue("the pane is still reported as this phone's", !session.state.value.owning)
                    // Several of the phone's ownership checks later, the desktop still holds it.
                    delay(7_000)
                    // Ask Rex, not the session's cached view: the view can lag behind an owner change.
                    assertEquals("the phone took the pane back", desk, ownerOnServer(endpoint, sessionId!!, blockId))
                    // Taking over is the phone's own request, and it works.
                    session.takeOver()
                    assertEquals(mine, ownerOnServer(endpoint, sessionId!!, blockId))
                    assertTrue("the pane is not reported as this phone's", session.state.value.owning)
                } finally {
                    watcher.cancel()
                    desktop.close()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * A new control connection is a new client to Rex, which owns nothing yet. The pane on screen
     * must be taken again on it and kept at the phone's size - not reported as the desktop's, which
     * is what losing it to our own reconnect looked like, and not left at its old size because the
     * loop that sizes it ended with the old connection.
     */
    @Test
    fun aReconnectTakesThePaneAgainAndKeepsSizingIt() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-reconnect-" + System.currentTimeMillis())
                val sid = session.state.value.activeSessionId!!
                sessionId = sid
                val blockId = awaitOwnedFocus(session)
                val watcher = collectPane(scope, session, blockId, StringBuilder())
                try {
                    withTimeout(15_000) { while (columnsOnServer(endpoint, sid, blockId) != 52) delay(100) }
                    val before = session.state.value.clientId

                    session.reconnectNow()
                    withTimeout(10_000) {
                        while (session.state.value.phase != Phase.Live || session.state.value.clientId == null) delay(50)
                    }
                    // Several ownership checks on the new connection.
                    delay(7_000)
                    val mine = session.state.value.clientId
                    assertEquals("the new connection does not hold the pane (was $before)", mine, ownerOnServer(endpoint, sid, blockId))
                    assertTrue("the pane is reported released", !session.state.value.released)
                    assertTrue("the pane is not reported as this phone's", session.state.value.owning)

                    // The phone's size still reaches the pane: the keyboard coming up, say.
                    session.setSize(60, 21, 8, 16)
                    val sized = withTimeoutOrNull(5_000) {
                        while (columnsOnServer(endpoint, sid, blockId) != 60) delay(100)
                        true
                    } ?: false
                    assertTrue("the pane stayed at ${columnsOnServer(endpoint, sid, blockId)} columns", sized)
                } finally {
                    watcher.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * Releasing a pane hands it back and stops the take-back loop: the desktop keeps it, at its own
     * size, until the phone asks for it again. A release that the loop undid a second later would
     * mean nothing, so the pane is still the desktop's after several checks.
     */
    @Test
    fun aReleasedPaneStaysWithTheDesktopUntilTakenBack() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-release-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId!!
                val blockId = awaitOwnedFocus(session)

                val watched = StringBuilder()
                val watcher = collectPane(scope, session, blockId, watched)
                try {
                    val mine = session.state.value.clientId
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockId) != mine) delay(100) }

                    session.releasePane()
                    assertTrue("the pane was not reported released", session.state.value.released)
                    assertTrue("the pane is still reported as this phone's", !session.state.value.owning)
                    assertDisowned(endpoint, sessionId!!, blockId)
                    // The take-back loop runs every few seconds; one round is enough to expose a release
                    // it undoes.
                    delay(6_000)
                    assertDisowned(endpoint, sessionId!!, blockId)
                    assertTrue("released state did not survive", session.state.value.released)

                    session.takeOver()
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockId) != mine) delay(100) }
                    assertTrue("the phone did not report the pane as its own", session.state.value.owning)
                    assertTrue("released flag stayed after taking over", !session.state.value.released)
                } finally {
                    watcher.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * A release belongs to the pane, not to the moment it was made: moving to another pane and
     * coming back must not take it from the desktop, or the button would only hold until the next
     * tab switch. Another pane is still taken on sight.
     */
    @Test
    fun aReleasedPaneStaysReleasedWhenItIsShownAgain() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-sticky-release-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId!!
                val mine = session.state.value.clientId
                val blockA = awaitOwnedFocus(session)

                val firstWatch = collectPane(scope, session, blockA, StringBuilder())
                try {
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockA) != mine) delay(100) }
                } finally {
                    firstWatch.cancel()
                }
                session.releasePane()
                assertDisowned(endpoint, sessionId!!, blockA)

                // A second pane is taken as usual, so the release is per pane and not a global switch.
                session.split(vertical = false)
                val blockB = withTimeout(10_000) {
                    while (true) {
                        val focused = session.state.value.view?.focusedBlock()?.id
                        if (focused != null && focused != blockA) return@withTimeout focused
                        delay(50)
                    }
                    error("unreachable")
                }
                val secondWatch = collectPane(scope, session, blockB!!, StringBuilder())
                try {
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sessionId!!, blockB) != mine) delay(100) }
                    assertFalse("showing another pane should not be released", session.state.value.released)
                } finally {
                    secondWatch.cancel()
                }

                // Back to the released pane: it is still the desktop's, and the button says so.
                session.focusBlock(blockA)
                val thirdWatch = collectPane(scope, session, blockA, StringBuilder())
                try {
                    withTimeout(5_000) { session.state.first { it.released } }
                    // Longer than the take-back loop's first wait, which is what a forgotten
                    // release would be undone by.
                    delay(4_000)
                    assertDisowned(endpoint, sessionId!!, blockA)
                    assertTrue("the released flag did not survive the switch", session.state.value.released)
                } finally {
                    thirdWatch.cancel()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * A program's status and the shell's directory come with Rex's events. Over a slow tailnet every
     * call costs a round trip, so an idle pane must not keep asking for them.
     */
    @Test
    fun statusAndDirectoryFollowEventsWithoutPolling() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val calls = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val session = PocketSession(endpoint, trace = { line ->
                if (line.startsWith("rpc ")) calls += line.removePrefix("rpc ").substringBefore(' ')
            })
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                session.createShell("rexpocket-test-events-" + System.currentTimeMillis())
                sessionId = session.state.value.activeSessionId
                val block = awaitOwnedFocus(session)
                // The first read of each pane is allowed; after that the screen only listens.
                delay(1_500)
                calls.clear()
                delay(4_000)
                val polls = calls.filter { it == "com.superlogical.terminal.program_status" || it == "com.superlogical.terminal.pwd" }
                assertTrue("an idle pane was polled: $polls", polls.isEmpty())

                session.sendText("printf '\\033]7501;state=working\\007'; cd /tmp\n")
                withTimeout(5_000) { while (session.state.value.blockStatus[block]?.state != "working") delay(50) }
                withTimeout(5_000) { while (session.state.value.pwd?.trimEnd('/')?.endsWith("tmp") != true) delay(50) }

                // What runs in front and the title it sets come the same way, with no polling.
                // A program, not the shell, sets the title: only that one is shown.
                session.sendText("python3 -c \"import sys,time; sys.stdout.write(chr(27)+']0;agent-title'+chr(7)); sys.stdout.flush(); time.sleep(3)\"\n")
                withTimeout(5_000) { while (session.state.value.programs[block]?.startsWith("python") != true) delay(50) }
                withTimeout(5_000) { while (session.state.value.titles[block] != "agent-title") delay(50) }
                val asked = calls.filter { it == "com.superlogical.terminal.process" || it == "com.superlogical.terminal.title" }
                assertTrue("the program or title was polled: $asked", asked.isEmpty())
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * Rex has one active tab per session, shared by every client. The phone keeps its own place:
     * moving between tabs on the phone must not move the desktop, and the desktop moving must not
     * move the phone. Otherwise each follows the other onto the same pane and they trade it back
     * and forth, resizing it each time.
     */
    @Test
    fun thePhoneAndTheDesktopMoveBetweenTabsIndependently() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val desktop = RexClient()
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                session.createShell("rexpocket-test-tabs-" + System.currentTimeMillis())
                val sid = session.state.value.activeSessionId!!
                sessionId = sid
                val first = session.state.value.view!!.activeWindowId!!
                session.newWindow()
                val second = withTimeout(10_000) {
                    while (true) {
                        val shown = session.state.value.view?.activeWindowId
                        if (shown != null && shown != first) return@withTimeout shown
                        delay(50)
                    }
                    error("unreachable")
                }

                desktop.connect(endpoint)
                desktop.call("session.attach", JSONObject().put("session_id", sid))
                fun desktopTab() = runBlocking {
                    desktop.call("session.view", JSONObject().put("session_id", sid)).optNullableString("active_window_id")
                }
                // A new tab made on the phone is shown on the phone only.
                assertEquals(first, desktopTab())

                desktop.call("session.focus_window", JSONObject().put("session_id", sid).put("window_id", second))
                session.focusWindow(first)
                assertEquals("the phone's tab switch moved the desktop", second, desktopTab())
                assertEquals(first, session.state.value.view?.activeWindowId)

                // The desktop walks its tabs; the phone stays where it is once the views settle.
                desktop.call("session.focus_window", JSONObject().put("session_id", sid).put("window_id", first))
                desktop.call("session.focus_window", JSONObject().put("session_id", sid).put("window_id", second))
                delay(1_500)
                assertEquals("the desktop's tab switch moved the phone", first, session.state.value.view?.activeWindowId)
            } finally {
                desktop.close()
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /**
     * The phone on one tab and the desktop on another each keep their own pane: neither takes the
     * other's, so neither is resized under it. This is the case that used to trade a pane every few
     * seconds.
     */
    @Test
    fun thePhoneAndTheDesktopOnDifferentTabsKeepTheirOwnPanes() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val desktop = RexClient()
            var sessionId: String? = null
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(52, 21, 8, 16)
                session.createShell("rexpocket-test-two-tabs-" + System.currentTimeMillis())
                val sid = session.state.value.activeSessionId!!
                sessionId = sid
                val phonePane = session.state.value.view!!.focusedBlock()!!.id
                session.newWindow()
                session.focusBlock(phonePane)
                val deskPane = session.state.value.view!!.allBlocks().first { it.id != phonePane }.id

                val seen = StringBuilder()
                val watcher = collectPane(scope, session, phonePane, seen)
                try {
                    val mine = session.state.value.clientId
                    withTimeout(15_000) { while (ownerOnServer(endpoint, sid, phonePane) != mine) delay(100) }
                    // The desktop shows its own tab and takes that tab's pane, as Rex.app does.
                    desktop.connect(endpoint)
                    desktop.call("session.attach", JSONObject().put("session_id", sid))
                    desktop.call("session.focus_block", JSONObject().put("session_id", sid).put("block_id", deskPane))
                    desktop.call(
                        "session.own_block",
                        JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(deskPane)).put("mode", "take"),
                    )
                    val desk = desktop.welcome!!.clientId
                    // Several of the phone's ownership checks later, each still holds its own.
                    delay(7_000)
                    assertEquals("the phone lost its pane", mine, ownerOnServer(endpoint, sid, phonePane))
                    assertEquals("the phone took the desktop's pane", desk, ownerOnServer(endpoint, sid, deskPane))
                    assertEquals(phonePane, session.state.value.view?.focusedBlock()?.id)
                } finally {
                    watcher.cancel()
                    desktop.close()
                }
            } finally {
                session.stop()
                scope.cancel()
                sessionId?.let { destroyQuietly(endpoint, it) }
            }
        }
    }

    /** Moving to another session must hand the pane left behind back to the desktop. */
    @Test
    fun switchingSessionsReleasesThePreviousPane() {
        val endpoint = testEndpoint()
        assumeTrue(
            "rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)",
            portOpen(endpoint.host, endpoint.port),
        )
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session = PocketSession(endpoint)
            val created = ArrayList<String>()
            try {
                session.start(scope, preferredSessionId = null)
                withTimeout(15_000) { session.awaitReady() }
                session.setSize(80, 24, 8, 16)
                val stamp = System.currentTimeMillis()

                session.createShell("rexpocket-test-a-$stamp")
                val first = session.state.value.activeSessionId!!
                created += first
                // Taking a pane is part of watching it, so both panes are actually watched here.
                val firstBlock = awaitWatchedPane(scope, session)
                session.createShell("rexpocket-test-b-$stamp")
                created += session.state.value.activeSessionId!!
                awaitWatchedPane(scope, session)

                assertDisowned(endpoint, first, firstBlock)
            } finally {
                session.stop()
                scope.cancel()
                created.forEach { destroyQuietly(endpoint, it) }
            }
        }
    }
}

/** The owner of a pane according to Rex, read with a fresh connection. */
private suspend fun ownerOnServer(endpoint: Endpoint, sessionId: String, blockId: String): String? {
    val probe = RexClient()
    try {
        probe.connect(endpoint)
        probe.call("session.attach", JSONObject().put("session_id", sessionId))
        return probe.call(
            "com.superlogical.terminal.size",
            JSONObject().put("session_id", sessionId).put("block_id", blockId),
        ).optNullableString("owner")
    } finally {
        probe.close()
    }
}

/** The pane's width according to Rex, read with a fresh connection. */
private suspend fun columnsOnServer(endpoint: Endpoint, sessionId: String, blockId: String): Int {
    val probe = RexClient()
    try {
        probe.connect(endpoint)
        probe.call("session.attach", JSONObject().put("session_id", sessionId))
        return probe.call(
            "com.superlogical.terminal.size",
            JSONObject().put("session_id", sessionId).put("block_id", blockId),
        ).optInt("columns", -1)
    } finally {
        probe.close()
    }
}

/** Watches the shown pane until its snapshot arrives, and returns the block it is. */
private suspend fun awaitWatchedPane(scope: CoroutineScope, session: PocketSession): String {
    val block = awaitOwnedFocus(session)
    val seen = StringBuilder()
    val feeder = collectPane(scope, session, block, seen)
    try {
        withTimeout(15_000) {
            while (!synchronized(seen) { seen.startsWith(SNAPSHOT_MARK) }) delay(50)
        }
    } finally {
        feeder.cancel()
    }
    return block
}

/**
 * Waits until the shown pane belongs to this client, and returns its id. Creating a session already
 * makes the creator the owner, so this alone does not prove the phone took it.
 */
private suspend fun awaitOwnedFocus(session: PocketSession): String = withTimeout(15_000) {
    while (true) {
        val st = session.state.value
        val block = st.view?.takeIf { it.sessionId == st.activeSessionId }?.focusedBlock()
        if (block != null && block.owner == st.clientId) return@withTimeout block.id
        delay(50)
    }
    error("unreachable")
}

/** Marks where a snapshot began in a collected stream. */
private const val SNAPSHOT_MARK = "\u0000SNAPSHOT\u0000"

/**
 * Collects a pane the way the terminal screen does, into [into]: a mark per snapshot, then only the
 * output that followed the last one, which is what a restored emulator would show growing. The
 * collector must outlive the typing, so it is returned to cancel at the end.
 */
private fun collectPane(scope: CoroutineScope, session: PocketSession, blockId: String, into: StringBuilder) =
    scope.launch(Dispatchers.Default) {
        session.watchPane(blockId).collect { feed ->
            synchronized(into) {
                when (feed) {
                    is TerminalFeed.Snapshot -> {
                        into.append(SNAPSHOT_MARK)
                        // The snapshot replaces the screen, so earlier text is no longer shown.
                        into.setLength(into.length)
                    }
                    is TerminalFeed.Output -> into.append(feed.bytes.toString(Charsets.UTF_8))
                }
            }
        }
    }

/** Text with its escape sequences removed, close enough to what a screen would show. */
private fun stripAnsi(text: String): String = text
    .replace(Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]"), "")
    .replace(Regex("\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)"), "")

private fun testEndpoint(): Endpoint {
    val host = System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
    val port = System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297
    return Endpoint(host, port, plaintext = true)
}

private fun portOpen(host: String, port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
} catch (_: Exception) {
    false
}

private suspend fun assertDisowned(endpoint: Endpoint, sessionId: String, blockId: String) {
    val probe = RexClient()
    try {
        val welcome = probe.connect(endpoint)
        assertTrue(welcome.clientId.startsWith("client:"))
        val attached = probe.call("session.attach", JSONObject().put("session_id", sessionId))
        val view = parseView(attached.getJSONObject("view"))
        val owner = view.allBlocks().firstOrNull { it.id == blockId }?.owner
        assertNull("block still owned after disown: $owner", owner)
    } finally {
        probe.close()
    }
}

private fun destroyQuietly(endpoint: Endpoint, sessionId: String) {
    runBlocking {
        val probe = RexClient()
        try {
            probe.connect(endpoint)
            probe.call("session.attach", JSONObject().put("session_id", sessionId))
            probe.call("session.destroy", JSONObject().put("session_id", sessionId))
        } catch (_: Exception) {
        } finally {
            probe.close()
        }
    }
}

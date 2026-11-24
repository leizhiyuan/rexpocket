package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the notifications are made of and what their actions send: a program's bell and desktop
 * notifications (OSC 9, OSC 777) reach the phone from a pane it is not showing, and a reply or an
 * interrupt reaches the pane it names. On ScriptedRex every run, and on a real Rex where one listens.
 */
abstract class PaneSignalsTest {
    abstract fun rex(): RexUnderTest

    /** The program in [block] rings the bell. */
    abstract suspend fun bell(world: World)

    /** The program in [block] asks for a desktop notification; OSC 9 when [title] is null. */
    abstract suspend fun notify(world: World, title: String?, body: String)

    /** The program in [World.other] reports progress with OSC 9;4 [state] and [value]. */
    abstract suspend fun progress(world: World, state: Int, value: Int?)

    /** Waits until [block] received [reply] and Enter. */
    abstract suspend fun awaitReplied(world: World, reply: String)

    /** Starts a long program in [World.other], interrupts it from the phone, and waits until it stopped. */
    abstract suspend fun interruptStops(world: World)

    class World(val rex: RexUnderTest, val desk: Desk, val phone: PocketSession, val sid: String, val other: String, val signals: List<PaneSignal>)

    private fun withPhone(staleMs: Long = PROGRESS_STALE_MS, body: suspend World.() -> Unit) = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint, progressStaleMs = staleMs)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signals = CopyOnWriteArrayList<PaneSignal>()
        var sid: String? = null
        try {
            desk.connect()
            val (made, tabs) = desk.newSession(tabs = 2)
            sid = made
            scope.launch(start = CoroutineStart.UNDISPATCHED) { phone.signals.collect { signals += it } }
            phone.start(scope, preferredSessionId = made)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            val shown = withTimeout(10_000) {
                var id: String? = null
                while (id == null) {
                    id = phone.state.value.view?.focusedBlock()?.id
                    if (id == null) delay(20)
                }
                id
            }
            val other = tabs.map { it.second }.first { it != shown }
            // A real shell takes typed input once it is up.
            if (rex is RealRex) delay(1_500)
            World(rex, desk, phone, made, other, signals).body()
        } finally {
            sid?.let { desk.destroy(it) }
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    private suspend fun World.awaitSignal(want: PaneSignal): PaneSignal =
        withTimeoutOrNull(10_000) {
            while (want !in signals) delay(20)
            want
        } ?: throw AssertionError("expected $want, got $signals")

    @Test
    fun aBellInAPaneNotOnScreenReachesThePhone() = withPhone {
        bell(this)
        awaitSignal(PaneSignal.Bell(sid, other))
    }

    @Test
    fun desktopNotificationsCarryTheirTitleAndBody() = withPhone {
        notify(this, null, "build finished")
        awaitSignal(PaneSignal.Note(sid, other, "", "build finished"))
        notify(this, "Agent", "needs input")
        awaitSignal(PaneSignal.Note(sid, other, "Agent", "needs input"))
    }

    private suspend fun World.awaitProgress(want: PaneProgress?) {
        withTimeoutOrNull(10_000) { while (phone.state.value.progress[other] != want) delay(20) }
            ?: throw AssertionError("expected $want, got ${phone.state.value.progress}")
    }

    /**
     * OSC 9;4 in a pane not on screen: each of its states reaches the phone as that pane's bar, and
     * state 0 takes it down. None of it is an OSC 9 notification, though it shares OSC 9's number.
     */
    @Test
    fun progressBarsReachThePhoneInEveryStateAndGo() = withPhone {
        progress(this, 1, 42)
        awaitProgress(PaneProgress(ProgressState.Normal, 42))
        progress(this, 4, 50)
        awaitProgress(PaneProgress(ProgressState.Paused, 50))
        progress(this, 2, 10)
        awaitProgress(PaneProgress(ProgressState.Error, 10))
        progress(this, 3, null)
        awaitProgress(PaneProgress(ProgressState.Indeterminate))
        progress(this, 1, 100)
        awaitProgress(PaneProgress(ProgressState.Normal, 100))
        progress(this, 0, null)
        awaitProgress(null)
        delay(300)
        assertTrue("no notification: $signals", signals.none { it is PaneSignal.Note })
    }

    /** A bar no report keeps up goes by itself, as Ghostty's does: its program may have died. */
    @Test
    fun aProgressBarWithNoNewReportGoes() = withPhone(staleMs = 1_500) {
        progress(this, 1, 30)
        awaitProgress(PaneProgress(ProgressState.Normal, 30))
        awaitProgress(null)
    }

    @Test
    fun aReplyIsPastedIntoThePaneItNamesThenEntered() = withPhone {
        assertTrue(phone.replyTo(sid, other, "echo reply-$((6*7))-ok"))
        awaitReplied(this, "echo reply-$((6*7))-ok")
    }

    @Test
    fun anInterruptStopsTheProgram() = withPhone { interruptStops(this) }

    @Test
    fun aReplyToAPaneThatIsGoneSaysSo() = withPhone {
        desk.closePane(sid, other)
        delay(300)
        assertEquals(false, phone.replyTo(sid, other, "late"))
    }
}

class ScriptedPaneSignalsTest : PaneSignalsTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    private val World.fake get() = (rex as FakeRexUnderTest).rex

    override suspend fun bell(world: World) = world.fake.bell(world.other)

    override suspend fun notify(world: World, title: String?, body: String) = world.fake.notify(world.other, title.orEmpty(), body)

    override suspend fun progress(world: World, state: Int, value: Int?) = world.fake.progress(world.other, state, value)

    /** Should Rex ever pass OSC 9;4 on as an OSC 9 notification too, it is a bar, not a message. */
    @Test
    fun anOsc9ThatIsAProgressReportIsNoNotification() = runBlocking {
        val rex = FakeRexUnderTest()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signals = CopyOnWriteArrayList<PaneSignal>()
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 1)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { phone.signals.collect { signals += it } }
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            val block = tabs.first().second
            rex.rex.notify(block, "", "4;1;42")
            rex.rex.notify(block, "", "build 4;1 done")
            withTimeout(5_000) { while (signals.isEmpty()) delay(20) }
            delay(200)
            assertEquals(listOf(PaneSignal.Note(sid, block, "", "build 4;1 done")), signals.toList())
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    override suspend fun awaitReplied(world: World, reply: String) {
        val want = listOf("paste ${world.other} $reply", "key ${world.other} +enter")
        withTimeoutOrNull(5_000) { while (world.fake.inputs.toList() != want) delay(20) }
            ?: throw AssertionError("expected $want, got ${world.fake.inputs}")
    }

    override suspend fun interruptStops(world: World) {
        assertTrue(world.phone.interrupt(world.sid, world.other))
        assertEquals(listOf("key ${world.other} ctrl+key_c"), world.fake.inputs.toList())
    }
}

class RealRexPaneSignalsTest : PaneSignalsTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    override suspend fun bell(world: World) = world.desk.type(world.sid, world.other, "printf '\\a'\n")

    override suspend fun notify(world: World, title: String?, body: String) {
        val osc = if (title == null) "9;$body" else "777;notify;$title;$body"
        world.desk.type(world.sid, world.other, "printf '\\033]$osc\\a'\n")
    }

    override suspend fun progress(world: World, state: Int, value: Int?) {
        val osc = "9;4;$state" + (value?.let { ";$it" } ?: "")
        world.desk.type(world.sid, world.other, "printf '\\033]$osc\\a'\n")
    }

    /** The shell ran the reply: its output, not the command line, reads reply-42-ok. */
    override suspend fun awaitReplied(world: World, reply: String) = awaitText(world, "\nreply-42-ok")

    override suspend fun interruptStops(world: World) {
        world.desk.type(world.sid, world.other, "sleep 60; echo slept-$((1+1))\n")
        delay(1_000)
        assertTrue(world.phone.interrupt(world.sid, world.other))
        delay(500)
        world.desk.type(world.sid, world.other, "echo after-$((3+4))-ok\n")
        awaitText(world, "\nafter-7-ok")
        assertTrue("the sleep was not interrupted", !world.desk.text(world.sid, world.other).contains("slept-2"))
    }

    private suspend fun awaitText(world: World, text: String) {
        withTimeoutOrNull(10_000) { while (!world.desk.text(world.sid, world.other).contains(text)) delay(200) }
            ?: throw AssertionError("never saw $text in: ${world.desk.text(world.sid, world.other).takeLast(300)}")
    }
}

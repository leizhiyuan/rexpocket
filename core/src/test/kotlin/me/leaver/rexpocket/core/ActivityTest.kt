package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The activity view on a fake Rex whose programs report their status (OSC 7501, told to clients as
 * `program_status_changed` and `program_status_removed` block events): every tab with a program
 * that is blocked, failed, done or working, grouped in Rex's priority order, the newest news first.
 */
class ActivityTest {
    private val clock = AtomicLong(1_000_000)

    private fun withRex(body: suspend (ScriptedRex, Desk, PocketSession, List<Pair<String, List<Pair<String, String>>>>) -> Unit) = runBlocking {
        val rex = ScriptedRex(clock = { clock.get() })
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint, clock = { clock.get() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val sessions = List(2) { desk.newSession(tabs = 3) }
            phone.start(scope, preferredSessionId = sessions[0].first)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            body(rex, desk, phone, sessions)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    private suspend fun PocketSession.until(what: String, ok: (PocketState) -> Boolean): PocketState =
        withTimeoutOrNull(10_000) {
            while (!ok(state.value)) delay(20)
            state.value
        } ?: throw AssertionError("$what never happened: ${activitySections(state.value).map { s -> s.group to s.items.map { it.window.id } }}")

    private fun shape(st: PocketState) = activitySections(st).map { s -> s.group to s.items.map { it.window.id } }

    @Test
    fun failedAndDoneTabsAreGroupedAsRexGroupsThem() = withRex { rex, _, phone, sessions ->
        val (shown, shownTabs) = sessions[0]
        val (other, otherTabs) = sessions[1]
        // One failed in the session on screen, one done in another session, one idle: Rex sends
        // every session's block events, not only the attached one's.
        rex.report(shownTabs[0].second, "error", msg = "prompt_failed: 401", app = "pi")
        rex.report(otherTabs[1].second, "done", app = "pi")
        rex.report(shownTabs[2].second, "idle", app = "pi")
        val st = phone.until("failed and done in activity") { shape(it).size == 2 }
        assertEquals(
            listOf(ActivityGroup.Failed to listOf(shownTabs[0].first), ActivityGroup.Done to listOf(otherTabs[1].first)),
            shape(st),
        )
        val failed = activitySections(st)[0].items.single()
        assertEquals(shown, failed.sessionId)
        assertEquals("prompt_failed: 401", failed.record.msg)
        assertEquals("pi", failed.record.app)
        assertEquals(other, activitySections(st)[1].items.single().sessionId)
    }

    @Test
    fun everyGroupInPriorityOrderAndATabStandsWhereItsMostPressingPaneDoes() = withRex { rex, desk, phone, sessions ->
        val (sid, tabs) = sessions[0]
        rex.report(tabs[0].second, "working")
        rex.report(tabs[1].second, "done")
        rex.report(tabs[2].second, "error")
        // A split: one pane working, the other waiting on the person. The tab waits.
        val second = desk.split(sid, tabs[0].second)
        rex.report(second, "blocked", kind = "permission", msg = "Apply 3 to add?")
        val st = phone.until("the split tab waiting") { s -> shape(s).firstOrNull()?.first == ActivityGroup.Waiting && shape(s).size == 3 }
        assertEquals(
            listOf(
                ActivityGroup.Waiting to listOf(tabs[0].first),
                ActivityGroup.Failed to listOf(tabs[2].first),
                ActivityGroup.Done to listOf(tabs[1].first),
            ),
            shape(st),
        )
        assertEquals(second, activitySections(st)[0].items.single().block.id)
    }

    @Test
    fun aRecordRexDropsLeavesActivity() = withRex { rex, _, phone, sessions ->
        val tab = sessions[0].second[0]
        rex.report(tab.second, "working", app = "cargo")
        phone.until("running") { shape(it) == listOf(ActivityGroup.Running to listOf(tab.first)) }
        // The program exits while working: Rex drops the record and says so.
        rex.report(tab.second, null)
        phone.until("gone from activity") { activitySections(it).isEmpty() }
        assertNull(phone.state.value.blockStatus[tab.second])
    }

    @Test
    fun theNewestNewsComesFirstAndAStateFoundOnReadingHasRexsAge() = withRex { rex, _, phone, sessions ->
        val tabs = sessions[0].second
        rex.report(tabs[0].second, "done")
        phone.until("the first done") { activitySections(it).isNotEmpty() }
        clock.addAndGet(60_000)
        rex.report(tabs[1].second, "done")
        val st = phone.until("two done") { s -> activitySections(s).firstOrNull()?.items?.size == 2 }
        assertEquals(listOf(tabs[1].first, tabs[0].first), activitySections(st)[0].items.map { it.window.id })
        assertEquals(1_060_000L, activitySections(st)[0].items[0].since)

        // A record that was there before the phone looked is read with the time Rex stamped on it
        // (`updated_at`, sent by Rex 0.1.0-preview.22), so its age is known from the first read.
        val rex2 = ScriptedRex(clock = { clock.get() })
        try {
            val desk = Desk(rex2.endpoint)
            desk.connect()
            val (sid, fresh) = desk.newSession(tabs = 1)
            rex2.report(fresh[0].second, "error")
            val reportedAt = clock.get()
            clock.addAndGet(9 * 60_000L)
            val late = PocketSession(rex2.endpoint, clock = { clock.get() })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            late.start(scope, preferredSessionId = sid)
            late.setActive(true)
            val read = late.until("read") { activitySections(it).isNotEmpty() }
            assertEquals(reportedAt, activitySections(read)[0].items[0].since)
            assertEquals("9m", activityAge(activitySections(read)[0].items[0].since!!, clock.get()))
            late.stop()
            scope.cancel()
            desk.close()
        } finally {
            rex2.close()
        }
    }

    @Test
    fun aStatusReportedBeforeThePhoneLookedShowsForASessionItHasNotOpened() = runBlocking {
        // Rex lets a connection that bootstrapped call methods on any session (API reference,
        // session.bootstrap), so the first read finds a record in a session the phone never opened.
        val rex = ScriptedRex(clock = { clock.get() })
        val desk = Desk(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phone = PocketSession(rex.endpoint, clock = { clock.get() })
        try {
            desk.connect()
            val (shown, _) = desk.newSession(tabs = 1)
            val (_, otherTabs) = desk.newSession(tabs = 1)
            rex.report(otherTabs[0].second, "blocked", kind = "question", msg = "Which branch?")
            phone.start(scope, preferredSessionId = shown)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            val st = phone.until("the other session's question") { shape(it).isNotEmpty() }
            assertEquals(listOf(ActivityGroup.Waiting to listOf(otherTabs[0].first)), shape(st))
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    /**
     * Idle in the Activity view, as Rex's "Include idle processes in activity": off, an idle tab is
     * listed until it was seen; on, always. Never in Needs you or Running. By session, the same
     * tabs under their sessions, in Rex's order.
     */
    @Test
    fun idleTabsShowUntilSeenOrAlwaysAndBySessionKeepsRexsOrder() = withRex { rex, _, phone, sessions ->
        val (first, tabs) = sessions[0]
        val (second, otherTabs) = sessions[1]
        rex.report(tabs[2].second, "idle", app = "claude")
        rex.report(tabs[0].second, "working", app = "cargo")
        rex.report(otherTabs[1].second, "error", app = "pi")
        // The reports can come before the view of the session the phone did not open: a tab is
        // listed once its session's view is there, so the test waits for both.
        val st = phone.until("three reports and both views") {
            it.blockStatus.size == 3 && it.views[first]?.windows?.size == 3 && it.views[second]?.windows?.size == 3
        }
        val none: SeenCheck = { _, _ -> false }
        assertEquals(
            listOf(ActivityGroup.Failed to listOf(otherTabs[1].first), ActivityGroup.Running to listOf(tabs[0].first), ActivityGroup.Idle to listOf(tabs[2].first)),
            activitySections(st, none, idle = none).map { s -> s.group to s.items.map { it.window.id } },
        )
        // Seen: gone from Activity while Include idle is off, there with it on.
        val marks = mapOf(tabs[2].second to seenMark(st.records.getValue(tabs[2].second))!!)
        val seen: SeenCheck = { block, record -> markCovers(marks[block], record) }
        assertTrue(activitySections(st, seen, idle = seen).none { it.group == ActivityGroup.Idle })
        assertTrue(activitySections(st, seen, idle = none).any { it.group == ActivityGroup.Idle })
        // Idle is the Activity view's only.
        assertTrue(needsYouSections(st, none).none { it.group == ActivityGroup.Idle })
        assertEquals(listOf(tabs[0].first), runningItems(st, none).map { it.window.id })
        assertNull(tabAttention(st.views.getValue(first).windows[2], st))
        // By session: the shown session's tabs in their order, then the other's.
        assertEquals(
            listOf(first to listOf(tabs[0].first, tabs[2].first), second to listOf(otherTabs[1].first)),
            activityBySession(st, none, idle = none).map { s -> s.sessionId to s.items.map { it.window.id } },
        )
    }

    @Test
    fun ageIsWrittenAsRexWritesIt() {
        assertNull(activityAge(0, 59_000))
        assertEquals("9m", activityAge(0, 9 * 60_000L))
        assertEquals("3h", activityAge(0, 3 * 3_600_000L + 5))
        assertEquals("2d", activityAge(0, 2 * 86_400_000L))
    }
}

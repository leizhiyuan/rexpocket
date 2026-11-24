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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home's Running is every tab with a program at work, and seen is per report: a tab whose
 * agent works while one of its child records is done stays running once that done report is seen -
 * on the phone or at a desk - and is in Needs you as well while it is not (docs/design/home.md,
 * "Filters"). Rex.app on the Mac shows such a tab as Working; before this, the phone summed the
 * pane up to done and, once seen, dropped the tab from both lists.
 */
class RunningTest {
    private fun withRex(body: suspend (ScriptedRex, Desk, PocketSession, String, List<Pair<String, String>>) -> Unit) = runBlocking {
        val rex = ScriptedRex()
        val mac = Desk(rex.endpoint, platform = "macos")
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            mac.connect()
            val (sid, tabs) = mac.newSession(tabs = 3)
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            body(rex, mac, phone, sid, tabs)
        } finally {
            phone.stop()
            scope.cancel()
            mac.close()
            rex.close()
        }
    }

    private suspend fun PocketSession.until(what: String, ok: (PocketState) -> Boolean): PocketState =
        withTimeoutOrNull(10_000) {
            while (!ok(state.value)) delay(20)
            state.value
        } ?: throw AssertionError("$what never happened: records=${state.value.records} deskSeen=${state.value.deskSeen}")

    private fun window(st: PocketState, windowId: String): WindowInfo = st.views.values.flatMap { it.windows }.first { it.id == windowId }

    private fun running(st: PocketState, seen: SeenCheck = { _, _ -> false }) = runningItems(st, seen).map { it.window.id }
    private fun needsYou(st: PocketState, seen: SeenCheck = { _, _ -> false }) = needsYouSections(st, seen).flatMap { s -> s.items.map { it.window.id } }

    /** Seen as the phone keeps it: each pane's mark, checked per report. */
    private fun seenBy(marks: Map<String, String>): SeenCheck = { block, record -> markCovers(marks[block], record) }

    @Test
    fun anAgentWorkingWithAChildDoneIsRunningAndNeedsYouUntilSeen() = withRex { rex, _, phone, _, tabs ->
        val (tab, pane) = tabs[0]
        rex.report(pane, "working", app = "claude", msg = "Refactoring")
        rex.report(pane, "done", id = "task-1", title = "Explore", msg = "Found 3 files")
        val st = phone.until("both records") { it.records[pane]?.size == 2 }
        // The summary still reads done, as Rex's Activity order puts it...
        assertEquals("done", st.blockStatus.getValue(pane).state)
        // ...but the tab is running, and its unseen result needs the person too.
        val attention = tabAttention(window(st, tab), st)!!
        assertEquals(ActivityGroup.Done, attention.group)
        assertTrue(attention.isRunning)
        assertEquals("working", attention.running!!.record.state)
        assertEquals(listOf(tab), running(st))
        assertEquals(listOf(tab), needsYou(st))
        assertEquals(listOf(ActivityGroup.Running to 1), attention.alsoBesides(ActivityGroup.Done))
        assertEquals(listOf(ActivityGroup.Done to 1), attention.alsoBesides(ActivityGroup.Running))
        // The Running row shows the working report, its dot the tab's most pressing state.
        val row = runningItems(st).single()
        assertEquals("working", row.record.state)
        assertEquals(ActivityGroup.Done, row.attention.group)
    }

    @Test
    fun seeingTheChildsResultLeavesTheTabRunning() = withRex { rex, _, phone, _, tabs ->
        val (tab, pane) = tabs[0]
        rex.report(pane, "working", app = "claude")
        rex.report(pane, "done", id = "task-1", msg = "Found 3 files")
        val st = phone.until("both records") { it.records[pane]?.size == 2 }
        // The phone showed the pane: every report it had then is seen.
        val seen = seenBy(mapOf(pane to seenMark(st.records.getValue(pane))!!))
        val attention = tabAttention(window(st, tab), st, seen)
        assertEquals(ActivityGroup.Running, attention?.group)
        assertEquals(listOf(tab), running(st, seen))
        assertEquals(emptyList<String>(), needsYou(st, seen))
        // A second child finishing afterwards is news again; the first stays seen.
        rex.report(pane, "done", id = "task-2", msg = "Wrote the tests")
        val later = phone.until("the second child") { it.records[pane]?.size == 3 }
        val again = tabAttention(window(later, tab), later, seen)!!
        assertEquals(ActivityGroup.Done, again.group)
        assertEquals("task-2", again.record.id)
        assertEquals(1, again.counts[ActivityGroup.Done])
        assertEquals(listOf(tab), running(later, seen))
    }

    @Test
    fun aResultTheDeskSawLeavesTheTabRunning() = withRex { rex, mac, phone, sid, tabs ->
        val (tab, pane) = tabs[0]
        rex.report(pane, "working", app = "claude")
        rex.report(pane, "done", id = "task-1", msg = "Found 3 files")
        phone.until("both records") { it.records[pane]?.size == 2 }
        // Rex.app shows the tab and takes its pane: the desk saw the result.
        mac.focus(sid, tab)
        mac.take(sid, pane)
        val st = phone.until("seen on the desk") { s -> s.records[pane].orEmpty().any { it.state == "done" && markCovers(s.deskSeen[pane], it) } }
        val seen = seenBy(st.deskSeen)
        assertEquals(listOf(tab), running(st, seen))
        assertEquals(emptyList<String>(), needsYou(st, seen))
        // The desk saw the reports, not the working one: that is never news, and is not marked.
        assertFalse(st.records.getValue(pane).filter { it.state == "working" }.any { markCovers(st.deskSeen[pane], it) })
    }

    @Test
    fun aTabWorkingInOnePaneAndWaitingInAnotherIsInBoth() = withRex { rex, mac, phone, sid, tabs ->
        val (tab, pane) = tabs[0]
        val second = mac.split(sid, pane)
        rex.report(pane, "working", app = "cargo")
        rex.report(second, "blocked", kind = "permission", app = "claude", msg = "Run tests?")
        val st = phone.until("both panes") { it.blockStatus[pane] != null && it.blockStatus[second] != null }
        val attention = tabAttention(window(st, tab), st)!!
        assertEquals(ActivityGroup.Waiting, attention.group)
        assertEquals(second, attention.block.id)
        assertEquals(pane, attention.running!!.block.id)
        assertEquals(listOf(tab), running(st))
        assertEquals(listOf(tab), needsYou(st))
        assertEquals(pane, runningItems(st).single().block.id)
    }

    @Test
    fun aTabWithNothingAtWorkIsNotRunning() = withRex { rex, _, phone, _, tabs ->
        // No records at all; an idle agent; one that finished.
        rex.report(tabs[1].second, "idle", app = "claude")
        rex.report(tabs[2].second, "done", app = "cargo")
        val st = phone.until("the reports") { it.blockStatus[tabs[1].second] != null && it.blockStatus[tabs[2].second] != null }
        assertNull(tabAttention(window(st, tabs[0].first), st))
        assertNull(tabAttention(window(st, tabs[1].first), st))
        assertEquals(emptyList<String>(), running(st))
        assertEquals(listOf(tabs[2].first), needsYou(st))
        // The work stopping takes the tab out of Running.
        rex.report(tabs[0].second, "working", app = "pi")
        phone.until("running") { running(it) == listOf(tabs[0].first) }
        rex.report(tabs[0].second, null)
        phone.until("stopped") { running(it).isEmpty() }
    }

    @Test
    fun aRootRecordsMarkFromBeforeMarksWerePerRecordStillHolds() {
        val root = ProgramRecord(id = "", state = "done", kind = null, app = "pi", title = null, msg = "built", progress = null)
        val child = root.copy(id = "a/b")
        // The stamp marks had for a pane is the root record's now.
        assertEquals(listOf("done", "", "built").joinToString("\u0000"), seenStamp(root))
        assertTrue(markCovers(seenStamp(root), root))
        assertFalse(markCovers(seenStamp(root), child))
        val both = seenMark(listOf(root, child, root.copy(id = "w", state = "working")))!!
        assertTrue(markCovers(both, root) && markCovers(both, child))
        assertEquals(seenStamp(root), mergeMarks(both, null, listOf(root)))
        assertEquals("a", child.copy(id = "a/b").parent)
        assertEquals("", child.copy(id = "a").parent)
        assertNull(root.parent)
    }
}

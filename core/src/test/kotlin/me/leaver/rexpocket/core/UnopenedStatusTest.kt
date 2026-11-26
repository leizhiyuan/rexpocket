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
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A tab of a session the phone never opened has its program's state on the home: an agent there
 * that starts working is in Running, one that asks is in Needs you, and so is one that is done,
 * within the status sweep's bound (docs/design/home.md, "Status of tabs never opened"). On a
 * person's Mac the phone heard status events only from the sessions it had opened; the other
 * sessions' tabs showed no state at all.
 */
abstract class UnopenedStatusTest {
    abstract fun rex(): RexUnderTest

    /** The program in [block] reports [pairs], an OSC 7501 report. */
    abstract suspend fun report(desk: Desk, rex: RexUnderTest, sid: String, block: String, pairs: String)

    open suspend fun begin(desk: Desk, sid: String, block: String) {}

    @Test
    fun aTabNeverOpenedOnThePhoneShowsWhatItsProgramReports() = runBlocking {
        val under = rex()
        val desk = Desk(under.endpoint, platform = "macos")
        val phone = PocketSession(under.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (opened, _) = desk.newSession(tabs = 1)
            val (other, tabs) = desk.newSession(tabs = 2)
            val (tab, block) = tabs[1]
            begin(desk, other, block)
            phone.start(scope, preferredSessionId = opened)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            phone.openSession(opened)

            suspend fun until(what: String, ok: (PocketState) -> Boolean) {
                withTimeoutOrNull(SWEEP_BOUND_MS) { while (!ok(phone.state.value)) delay(50) }
                    ?: throw AssertionError("$what never happened: records=${phone.state.value.records}")
            }
            fun running(st: PocketState) = runningItems(st).map { it.window.id }
            fun needsYou(st: PocketState) = needsYouSections(st) { _, _ -> false }.flatMap { s -> s.items.map { it.window.id } }

            // Read once before it reports: the phone has the tab and its idle pane.
            until("the other session's tab known") { st -> st.views[other]?.windows?.any { it.id == tab } == true }
            delay(1_000)

            report(desk, under, other, block, "state=working:app=omp")
            until("the unopened tab in Running") { tab in running(it) }

            report(desk, under, other, block, "state=blocked:kind=permission:app=omp:msg=" + b64("Allow bash: npm test?"))
            until("the unopened tab in Needs you, blocked") { it.blockStatus[block]?.state == "blocked" && tab in needsYou(it) }

            report(desk, under, other, block, "state=done:app=omp")
            until("the unopened tab done, in Needs you and out of Running") {
                it.blockStatus[block]?.state == "done" && tab in needsYou(it) && tab !in running(it)
            }
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            under.close()
        }
    }

    /**
     * The program in [block] reports working, a prompt mark makes Rex drop that record, and the
     * program's title goes on spinning ([title]), as omp's did on the Mac that listed it Working.
     */
    abstract suspend fun workWithDroppedRecord(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String)

    /** The program in [block] is idle again: its title stops spinning. */
    abstract suspend fun settle(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String)

    /**
     * A tab whose agent's title spins is Working on the home even when Rex holds no record for it,
     * in a session the phone opened and in one it did not.
     */
    @Test
    fun aSpinningTitleIsWorkingWhenRexDroppedTheRecord() = runBlocking {
        val under = rex()
        val desk = Desk(under.endpoint, platform = "macos")
        val phone = PocketSession(under.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (opened, mine) = desk.newSession(tabs = 1)
            val (other, theirs) = desk.newSession(tabs = 1)
            phone.start(scope, preferredSessionId = opened)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            phone.openSession(opened)
            fun running(st: PocketState) = runningItems(st).map { it.window.id }
            suspend fun until(what: String, ok: (PocketState) -> Boolean) {
                withTimeoutOrNull(SWEEP_BOUND_MS) { while (!ok(phone.state.value)) delay(50) }
                    ?: throw AssertionError("$what never happened: records=${phone.state.value.records} titles=${phone.state.value.titles}")
            }
            until("both sessions known") { st -> other in st.views && opened in st.views }
            delay(1_000)
            for ((sid, tabs) in listOf(opened to mine, other to theirs)) {
                val (tab, block) = tabs[0]
                workWithDroppedRecord(desk, under, sid, block, "π ⠇ 🔑 ROTATE: Rotate preview")
                until("$sid's spinning tab in Working") { tab in running(it) && it.blockStatus[block]?.state == "working" }
                settle(desk, under, sid, block, "π 🔑 ROTATE: Rotate preview")
                until("$sid's settled tab out of Working") { tab !in running(it) }
            }
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            under.close()
        }
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private companion object {
        /** The sweep's 12 s on screen, and room for the round trips. */
        const val SWEEP_BOUND_MS = 20_000L
    }
}

/** On a ScriptedRex that, like the Mac's, sends block events only to the sessions a client attached. */
class ScriptedUnopenedStatusTest : UnopenedStatusTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest(ScriptedRex(blockEventsOnlyWhenAttached = true))

    override suspend fun report(desk: Desk, rex: RexUnderTest, sid: String, block: String, pairs: String) {
        val kv = pairs.split(':').associate { it.substringBefore('=') to it.substringAfter('=') }
        val msg = kv["msg"]?.let { String(java.util.Base64.getDecoder().decode(it)) }
        (rex as FakeRexUnderTest).rex.report(block, kv.getValue("state"), kind = kv["kind"], msg = msg, app = kv["app"])
    }

    override suspend fun workWithDroppedRecord(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String) {
        val scripted = (rex as FakeRexUnderTest).rex
        scripted.report(block, "working", app = "omp")
        scripted.dropRecords(block, listOf(""), reason = "prompt")
        scripted.setTitle(block, title)
    }

    override suspend fun settle(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String) =
        (rex as FakeRexUnderTest).rex.setTitle(block, title)
}

/** On a real Rex: the pane's shell writes each report as an agent's process does. */
class RealRexUnopenedStatusTest : UnopenedStatusTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    override suspend fun begin(desk: Desk, sid: String, block: String) {
        delay(1_500)
        desk.type(sid, block, "while IFS= read -r l; do printf '\\033]7501;%s\\033\\\\' \"\$l\"; done\n")
        delay(500)
    }

    override suspend fun report(desk: Desk, rex: RexUnderTest, sid: String, block: String, pairs: String) {
        desk.type(sid, block, "$pairs\n")
    }

    /**
     * A program reports working, writes a prompt mark (OSC 133;A), which makes Rex drop the record
     * (`program_status_removed`, reason `prompt`), then sets each line typed to it as its title.
     */
    override suspend fun workWithDroppedRecord(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String) {
        delay(1_500)
        val script = "import sys; w=sys.stdout.write; E=chr(27); " +
            "w(E+']7501;state=working:app=omp'+E+chr(92)); sys.stdout.flush(); " +
            "w(E+']133;A'+chr(7)); sys.stdout.flush(); " +
            "[(w(E+']0;'+l.strip()+chr(7)), sys.stdout.flush()) for l in sys.stdin]"
        desk.type(sid, block, "python3 -c \"$script\"\n")
        delay(1_000)
        desk.type(sid, block, "$title\n")
    }

    override suspend fun settle(desk: Desk, rex: RexUnderTest, sid: String, block: String, title: String) {
        desk.type(sid, block, "$title\n")
    }
}

/**
 * What the sweep costs: a pane whose program says it works or asks needs no title read, as a
 * spinning title only stands in for a record Rex no longer holds (withTitleWork).
 */
class SweepCostTest {
    @Test
    fun aPaneWithAWorkingRecordIsNotAskedItsTitle() = runBlocking {
        val rex = ScriptedRex(blockEventsOnlyWhenAttached = true)
        val desk = Desk(rex.endpoint, platform = "macos")
        val phone = PocketSession(rex.endpoint, statusSweepMs = 400)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (opened, _) = desk.newSession(tabs = 1)
            val (_, tabs) = desk.newSession(tabs = 1)
            val (_, block) = tabs[0]
            rex.report(block, "working", app = "omp")
            phone.start(scope, preferredSessionId = opened)
            withTimeout(15_000) { phone.awaitReady() }
            phone.openSession(opened)
            fun count(method: String) = rex.calls.count { it.startsWith("com.superlogical.terminal.$method") && it.endsWith(block) }
            withTimeout(10_000) { while (count("program_status") < 4) delay(50) }
            assertEquals("working in the unopened tab", "working", phone.state.value.blockStatus[block]?.state)
            // The first read takes the title with everything else; the sweeps after it do not.
            assertEquals("title reads", 1, count("title"))
            // A pane with no record is asked its title again: a spinner may be all it says.
            rex.report(block, null)
            val before = count("title")
            withTimeout(10_000) { while (count("title") < before + 2) delay(50) }
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

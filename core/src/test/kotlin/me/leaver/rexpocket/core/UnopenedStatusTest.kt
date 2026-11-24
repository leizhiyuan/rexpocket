package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
}

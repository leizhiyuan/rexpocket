package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * An agent spins a braille frame in its title several times a second while it works. Each frame
 * is the same news - still working - so the phone does not publish a new state for it, and a
 * title that does change leaves every status map that did not change the same instance, so what
 * shows them (each row on the home) is not composed again for nothing.
 */
class StatusPublishTest {
    @Test
    fun spinnerFramesPublishNothingAndATitleChangeKeepsTheStatusMaps() = runBlocking {
        val rex = ScriptedRex()
        val desk = Desk(rex.endpoint, platform = "macos")
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 1)
            val block = tabs[0].second
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            phone.openSession(sid)
            suspend fun until(what: String, ok: (PocketState) -> Boolean) {
                withTimeoutOrNull(15_000) { while (!ok(phone.state.value)) delay(20) }
                    ?: throw AssertionError("$what never happened: ${phone.state.value.titles} ${phone.state.value.blockStatus}")
            }
            until("the session open") { st -> st.view?.allBlocks().orEmpty().any { it.id == block } }
            // Working by its title alone: Rex dropped the record at a prompt mark.
            rex.report(block, "working", app = "omp")
            rex.dropRecords(block, listOf(""), reason = "prompt")
            rex.setTitle(block, "π ⠋ ROTATE: Rotate preview")
            until("working by the title") { it.titles[block] == "π ⠋ ROTATE: Rotate preview" && it.blockStatus[block]?.state == "working" }
            delay(1_000)
            val first = phone.state.value

            val seen = Collections.synchronizedList(mutableListOf<String?>())
            val watch = scope.launch { phone.state.collect { seen += it.titles[block] } }
            for (frame in "⠙⠹⠸⠼⠴⠦⠧⠇⠏") {
                rex.setTitle(block, "π $frame ROTATE: Rotate preview")
                delay(60)
            }
            rex.setTitle(block, "π ⠋ ROTATE: Rotate the preview")
            until("the new title") { it.titles[block] == "π ⠋ ROTATE: Rotate the preview" }
            watch.cancel()
            // A frame goes out only with a state published for something else (a status read
            // landing, the sweep): before, each of the nine did.
            val frames = seen.distinct().filter { it != first.titles[block] && it != "π ⠋ ROTATE: Rotate the preview" }
            assertTrue("states for frames: $frames", frames.size <= 2)

            val after = phone.state.value
            assertEquals("working", after.blockStatus[block]?.state)
            assertSame(first.blockStatus, after.blockStatus)
            assertSame(first.records, after.records)
            assertSame(first.busy, after.busy)
            assertSame(first.programs, after.programs)
            assertSame(first.statusSince, after.statusSince)

            // The spinner stopping is news: out of Working.
            rex.setTitle(block, "π ROTATE: Rotate the preview")
            until("idle again") { it.blockStatus[block]?.state != "working" }
            assertTrue(block !in phone.state.value.busy)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

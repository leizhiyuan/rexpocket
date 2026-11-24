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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The pane's own methods the palette runs: Copy and Share output (`format` as text, trimmed and
 * unwrapped, the rest read with `blob_read`), Clear and Reset Terminal. On ScriptedRex every run,
 * and on a real Rex where one listens.
 */
abstract class PaneTextTest {
    abstract fun rex(): RexUnderTest

    /** The program in [block] prints the numbers 1 to [n], one a line, and then [END]. */
    abstract suspend fun count(rex: RexUnderTest, phone: PocketSession, block: String, n: Int)

    private fun withPane(body: suspend (RexUnderTest, PocketSession, String) -> Unit) = runBlocking {
        val rex = rex()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val phone = PocketSession(rex.endpoint)
        var sid: String? = null
        try {
            phone.start(scope)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setSize(80, 24, 8, 16)
            phone.createShell("rexpocket-panetext-" + System.currentTimeMillis())
            sid = phone.state.value.activeSessionId
            val block = phone.state.value.view!!.focusedBlock()!!.id
            body(rex, phone, block)
        } finally {
            if (sid != null) runCatching { phone.destroySession() }
            phone.stop()
            scope.cancel()
            rex.close()
        }
    }

    private suspend fun PocketSession.textUntil(what: String, ok: (String) -> Boolean): String =
        withTimeoutOrNull(15_000) {
            while (true) {
                val text = paneText()?.text.orEmpty()
                if (ok(text)) return@withTimeoutOrNull text
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        } ?: throw AssertionError("$what never happened: ${paneText()?.text?.takeLast(300)}")

    @Test
    fun aLongOutputComesWholeInPartsAndItsNewestPartWhenCut() = withPane { rex, phone, block ->
        count(rex, phone, block, 12_000)
        val whole = phone.textUntil("the count ending") { it.contains("\n$END") }
        assertTrue("more than one part of Rex's 48 KiB", whole.toByteArray().size > 48 * 1024)
        assertTrue(whole.contains("\n11999\n12000\n"))
        assertFalse(phone.paneText()!!.cut)

        val cut = phone.paneText(maxBytes = 4_000)!!
        assertTrue(cut.cut)
        assertTrue(cut.text.toByteArray().size <= 4_000)
        assertTrue("the newest output is kept", cut.text.contains("\n12000\n$END"))
        val first = cut.text.lineSequence().first()
        assertTrue("it starts at the start of a line: $first", first.matches(Regex("\\d+")) && first.toInt() > 11_000)
    }

    @Test
    fun clearAndResetEmptyThePane() = withPane { rex, phone, block ->
        count(rex, phone, block, 50)
        phone.textUntil("the count ending") { it.contains("\n$END") }
        phone.clearPane()
        phone.textUntil("the pane cleared") { !it.contains(END) }
        count(rex, phone, block, 50)
        phone.textUntil("the count ending again") { it.contains("\n$END") }
        phone.resetPane()
        val reset = phone.textUntil("the pane reset") { !it.contains(END) }
        assertEquals("", reset.lines().filter { it.matches(Regex("\\d+")) }.joinToString())
    }

    companion object {
        const val END = "end-42-mark"
    }
}

class ScriptedPaneTextTest : PaneTextTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    override suspend fun count(rex: RexUnderTest, phone: PocketSession, block: String, n: Int) {
        (rex as FakeRexUnderTest).rex.print(block, (1..n).joinToString("\n", prefix = "\n", postfix = "\n$END\n"))
    }
}

class RealRexPaneTextTest : PaneTextTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    // The command line says $((40+2)), so only the output reads end-42-mark.
    override suspend fun count(rex: RexUnderTest, phone: PocketSession, block: String, n: Int) {
        phone.sendText("seq 1 $n; echo end-\$((40+2))-mark\n")
    }
}

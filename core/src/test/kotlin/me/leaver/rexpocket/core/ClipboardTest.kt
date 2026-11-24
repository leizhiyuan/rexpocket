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
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A program copying with OSC 52 (`clipboard_written`) reaches the phone's clipboard only from the
 * pane on screen, and only while the app is in the foreground; a copy too large for the event is
 * read with `clipboard_read`.
 */
class ClipboardTest {
    private class Phone(val rex: ScriptedRex, val phone: PocketSession, val shown: String, val other: String, val copies: List<PaneCopy>)

    private fun withPhone(body: suspend Phone.() -> Unit) = runBlocking {
        val rex = ScriptedRex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val copies = CopyOnWriteArrayList<PaneCopy>()
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 2)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { phone.copies.collect { copies += it } }
            phone.start(scope, preferredSessionId = sid)
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
            Phone(rex, phone, shown, other, copies).body()
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    private suspend fun Phone.awaitCopies(n: Int): List<PaneCopy> =
        withTimeoutOrNull(5_000) {
            while (copies.size < n) delay(20)
            copies.toList()
        } ?: throw AssertionError("expected $n copies, got $copies")

    @Test
    fun aCopyInThePaneOnScreenReachesTheClipboard() = withPhone {
        rex.copy(shown, "hi")
        assertEquals(listOf(PaneCopy(shown, "hi")), awaitCopies(1))
    }

    @Test
    fun aCopyInAPaneNotOnScreenDoesNot() = withPhone {
        rex.copy(other, "from behind")
        rex.copy(shown, "on screen")
        assertEquals(listOf(PaneCopy(shown, "on screen")), awaitCopies(1))
        delay(200)
        assertEquals(1, copies.size)
    }

    @Test
    fun aCopyWhileTheAppIsInTheBackgroundDoesNot() = withPhone {
        phone.setActive(false)
        rex.copy(shown, "in a pocket")
        delay(300)
        phone.setActive(true)
        rex.copy(shown, "looking")
        assertEquals(listOf(PaneCopy(shown, "looking")), awaitCopies(1))
    }

    @Test
    fun aCopyTooLargeForTheEventIsReadFromRex() = withPhone {
        val big = "x".repeat(70_000) + "é"
        rex.copy(shown, big, inline = false)
        assertEquals(listOf(PaneCopy(shown, big)), awaitCopies(1))
    }
}

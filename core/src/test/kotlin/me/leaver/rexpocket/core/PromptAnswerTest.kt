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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Approve and Deny from a notification or the home's Needs you: the agent's keys reach the pane
 * only while its record is still the prompt the person saw, read again from Rex first. On
 * ScriptedRex every run, and on a real Rex where one listens (a shell reading the key stands in for
 * the agent).
 */
abstract class PromptAnswerTest {
    abstract fun rex(): RexUnderTest

    /** The program in [World.other] reports an approval prompt from [app], then waits for one key. */
    abstract suspend fun ask(world: World, app: String, msg: String)

    /** Waits until the program in [World.other] got [code]'s key (`enter`, `escape`, `key_y`). */
    abstract suspend fun awaitKey(world: World, code: String)

    /** Nothing reached [World.other] since it asked. */
    abstract suspend fun assertNothingTyped(world: World)

    class World(val rex: RexUnderTest, val desk: Desk, val phone: PocketSession, val sid: String, val other: String)

    private fun withPhone(body: suspend World.() -> Unit) = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var sid: String? = null
        try {
            desk.connect()
            val (made, tabs) = desk.newSession(tabs = 2)
            sid = made
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
            if (rex is RealRex) delay(1_500)
            World(rex, desk, phone, made, other).body()
        } finally {
            sid?.let { desk.destroy(it) }
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    /** The prompt as the phone heard it, once it has [msg]. */
    protected suspend fun World.seen(msg: String): ProgramRecord =
        withTimeoutOrNull(10_000) {
            var record: ProgramRecord? = null
            while (record == null) {
                record = phone.state.value.blockStatus[other]?.takeIf { it.state == "blocked" && it.msg == msg }
                if (record == null) delay(20)
            }
            record
        } ?: throw AssertionError("the phone never saw $msg: ${phone.state.value.blockStatus[other]}")

    @Test
    fun approveSendsTheAgentsKeyWhileThePromptIsTheOneSeen() = withPhone {
        ask(this, "codex", "Run the tests?")
        val record = seen("Run the tests?")
        assertEquals(ApprovalKeys.CODEX, approvalKeys(record))
        assertEquals(PromptAnswer.Sent, phone.answerPrompt(sid, other, promptStamp(record), approve = true))
        awaitKey(this, "key_y")
    }

    @Test
    fun denySendsEscape() = withPhone {
        ask(this, "claude", "Edit main.rs?")
        val record = seen("Edit main.rs?")
        assertEquals(PromptAnswer.Sent, phone.answerPrompt(sid, other, promptStamp(record), approve = false))
        awaitKey(this, "escape")
    }

    @Test
    fun approveForClaudeIsReturn() = withPhone {
        ask(this, "claude", "Allow Bash?")
        val record = seen("Allow Bash?")
        assertEquals(PromptAnswer.Sent, phone.answerPrompt(sid, other, promptStamp(record), approve = true))
        awaitKey(this, "enter")
    }

    @Test
    fun anAnswerToAnOlderPromptTypesNothing() = withPhone {
        ask(this, "claude", "Allow Bash?")
        val record = seen("Allow Bash?")
        // What an alert said about the prompt before this one: another message, or the same one asked earlier.
        assertEquals(PromptAnswer.Changed, phone.answerPrompt(sid, other, promptStamp(record.copy(msg = "Allow Edit?")), approve = true))
        assertEquals(PromptAnswer.Changed, phone.answerPrompt(sid, other, promptStamp(record.copy(updatedAt = (record.updatedAt ?: 0) - 5_000)), approve = true))
        delay(300)
        assertNothingTyped(this)
    }

    @Test
    fun aPaneThatIsGoneIsNotAnswered() = withPhone {
        ask(this, "claude", "Allow Bash?")
        val record = seen("Allow Bash?")
        desk.closePane(sid, other)
        delay(300)
        assertEquals(PromptAnswer.NotSent, phone.answerPrompt(sid, other, promptStamp(record), approve = true))
    }
}

class ScriptedPromptAnswerTest : PromptAnswerTest() {
    override fun rex(): RexUnderTest = FakeRexUnderTest()

    private val World.fake get() = (rex as FakeRexUnderTest).rex

    override suspend fun ask(world: World, app: String, msg: String) =
        world.fake.report(world.other, "blocked", kind = "permission", app = app, msg = msg)

    override suspend fun awaitKey(world: World, code: String) {
        val want = listOf("key ${world.other} +$code")
        withTimeoutOrNull(5_000) { while (world.fake.inputs.toList() != want) delay(20) }
            ?: throw AssertionError("expected $want, got ${world.fake.inputs}")
    }

    override suspend fun assertNothingTyped(world: World) = assertEquals(emptyList<String>(), world.fake.inputs.toList())

    /** The phone missed the event of the new prompt (a reconnect, say): the read from Rex still catches it. */
    @Test
    fun aPromptThatChangedWithoutThePhoneHearingIsNotAnswered() = runBlocking {
        val rex = ScriptedRex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, tabs) = desk.newSession(tabs = 1)
            val block = tabs.single().second
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.setActive(true)
            rex.report(block, "blocked", kind = "permission", app = "claude", msg = "Allow Bash?")
            val record = withTimeout(10_000) {
                var r: ProgramRecord? = null
                while (r == null) {
                    r = phone.state.value.blockStatus[block]
                    if (r == null) delay(20)
                }
                r
            }
            delay(5)
            rex.report(block, "blocked", kind = "permission", app = "claude", msg = "Allow rm -rf build?", quiet = true)
            assertEquals("Allow Bash?", phone.state.value.blockStatus[block]?.msg)
            assertEquals(PromptAnswer.Changed, phone.answerPrompt(sid, block, promptStamp(record), approve = true))
            assertTrue(rex.inputs.isEmpty())
            // A program that stopped asking is not answered either.
            rex.report(block, "working", app = "claude", quiet = true)
            assertEquals(PromptAnswer.Changed, phone.answerPrompt(sid, block, promptStamp(record), approve = false))
            assertTrue(rex.inputs.isEmpty())
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }
}

class RealRexPromptAnswerTest : PromptAnswerTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        return rex
    }

    /**
     * The shell reports the prompt as an agent would (OSC 7501, `msg` in base64), then reads one
     * key and prints its code, so what Approve or Deny typed shows on the screen.
     */
    override suspend fun ask(world: World, app: String, msg: String) {
        val b64 = java.util.Base64.getEncoder().encodeToString(msg.toByteArray())
        world.desk.type(
            world.sid,
            world.other,
            "printf '\\033]7501;state=blocked:kind=permission:app=$app:msg=$b64\\033\\\\'; " +
                "IFS= read -rsn1 k; printf 'got-%d-key\\n' \"'\$k\"\n",
        )
    }

    override suspend fun awaitKey(world: World, code: String) {
        // `read -n1` keeps the key: Return reads as nothing (0), Esc as 27, y as 121.
        val want = when (code) {
            "enter" -> 0
            "escape" -> 27
            "key_y" -> 'y'.code
            else -> error(code)
        }
        awaitText(world, "got-$want-key")
    }

    override suspend fun assertNothingTyped(world: World) {
        assertTrue("a key reached the program", !Regex("got-[0-9]+-key").containsMatchIn(world.desk.text(world.sid, world.other)))
    }

    private suspend fun awaitText(world: World, text: String) {
        withTimeoutOrNull(10_000) { while (!world.desk.text(world.sid, world.other).contains(text)) delay(200) }
            ?: throw AssertionError("never saw $text in: ${world.desk.text(world.sid, world.other).takeLast(300)}")
    }
}

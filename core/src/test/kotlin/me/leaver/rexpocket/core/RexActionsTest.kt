package me.leaver.rexpocket.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The person's own Rex actions from the phone: the palette lists the configuration file's actions
 * that take no required arguments (`client.keymap`), runs one for the pane on screen with origin
 * `palette` (`client.action`), and answers its questions (`client_prompt_requested`, `client.reply`)
 * on a connection of its own at revision 17, the first Rex asks questions on. On ScriptedRex every
 * run, and on a real Rex started with tools/rex/test-init.lua (the `rex-real` workflow).
 */
abstract class RexActionsTest {
    abstract fun rex(): RexUnderTest

    private fun withPhone(body: suspend (PocketSession, String) -> Unit) = runBlocking {
        val rex = rex()
        val desk = Desk(rex.endpoint)
        val phone = PocketSession(rex.endpoint)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            desk.connect()
            val (sid, _) = desk.newSession(tabs = 1)
            phone.start(scope, preferredSessionId = sid)
            withTimeout(15_000) { phone.awaitReady() }
            phone.openSession(sid)
            body(phone, sid)
        } finally {
            phone.stop()
            scope.cancel()
            desk.close()
            rex.close()
        }
    }

    /** Runs [name], answering each question with [answer]; every event, the outcome last. */
    private suspend fun PocketSession.run(name: String, answer: suspend (RexPrompt) -> JSONObject = { RexActions.cancelled() }): List<ActionEvent> =
        withTimeout(30_000) {
            runRexAction(name).transformWhile { e ->
                emit(e)
                if (e is ActionEvent.Prompt) e.prompt.answer(answer(e.prompt))
                e !is ActionEvent.Done && e !is ActionEvent.Failed
            }.toList()
        }

    private fun List<ActionEvent>.result(): JSONObject {
        val done = last() as? ActionEvent.Done ?: throw AssertionError("not done: ${last()}")
        return JSONObject(done.result ?: "{}")
    }

    @Test
    fun thePaletteListsTheFilesActionsThatNeedNoArguments() = withPhone { phone, _ ->
        val actions = phone.rexActions()
        val where = actions.firstOrNull { it.name == "rexpocket_where" } ?: throw AssertionError("no rexpocket_where in $actions")
        assertEquals("RexPocket: Where Am I", where.title)
        assertEquals("RexPocket", where.category)
        assertEquals("Says where the action ran", where.description)
        // No category in the file: Rex files it under Actions.
        assertEquals("Actions", actions.first { it.name == "rexpocket_ask" }.category)
        assertFalse("an action with a required argument is left out", actions.any { it.name == "rexpocket_needs_args" })
        assertFalse("block actions are the palette's own entries", actions.any { it.name.contains('.') })
    }

    @Test
    fun anActionRunsForThePaneOnScreenFromThePalette() = withPhone { phone, sid ->
        val block = phone.state.value.view!!.focusedBlock()!!.id
        val result = phone.run("rexpocket_where").result()
        assertEquals("palette", result.getString("origin"))
        assertEquals(sid, result.getString("session"))
        assertEquals(block, result.getString("block"))
        val unknown = phone.run("rexpocket_not_there").last()
        assertTrue("$unknown", unknown is ActionEvent.Failed)
    }

    @Test
    fun aQuestionIsAnsweredWithTextAChoiceOrClosed() = withPhone { phone, _ ->
        var asked: RexPrompt? = null
        val typed = phone.run("rexpocket_ask") { p ->
            asked = p
            RexActions.text("Ann")
        }
        val input = asked!!
        assertEquals("input", input.kind)
        assertEquals("Your name", input.title)
        assertEquals("name", input.placeholder)
        assertEquals("x", input.value)
        assertEquals("Use", input.confirm)
        assertEquals("rexpocket_ask", input.action)
        assertTrue(input.timeoutMs > 0)
        assertEquals("Ann", typed.result().getString("name"))

        val picked = phone.run("rexpocket_pick") { p ->
            asked = p
            RexActions.ids(listOf("b"))
        }
        val pick = asked!!
        assertEquals("pick", pick.kind)
        assertEquals(listOf("Apple", "b"), pick.items.map { it.id })
        assertEquals(RexPrompt.Item("b", "Banana", subtitle = "yellow", detail = "ripe"), pick.items[1])
        assertNull(pick.items[0].subtitle)
        assertEquals("b", picked.result().getString("id"))

        val closed = phone.run("rexpocket_ask") { RexActions.cancelled() }
        assertEquals("cancelled", closed.result().getString("why"))
    }
}

class ScriptedRexActionsTest : RexActionsTest() {
    override fun rex(): RexUnderTest {
        val rex = ScriptedRex()
        rex.luaActions += ScriptedRex.ScriptedAction("rexpocket_where", "RexPocket: Where Am I", category = "RexPocket", description = "Says where the action ran")
        rex.luaActions += ScriptedRex.ScriptedAction(
            "rexpocket_ask", "RexPocket: Ask a Name",
            prompt = JSONObject().put("kind", "input").put("title", "Your name").put("placeholder", "name").put("value", "x").put("confirm", "Use"),
        ) { answer, _ ->
            when {
                answer == null -> null
                answer.has("text") -> JSONObject().put("name", answer.getString("text"))
                answer.optBoolean("cancelled") -> JSONObject().put("why", "cancelled")
                else -> answer
            }
        }
        rex.luaActions += ScriptedRex.ScriptedAction(
            "rexpocket_pick", "RexPocket: Pick a Fruit",
            prompt = JSONObject().put("kind", "pick").put("title", "Fruit").put(
                "items",
                JSONArray().put(JSONObject().put("id", "Apple").put("title", "Apple"))
                    .put(JSONObject().put("id", "b").put("title", "Banana").put("subtitle", "yellow").put("icon", "star").put("detail", "ripe")),
            ),
        ) { answer, _ -> answer?.optJSONArray("ids")?.let { JSONObject().put("id", it.getString(0)) } ?: answer }
        rex.luaActions += ScriptedRex.ScriptedAction(
            "rexpocket_needs_args", "RexPocket: Needs a File",
            args = JSONObject().put("type", "object").put("required", JSONArray().put("file")).put("properties", JSONObject().put("file", JSONObject().put("type", "string"))),
        )
        return FakeRexUnderTest(rex)
    }
}

class RealRexActionsTest : RexActionsTest() {
    override fun rex(): RexUnderTest {
        val rex = RealRex()
        assumeTrue("rex plaintext listener on ${rex.endpoint.label()} (REX_TEST_HOST/REX_TEST_PORT)", rex.reachable)
        // The actions are tools/rex/test-init.lua's, which rex-real starts Rex with.
        val hasThem = runBlocking {
            val c = RexClient()
            try {
                c.connect(rex.endpoint)
                RexActions.parse(c.call("client.keymap", JSONObject())).any { it.name == "rexpocket_where" }
            } finally {
                c.close()
            }
        }
        assumeTrue("Rex was started without tools/rex/test-init.lua (--config)", hasThem)
        return rex
    }
}

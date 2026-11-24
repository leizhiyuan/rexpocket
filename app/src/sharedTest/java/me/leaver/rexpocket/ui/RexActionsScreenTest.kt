package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ScriptedRex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The person's own Rex actions in the terminal's palette: listed under their category, run for the
 * pane on screen with origin `palette`, and their questions answered in a dialog over the terminal.
 * The whole app on its view model, on ScriptedRex, which asks questions as Rex does
 * (RexActionsTest holds it to a real Rex). JVM and emulator; the typed answer on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class RexActionsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null
    private val answers = CopyOnWriteArrayList<String>()

    private fun string(id: Int) = compose.activity.getString(id)

    private fun start(): RexViewModel {
        desk.rex.luaActions += ScriptedRex.ScriptedAction(
            "pick_fruit", "Pick a Fruit", category = "Kitchen",
            prompt = JSONObject().put("kind", "pick").put("title", "Fruit").put(
                "items",
                JSONArray().put(JSONObject().put("id", "a").put("title", "Apple"))
                    .put(JSONObject().put("id", "b").put("title", "Banana").put("subtitle", "yellow")),
            ),
        ) { answer, _ -> answer.also { answers += it.toString() } }
        desk.rex.luaActions += ScriptedRex.ScriptedAction(
            "new_branch", "Git: New Branch",
            prompt = JSONObject().put("kind", "input").put("title", "New branch").put("value", "feature/").put("confirm", "Create branch"),
        ) { answer, _ -> answer.also { answers += it.toString() } }
        val app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, desk.ids[0])
        compose.runOnUiThread { m.openSessionOn(desk.host, desk.ids[0]) }
        compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private fun openPalette(query: String) {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
        compose.awaitThat { compose.onAllNodes(hasText("Pick a Fruit")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput(query)
        compose.waitForIdle()
    }

    @Test
    fun anActionRunsFromThePaletteAndItsQuestionIsAnswered() {
        val m = start()
        openPalette("fruit")
        compose.onNode(hasText("Pick a Fruit", substring = true) and hasTestTag(paletteRowTag(0))).assertExists()
        compose.onNodeWithTag(paletteRowTag(0)).performClick()
        compose.awaitThat { m.rexPrompt.value != null }
        compose.onNodeWithText("Fruit").assertExists()
        compose.onNodeWithText("yellow").assertExists()
        compose.onNodeWithTag(rexPromptItemTag("b")).performClick()
        compose.awaitThat { answers.isNotEmpty() }
        assertEquals("{\"ids\":[\"b\"]}", answers.single())
        val block = m.pocket.value.view!!.focusedBlock()!!.id
        assertEquals("pick_fruit ${desk.ids[0]} $block palette", desk.rex.actionRuns.single())
        compose.awaitThat { m.rexPrompt.value == null }

        // Cancel closes the question, and the action hears so.
        openPalette("fruit")
        compose.onNodeWithTag(paletteRowTag(0)).performClick()
        compose.awaitThat { m.rexPrompt.value != null }
        compose.onNode(hasText(string(R.string.action_cancel)) and hasClickAction()).performClick()
        compose.awaitThat { answers.size == 2 }
        assertEquals("{\"cancelled\":true}", answers[1])
    }

    @Test
    fun aTypedAnswerGoesBackAsText() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val m = start()
        openPalette("branch")
        compose.onNodeWithTag(paletteRowTag(0)).performClick()
        compose.awaitThat { m.rexPrompt.value != null }
        compose.onNode(hasSetTextAction() and !hasTestTag(PALETTE_FIELD_TAG)).performTextReplacement("feature/phone")
        compose.onNode(hasText("Create branch") and hasClickAction()).performClick()
        compose.awaitThat { answers.isNotEmpty() }
        assertTrue(answers.single(), answers.single() == "{\"text\":\"feature/phone\"}")
    }
}

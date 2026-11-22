package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.data.SavedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's text fields as a keyboard and TalkBack meet them: each is named by its hint, and Enter
 * on the keyboard goes to the next field or does what the dialog's button does.
 *
 * The dialogs' own tests run on the emulator only: on Robolectric a text field in a dialog window
 * never lets Compose go idle, whatever the field (a bare BasicTextField too).
 */
@RunWith(AndroidJUnit4::class)
class TextFieldsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int) = compose.activity.getString(id)

    private fun field(hint: Int) = compose.onNode(hasSetTextAction() and hasContentDescription(string(hint)))

    private fun ime(action: ImeAction) = SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, action)

    private val saved = mutableListOf<SavedHost>()

    private fun hostDialog() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        compose.setContent {
            RexTheme {
                HostDialog(initial = null, tailnetEnabled = false, onDismiss = {}, onDelete = null, onSave = { saved += it }, onJoinTailnet = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aFieldIsNamedByItsHintAndEnterDoesWhatItSays() {
        var done = 0
        compose.setContent {
            RexTheme {
                Column {
                    Field("Name", "", imeAction = ImeAction.Next) {}
                    Field("Address", "", imeAction = ImeAction.Done, onDone = { done++ }) {}
                    Field("Notes", "") {}
                }
            }
        }
        compose.waitForIdle()
        val address = compose.onNode(hasSetTextAction() and hasContentDescription("Address"))
        address.assert(hasText(""))
        assertTrue(
            "the hint above the field is the field's name, not a line of its own",
            compose.onAllNodes(hasText("Address")).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNode(hasSetTextAction() and hasContentDescription("Name")).assert(ime(ImeAction.Next))
        compose.onNode(hasSetTextAction() and hasContentDescription("Notes")).assert(ime(ImeAction.Default))
        address.assert(ime(ImeAction.Done)).performImeAction()
        compose.waitForIdle()
        assertEquals(1, done)
    }

    @Test
    fun enterOnTheAddressSavesAHostThatNeedsNothingMore() {
        hostDialog()
        field(R.string.field_name).assert(ime(ImeAction.Next))
        field(R.string.field_address).performTextReplacement("ws://127.0.0.1:7297")
        compose.waitForIdle()
        field(R.string.field_address).assert(ime(ImeAction.Done)).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("127.0.0.1:7297"), saved.map { "${it.host}:${it.port}" })
    }

    @Test
    fun anSshHostGoesOnToItsAccountAndSavesFromThePassword() {
        hostDialog()
        field(R.string.field_address).performTextReplacement("ssh://desk.example")
        compose.waitForIdle()
        field(R.string.field_address).assert(ime(ImeAction.Next)).performImeAction()
        compose.waitForIdle()
        assertTrue("nothing is saved before the account", saved.isEmpty())
        field(R.string.ssh_user).performTextReplacement("me")
        field(R.string.ssh_user).assert(ime(ImeAction.Next))
        field(R.string.ssh_password).performTextReplacement("secret")
        compose.waitForIdle()
        field(R.string.ssh_password).assert(ime(ImeAction.Done)).performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("me@desk.example"), saved.map { "${it.sshUser}@${it.host}" })
    }

    @Test
    fun enterInTheRenameDialogSaves() {
        assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)
        val acts = mutableListOf<suspend PocketSession.() -> Unit>()
        compose.setContent { TermOnModel(termModel(), liveState(), onAct = { acts += it }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.menu_rename_tab)).performClick()
        compose.waitForIdle()
        field(R.string.field_name).assert(ime(ImeAction.Done))
        field(R.string.field_name).performTextReplacement("deploy")
        field(R.string.field_name).performImeAction()
        compose.waitForIdle()
        assertEquals("Enter renames the tab, as Save does", 1, acts.size)
        assertTrue("the dialog is gone", compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty())
    }
}

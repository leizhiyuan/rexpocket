package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.DirEntry
import me.leaver.rexpocket.core.DirListing
import me.leaver.rexpocket.core.DirStatus
import me.leaver.rexpocket.core.PocketSession
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.TabTextSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The terminal's text size where a person can find it (terminal-controls.md, "Text size"): the ⋮
 * menu's Text size row for the tab on screen, the default for new tabs, the one-time pinch hint;
 * and the other rows of this pass that reach what only a gesture did: Select text, Insert path.
 * Runs on the JVM and the emulators; the sheets' rows are scrolled to, for API 30's short screen.
 */
@RunWith(AndroidJUnit4::class)
class TextSizeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: RexViewModel
    private val acts = mutableListOf<suspend PocketSession.() -> Unit>()
    private var state by mutableStateOf(liveState())
    private var hint by mutableStateOf(false)

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun start() {
        compose.runOnUiThread {
            compose.activity.edgeToEdgeAsTheApp()
            model = termModel()
        }
        compose.setContent {
            if (!settings) {
                TermOnModel(model, state, onAct = { acts += it }, pinchHint = hint)
            } else {
                val appearance by model.appearance.collectAsState()
                val font by model.fontScale.collectAsState()
                RexTheme {
                    AppearanceSheet(appearance, model::setAppearance, onDismiss = {}, terminalTextSize = font, onTerminalTextSize = model::setFont)
                }
            }
        }
        compose.waitForIdle()
    }

    /** Settings › Appearance in place of the terminal. */
    private var settings by mutableStateOf(false)

    private fun openMenu() {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
    }

    private fun rowSays(percent: Int) {
        compose.onNodeWithTag(TEXT_SIZE_ROW_TAG).performScrollTo()
        compose.onNode(hasAnyAncestor(hasTestTag(TEXT_SIZE_ROW_TAG)) and hasText(string(R.string.text_size_percent, percent))).assertExists()
    }

    private val buildKey = TabTextSizes.key(null, TEST_SESSION, "window:build")

    @Test
    fun theRowStepsThisTabAndResetGoesBackToTheDefault() {
        openMenu()
        rowSays(100)
        compose.onNodeWithTag(TEXT_SIZE_RESET_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performClick()
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performClick()
        compose.waitForIdle()
        // The sheet stays open: what the row changes shows on it.
        rowSays(120)
        assertEquals(1.2f, model.textSizes.sizes.of(buildKey, 1f), 0.001f)
        assertEquals("the default is left alone", 1f, model.fontScale.value, 0.001f)
        compose.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performClick()
        compose.waitForIdle()
        rowSays(110)
        compose.onNodeWithTag(TEXT_SIZE_RESET_TAG).performClick()
        compose.waitForIdle()
        rowSays(100)
        assertTrue(model.textSizes.sizes.sizes.isEmpty())
    }

    /**
     * A size pinched past the old 70-180% stays what it is on the row, and + and − step from it:
     * one range everywhere, the pinch's (6-40 sp). The default for new tabs takes it too.
     */
    @Test
    fun aPinchedSizeShowsAsItIsAndStepsFromThere() {
        // What the pane's pinch hands the screen: its new size in sp, over the 12 sp base.
        compose.runOnUiThread { model.textSizes.sizes = model.textSizes.sizes.with(buildKey, 30f / 12f, 1f) }
        openMenu()
        rowSays(250)
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performClick()
        compose.waitForIdle()
        rowSays(260)
        compose.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performClick()
        compose.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performClick()
        compose.waitForIdle()
        rowSays(240)
        compose.runOnUiThread { model.setFont(30f / 12f) }
        assertEquals(2.5f, model.fontScale.value, 0.001f)
    }

    @Test
    fun anotherTabFollowsTheDefaultWhileThisOneKeepsItsOwn() {
        openMenu()
        compose.onNodeWithTag(TEXT_SIZE_SMALLER_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        rowSays(90)
        // The default changes in Settings: this tab keeps its own, a new tab starts at the default.
        compose.runOnUiThread { model.setFont(1.3f) }
        compose.waitForIdle()
        rowSays(90)
        val logs = tabOf(testPane("logs"), id = "window:logs", label = "logs")
        compose.runOnUiThread {
            state = liveState(logs).copy(view = SessionView(3, TEST_SESSION, "work", logs.id, listOf(logs)))
        }
        compose.waitForIdle()
        rowSays(130)
    }

    @Test
    fun theDefaultForNewTabsIsInAppearanceWithAReset() {
        settings = true
        compose.waitForIdle()
        compose.onNodeWithTag(TERMINAL_TEXT_SIZE_TAG).performScrollTo()
        compose.onNode(hasText(string(R.string.appearance_terminal_text_size)) and hasText(string(R.string.text_size_percent, 100))).assertExists()
        compose.onNodeWithTag(TERMINAL_TEXT_SIZE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1.1f, model.fontScale.value, 0.001f)
        compose.onNodeWithText(string(R.string.terminal_font_size, 110)).assertExists()
        compose.onNodeWithTag(TEXT_SIZE_RESET_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1f, model.fontScale.value, 0.001f)
        // Kept, as Settings are.
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1.1f, RexViewModel(compose.activity.application).fontScale.value, 0.001f)
    }

    @Test
    fun thePinchHintShowsOnceAndIsRemembered() {
        hint = true
        compose.waitForIdle()
        compose.onNodeWithTag(PINCH_HINT_TAG).assertExists()
        compose.onNodeWithText(string(R.string.pinch_hint_dismiss)).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag(PINCH_HINT_TAG)).fetchSemanticsNodes().isEmpty())
        assertFalse(model.pinchHint.value)
        assertFalse("a new start does not show it again", RexViewModel(compose.activity.application).pinchHint.value)
    }

    @Test
    fun changingTheSizeIsFindingItSoTheHintGoes() {
        hint = true
        compose.waitForIdle()
        openMenu()
        compose.onNodeWithTag(TEXT_SIZE_BIGGER_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(model.pinchHint.value)
    }

    @Test
    fun theMenuStartsASelectionAndCopiesTheOutput() {
        openMenu()
        compose.onNodeWithText(string(R.string.select_text)).performScrollTo().performClick()
        compose.waitForIdle()
        // No picture on this pane: a toast says there is nothing yet, and nothing goes to Rex.
        assertTrue(acts.isEmpty())
        openMenu()
        compose.onNodeWithText(string(R.string.palette_copy_output)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Copy output reads the pane from Rex", 1, acts.size)
    }

    @Test
    fun clearTerminalFromTheMenuAsksFirst() {
        openMenu()
        compose.onNodeWithText(string(R.string.palette_clear_terminal)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(acts.isEmpty())
        compose.onNodeWithText(string(R.string.confirm_clear_terminal)).assertExists()
        compose.onNodeWithText(string(R.string.action_clear)).performClick()
        compose.waitForIdle()
        assertEquals(1, acts.size)
    }

    /**
     * Insert path from the key bar too: a key of its own, on the bar by default. Its tap opens the
     * browser over the pane on screen (by its semantics: this screen keeps the key bar scrolled).
     */
    @Test
    fun insertPathOpensFromItsKey() {
        compose.onNode(hasContentDescription(string(R.string.insert_path)) and hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.waitUntil(5_000) { acts.isNotEmpty() }
        compose.onNodeWithText(string(R.string.insert_path_loading)).assertExists()
    }

    @Test
    fun insertPathOpensFromThePaletteAndAsksRexForTheFolder() {
        openMenu()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput("insert path")
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.insert_path)).performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { acts.isNotEmpty() }
        // The folder is asked of Rex (the act, not run here); until it answers the sheet says so.
        compose.onNodeWithText(string(R.string.insert_path_loading)).assertExists()
    }
}

/** Insert path's browser on its own, over a made-up host. */
@RunWith(AndroidJUnit4::class)
class InsertPathTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val folders = mapOf(
        "" to DirListing("/home/me/src", "/home/me", DirStatus.Ok, DirListing.sorted(listOf(DirEntry("app"), DirEntry("gone"), DirEntry("denied"), DirEntry("my notes.txt", file = true), DirEntry(".git"))), false),
        "/home/me/src/denied" to DirListing("/home/me/src/denied", "/home/me", DirStatus.Denied, emptyList(), false),
        "/home/me/src/app" to DirListing("/home/me/src/app", "/home/me", DirStatus.Ok, emptyList(), false),
        "/home/me" to DirListing("/home/me", "/home/me", DirStatus.Ok, listOf(DirEntry("src")), false),
        "/home/me/src" to DirListing("/home/me/src", "/home/me", DirStatus.Ok, listOf(DirEntry("app")), false),
    )
    private val asked = mutableListOf<String>()
    private val inserted = mutableListOf<String>()
    private var open by mutableStateOf(true)

    @Before
    fun start() {
        compose.setContent {
            RexTheme {
                if (open) {
                    InsertPathSheet(
                        list = { path -> asked += path; folders[path] },
                        onInsert = { inserted += it },
                        onDismiss = { open = false },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun itStartsWhereTheProgramIsAndTypesAFileQuoted() {
        assertEquals("", asked.first())
        compose.onNodeWithText("~/src").assertExists()
        // Dot entries wait for Show hidden.
        assertTrue(compose.onAllNodes(hasTestTag(insertPathEntryTag(".git"))).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText(string(R.string.insert_path_show_hidden)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(insertPathEntryTag(".git")).assertExists()
        compose.onNodeWithTag(insertPathEntryTag("my notes.txt")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("~/'src/my notes.txt'"), inserted)
        compose.waitUntil(5_000) { !open }
    }

    @Test
    fun aFolderOpensInPlaceAndItsPathIsInserted() {
        compose.onNodeWithTag(insertPathEntryTag("app")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("~/src/app").assertExists()
        compose.onNodeWithText(string(R.string.insert_path_empty)).assertExists()
        compose.onNodeWithTag(INSERT_PATH_UP_TAG).performClick()
        compose.waitForIdle()
        assertEquals("/home/me/src", asked.last())
        compose.onNodeWithTag(INSERT_PATH_HERE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf("~/src"), inserted)
    }

    @Test
    fun aFolderThatCannotBeReadSaysSoAndUpStillWorks() {
        compose.onNodeWithTag(insertPathEntryTag("gone")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.insert_path_failed)).assertExists()
        assertTrue(compose.onAllNodes(hasTestTag(INSERT_PATH_HERE_TAG)).fetchSemanticsNodes().isEmpty())
        // The way back is still there.
        compose.onNodeWithTag(INSERT_PATH_UP_TAG).performClick()
        compose.waitForIdle()
        assertEquals("/home/me/src", asked.last())
        compose.onNodeWithTag(insertPathEntryTag("app")).assertExists()
    }

    @Test
    fun aFolderRexMayNotReadSaysWhy() {
        compose.onNodeWithTag(insertPathEntryTag("denied")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.insert_path_denied)).assertExists()
        compose.onNodeWithTag(INSERT_PATH_UP_TAG).performClick()
        compose.waitForIdle()
        assertEquals("/home/me/src", asked.last())
    }
}

/** The key bar's Insert path key, and the Attach key beside it, which is only Send a file now. */
@RunWith(AndroidJUnit4::class)
class InsertPathKeyTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Test
    fun insertPathIsAKeyOfItsOwnAndAttachOnlySendsAFile() {
        var inserts = 0
        var attaches = 0
        compose.setContent {
            RexTheme {
                androidx.compose.foundation.layout.Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(891.dp)) {
                    KeyBar(
                        mods = me.leaver.rexpocket.core.KeyModifiers(), busy = false, imeUp = false, onMod = {}, onKey = { _, _ -> },
                        onNewLine = {}, onPaste = {}, onAttach = { attaches++ }, attachReady = true, onKeyboard = {},
                        onInsertPath = { inserts++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasContentDescription(string(R.string.insert_path)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(1, inserts)
        assertEquals(0, attaches)
        // One route each: a long press on Attach is no second Insert path, and TalkBack offers none.
        val attach = compose.onNode(hasContentDescription(string(R.string.upload_attach)) and hasClickAction())
        val actions = attach.fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsActions.CustomActions) { emptyList() }
        assertTrue("$actions", actions.isEmpty())
        // Held, it is a tap: a file from the phone.
        attach.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, inserts)
        assertEquals(1, attaches)
    }
}

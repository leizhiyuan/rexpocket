package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.emptyFlow
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.TapAction
import me.leaver.rexpocket.core.DeskKey
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.KeyPreset
import me.leaver.rexpocket.core.PocketState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pictures of the screens a change to the controls touches, portrait and landscape, for a person to
 * look at: build/outputs/roborazzi, uploaded by device-test as "screenshots". Nothing is compared
 * against them; the behaviour is the screen tests' (ArrangeKeysTest, TerminalControlsTest). Each
 * shows the app's own pages on its view model, as the screen tests do. See docs/design/testing.md.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PHONE)
class ScreenshotsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun landscape() = RuntimeEnvironment.setQualifiers(PHONE_LANDSCAPE)

    private fun controls(page: ControlsPage, name: String) {
        val model = RexViewModel(freshApp())
        compose.setContent { ControlsOnModel(model, page, onPage = {}, bar = false) }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** A row of the sheet tapped, as a person opens a page from it. */
    private fun open(text: String) {
        compose.onAllNodes(hasText(text) and hasClickAction())[0].performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Arrange keys, then the page [path] leads to, captured. */
    private fun arrangePage(name: String, vararg path: Int) {
        val model = RexViewModel(freshApp())
        compose.setContent { ControlsOnModel(model, ControlsPage.ArrangeKeys, onPage = {}, bar = false) }
        compose.waitForIdle()
        for (step in path) open(if (step == 0) "Fn" else compose.activity.getString(step))
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun terminal(name: String, arrangement: KeyArrangement = KeyArrangement(), panel: String? = null) {
        compose.setContent {
            RexTheme {
                TermScreen(
                    state = PocketState(),
                    fontScale = 1f,
                    terminalTypeface = Typeface.MONOSPACE,
                    onFont = {},
                    onBack = {},
                    onOpenSession = {},
                    watchPane = { emptyFlow() },
                    onRetry = {},
                    onDismissNotice = {},
                    onAct = {},
                    upload = null,
                    onAttach = {},
                    onCancelUpload = {},
                    onDismissUpload = {},
                    onRetryUpload = {},
                    gestures = GestureSettings(),
                    keyBarWithKeyboard = true,
                    keyArrangement = arrangement,
                )
            }
        }
        compose.waitForIdle()
        if (panel != null) {
            compose.onNodeWithTag(groupKeyTag(panel)).performScrollTo().performClick()
            compose.waitForIdle()
        }
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The terminal of a live session, as the app shows it on its view model, with the ⋮ menu open. */
    private fun menu(name: String) {
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState(tabOf(testPane("left"), testPane("right")))) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Full screen right after the menu's item, before anything else could lay the screen out again. */
    private fun fullScreen(name: String) {
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState()) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.action_full_screen)).performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** The command palette over a live terminal, opened from the ⋮ menu, with [query] typed. */
    private fun palette(name: String, query: String = "") {
        val model = termModel()
        compose.setContent { TermOnModel(model, liveState(tabOf(testPane("left"), testPane("right")))) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.palette_title)).performClick()
        compose.waitForIdle()
        if (query.isNotEmpty()) compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput(query)
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /**
     * The home screen's activity view, the whole app on ScriptedRex: a failed tab with its message,
     * one waiting, one done and one running.
     */
    private fun activity(name: String) {
        RexOnDesk(sessions = 2).use { desk ->
            val app = freshApp()
            desk.saveFor(app, lastSession = desk.ids[0])
            val model = launchedModel(compose, app)
            compose.setContent { RexPocketApp(model) }
            compose.awaitLive(model, desk.ids[0])
            compose.awaitThat { model.pocket.value.views.containsKey(desk.ids[1]) }
            val views = model.pocket.value.views
            val first = views.getValue(desk.ids[0]).windows
            val second = views.getValue(desk.ids[1]).windows
            desk.rex.report(first[0].blocks[0].id, "error", msg = "failed: prompt_failed: 401 Unauthorized", app = "pi")
            desk.rex.report(first[1].blocks[0].id, "blocked", kind = "permission", msg = "Apply 3 to add, 1 to change, 0 to destroy?", app = "terraform")
            desk.rex.report(second[0].blocks[0].id, "done", msg = "Upgraded 12 packages", app = "brew")
            desk.rex.report(second[1].blocks[0].id, "working", app = "claude")
            compose.awaitThat { model.pocket.value.blockStatus.size == 4 }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_show_active)).performClick()
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
            compose.runOnUiThread { model.disconnect() }
        }
    }

    /** Terminal controls with every choice row moved off its default, so its icons show the change. */
    private fun controlsChanged(name: String) {
        val model = RexViewModel(freshApp())
        compose.runOnUiThread {
            model.setGestures(
                GestureSettings(tap = TapAction.Nothing, doubleTap = DoubleTapAction.Keyboard, longPress = LongPressAction.FullScreen),
            )
            model.setKeyBarCtrlC(CtrlCKey.Hold)
        }
        compose.setContent { ControlsOnModel(model, ControlsPage.Controls, onPage = {}, bar = false) }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    /** Settings, and with [row] the sheet that row opens, from the home screen of a connected app. */
    private fun settings(name: String, row: Int? = null) {
        RexOnDesk(sessions = 1).use { desk ->
            val app = freshApp()
            desk.saveFor(app, lastSession = desk.ids[0])
            val model = launchedModel(compose, app)
            compose.setContent { RexPocketApp(model) }
            compose.awaitLive(model, desk.ids[0])
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
            compose.waitForIdle()
            if (row != null) open(compose.activity.getString(row))
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
            compose.runOnUiThread { model.disconnect() }
        }
    }

    @Test fun controlsChangedPortrait() = controlsChanged("controls-changed-portrait")
    @Test fun controlsChangedLandscape() { landscape(); controlsChanged("controls-changed-landscape") }
    @Test fun settingsPortrait() = settings("settings-portrait")
    @Test fun settingsLandscape() { landscape(); settings("settings-landscape") }
    @Test fun diagnosticsPortrait() = settings("diagnostics-portrait", R.string.diagnostics)
    @Test fun groupNamePortrait() = arrangePage("group-name-portrait", R.string.arrange_new_group, R.string.arrange_empty_group)

    @Test fun palettePortrait() = palette("command-palette-portrait")
    @Test fun paletteLandscape() { landscape(); palette("command-palette-landscape") }
    @Test fun paletteSearchPortrait() = palette("command-palette-search-portrait", "tab")
    @Test fun activityPortrait() = activity("activity-portrait")
    @Test fun activityLandscape() { landscape(); activity("activity-landscape") }

    @Test fun menuPortrait() = menu("terminal-menu-portrait")
    @Test fun menuLandscape() { landscape(); menu("terminal-menu-landscape") }
    @Test fun fullScreenPortrait() = fullScreen("terminal-full-screen-portrait")
    @Test fun fullScreenLandscape() { landscape(); fullScreen("terminal-full-screen-landscape") }

    /** Arrange keys right after Paste's up button: Paste must be drawn above Fn. */
    @Test
    fun arrangeKeysAfterAMove() {
        controls(ControlsPage.ArrangeKeys, "arrange-keys-portrait-before-move")
        val a = compose.activity
        compose.onNodeWithContentDescription(a.getString(R.string.arrange_move_up, a.getString(R.string.key_paste)))
            .performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/arrange-keys-portrait-paste-moved-up.png")
    }

    @Test fun terminalPortrait() = terminal("terminal-portrait")
    @Test fun terminalLandscape() { landscape(); terminal("terminal-landscape") }
    @Test fun controlsPortrait() = controls(ControlsPage.Controls, "controls-portrait")
    @Test fun controlsLandscape() { landscape(); controls(ControlsPage.Controls, "controls-landscape") }
    @Test fun arrangeKeysPortrait() = controls(ControlsPage.ArrangeKeys, "arrange-keys-portrait")
    @Test fun arrangeKeysLandscape() { landscape(); controls(ControlsPage.ArrangeKeys, "arrange-keys-landscape") }
    @Test fun addKeysPortrait() = arrangePage("add-keys-portrait", R.string.arrange_add_keys)
    @Test fun addKeysLandscape() { landscape(); arrangePage("add-keys-landscape", R.string.arrange_add_keys) }
    @Test fun fnGroupPortrait() = arrangePage("fn-group-portrait", 0)
    @Test fun fnGroupLandscape() { landscape(); arrangePage("fn-group-landscape", 0) }
    @Test fun newGroupPortrait() = arrangePage("new-group-portrait", R.string.arrange_new_group)
    @Test fun newGroupLandscape() { landscape(); arrangePage("new-group-landscape", R.string.arrange_new_group) }

    /** The bar with F10 added and the htop group made, its panel open. */
    private val htopBar = KeyArrangement().add(BarKey.Desk(DeskKey.F10)).moveTo(BarKey.Desk(DeskKey.F10), 2).addPreset(KeyPreset.Htop)
    @Test fun terminalFnPanelPortrait() = terminal("terminal-fn-panel-portrait", panel = BarKey.FN_GROUP)
    @Test fun terminalHtopPortrait() = terminal("terminal-htop-portrait", htopBar, panel = "htop")
    @Test fun terminalHtopLandscape() { landscape(); terminal("terminal-htop-landscape", htopBar, panel = "htop") }
}

/** A Pixel 6 or so, the phone the emulator in device-test is. */
private const val PHONE = "w411dp-h915dp-port-xxhdpi"
private const val PHONE_LANDSCAPE = "w915dp-h411dp-land-xxhdpi"

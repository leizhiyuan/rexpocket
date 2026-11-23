package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasContentDescription
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
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.net.TailnetStatus
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

    /**
     * The share sheet over the home of a connected app: for the pane the phone showed last, or with
     * [picker] the pane picker; with [manage] instead, the home's sheet for a tab.
     */
    private fun shareOrManage(name: String, picker: Boolean = false, manage: Boolean = false) {
        RexOnDesk(sessions = 2).use { desk ->
            val app = freshApp()
            val store = desk.saveFor(app, lastSession = desk.ids[1])
            val model = launchedModel(compose, app)
            compose.setContent { RexPocketApp(model) }
            compose.awaitLive(model, desk.ids[1])
            val pane = model.pocket.value.views.getValue(desk.ids[1]).windows.last().blocks.first().id
            if (!picker) store.setLastPane(desk.host.id, desk.ids[1], pane, desk.ids)
            if (manage) {
                compose.onAllNodes(hasContentDescription(compose.activity.getString(R.string.manage_actions_for, compose.activity.getString(R.string.tab_number, 2))))[1]
                    .performClick()
            } else {
                compose.runOnUiThread { model.offerShare(ShareRequest("git log --oneline | head -20", emptyList())) }
            }
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
            compose.runOnUiThread { model.disconnect() }
        }
    }

    @Test fun sharePortrait() = shareOrManage("share-portrait")
    @Test fun shareLandscape() { landscape(); shareOrManage("share-landscape") }
    @Test fun sharePickerPortrait() = shareOrManage("share-picker-portrait", picker = true)
    @Test fun sharePickerLandscape() { landscape(); shareOrManage("share-picker-landscape", picker = true) }
    @Test fun tabSheetPortrait() = shareOrManage("home-tab-sheet-portrait", manage = true)
    @Test fun tabSheetLandscape() { landscape(); shareOrManage("home-tab-sheet-landscape", manage = true) }

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

    /**
     * The Tap row's picker, the current choice selected. Shown on its own: under Robolectric the
     * tall Terminal controls sheet's window is drawn over a dialog opened from it (on a phone the
     * dialog is on top; AppearanceTest's picker shot shows both, its sheet being short).
     */
    private fun picker(name: String) {
        compose.setContent {
            RexTheme {
                ChoicePicker(compose.activity.getString(R.string.gesture_tap), TapAction.entries, TapAction.Keyboard, ::choice, {}, {})
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun tapPickerPortrait() = picker("picker-tap-portrait")
    @Test fun tapPickerLandscape() { landscape(); picker("picker-tap-landscape") }

    /**
     * Settings › Appearance with a light app and an always-dark terminal; with [row], that row's
     * picker; with [text], the app's text at that size.
     */
    private fun appearance(name: String, row: Int? = null, text: me.leaver.rexpocket.core.TextSize = me.leaver.rexpocket.core.TextSize.System) {
        val model = RexViewModel(freshApp())
        compose.runOnUiThread {
            model.setAppearance(me.leaver.rexpocket.core.Appearance(me.leaver.rexpocket.core.AppTheme.Light, me.leaver.rexpocket.core.TerminalColors.Dark, text))
        }
        compose.setContent { AppearanceOnModel(model, open = true) }
        compose.waitForIdle()
        if (row != null) open(compose.activity.getString(row))
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun appearancePortrait() = appearance("appearance-light-app-dark-terminal-portrait")
    @Test fun appearanceLandscape() { landscape(); appearance("appearance-light-app-dark-terminal-landscape") }
    @Test fun appearancePickerPortrait() = appearance("picker-terminal-colours-portrait", R.string.appearance_terminal)
    @Test fun settingsPortrait() = settings("settings-portrait")
    @Test fun settingsLandscape() { landscape(); settings("settings-landscape") }
    @Test fun diagnosticsPortrait() = settings("diagnostics-portrait", R.string.diagnostics)
    @Test fun notificationsPortrait() = settings("notifications-portrait", R.string.notifications_title)
    @Test fun notificationsLandscape() { landscape(); settings("notifications-landscape", R.string.notifications_title) }
    @Test fun groupNamePortrait() = arrangePage("group-name-portrait", R.string.arrange_new_group, R.string.arrange_empty_group)

    /**
     * The home screen with the tailnet in [status] - the whole app on its view model, the node a
     * stand-in reporting what a real one does (see TailnetHarness) - with [open] connecting through
     * [socks], the details sheet open when [details], and the clock moved on until [waitFor] shows.
     */
    private fun tailnet(
        name: String,
        status: TailnetStatus,
        open: SavedHost? = null,
        socks: FakeSocks? = null,
        dialError: String? = null,
        startError: String? = null,
        details: Boolean = false,
        waitFor: String? = null,
        backend: TailnetBackend = TailnetBackend.Tailscale,
        joined: Boolean = true,
        hosts: List<SavedHost> = listOf(TailnetSamples.macHost, TailnetSamples.nasHost),
    ) {
        val node = FakeTailnetNode()
        node.state.value = status
        node.startError = startError
        socks?.let { node.socksPort = it.port }
        if (dialError != null && open != null) node.dialErrors[open.host] = dialError
        val model = tailnetModel(compose, node, hosts, open, backend, joined)
        try {
            compose.setContent { RexPocketApp(model) }
            compose.waitForIdle()
            if (details) {
                compose.onNodeWithTag(TAILNET_CARD_TAG).performClick()
                compose.waitForIdle()
            }
            if (waitFor != null) {
                compose.awaitThat(15_000) {
                    compose.mainClock.advanceTimeBy(500)
                    compose.onAllNodes(hasText(waitFor, substring = true)).fetchSemanticsNodes().isNotEmpty()
                }
            }
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            compose.runOnUiThread { model.disconnect() }
            socks?.close()
        }
    }

    private fun refusing() = FakeSocks { _, _ -> null }

    @Test fun tailnetStartingPortrait() = tailnet("tailnet-starting-portrait", TailnetSamples.starting)
    @Test fun tailnetNeedsLoginPortrait() = tailnet("tailnet-needs-login-portrait", TailnetSamples.needsLogin)
    @Test fun tailnetNeedsLoginLandscape() { landscape(); tailnet("tailnet-needs-login-landscape", TailnetSamples.needsLogin) }
    @Test fun tailnetConnectingPortrait() = tailnet("tailnet-connecting-portrait", TailnetSamples.connecting)
    @Test fun tailnetConnectedPortrait() = tailnet("tailnet-connected-portrait", TailnetSamples.connected())
    @Test fun tailnetConnectedLandscape() { landscape(); tailnet("tailnet-connected-landscape", TailnetSamples.connected()) }
    @Test fun tailnetDetailsPortrait() = tailnet("tailnet-details-portrait", TailnetSamples.connected(), details = true)
    @Test fun tailnetDetailsLandscape() { landscape(); tailnet("tailnet-details-landscape", TailnetSamples.connected(), details = true) }
    @Test fun tailnetErrorPortrait() = tailnet("tailnet-error-portrait", TailnetSamples.starting, startError = "tsnet: open state: permission denied")
    @Test fun tailnetNotJoinedPortrait() = tailnet("tailnet-not-joined-portrait", TailnetSamples.starting, joined = false)
    @Test fun tailnetNetbirdDetailsPortrait() = tailnet(
        "tailnet-netbird-details-portrait",
        TailnetSamples.netbird(),
        details = true,
        backend = TailnetBackend.Netbird,
        hosts = listOf(TailnetSamples.macHost.copy(host = "mac.netbird.cloud")),
    )

    @Test
    fun tailnetPeerOfflinePortrait() = tailnet(
        "tailnet-peer-offline-portrait",
        TailnetSamples.connected(),
        open = TailnetSamples.nasHost,
        socks = refusing(),
        dialError = "dial tcp 100.64.0.3:7297: i/o timeout",
        waitFor = "is offline on Tailscale",
    )

    @Test
    fun tailnetRexNotListeningPortrait() = tailnet(
        "tailnet-rex-not-listening-portrait",
        TailnetSamples.connected(),
        open = TailnetSamples.macHost,
        socks = refusing(),
        dialError = "connect tcp 100.64.0.2:7297: connection was refused",
        waitFor = "nothing listens on port 7297",
    )

    @Test
    fun tailnetReachingHostPortrait() = tailnet(
        "tailnet-connect-reaching-portrait",
        TailnetSamples.connected(),
        open = TailnetSamples.macHost,
        // A proxy that takes the connection and never answers: a peer that does not.
        socks = FakeSocks(stall = true) { _, _ -> null },
        waitFor = "Reaching mac over Tailscale",
    )

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

    /** The first run, with no hosts: its three routes, or the tailnet's card in place of one. */
    private fun emptyHome(name: String, tailnet: TailnetStatus? = null) {
        if (tailnet == null) {
            val model = launchedModel(compose, freshApp())
            compose.setContent { RexPocketApp(model) }
            compose.waitForIdle()
            captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        } else {
            tailnet(name, tailnet, hosts = emptyList())
        }
    }

    @Test fun emptyHomePortrait() = emptyHome("empty-home-portrait")
    @Test fun emptyHomeLandscape() { landscape(); emptyHome("empty-home-landscape") }
    @Test fun emptyHomeTailnetPortrait() = emptyHome("empty-home-tailnet-portrait", TailnetSamples.needsLogin)

    /**
     * A narrow phone (360 dp) with the system font made [scale] times larger: what has to wrap
     * rather than be cut off or run into its neighbour. See docs/design/testing.md.
     */
    private fun large(scale: Float) {
        RuntimeEnvironment.setQualifiers(NARROW_PHONE)
        RuntimeEnvironment.setFontScale(scale)
    }


    @Test fun largeFont13EmptyHome() { large(1.3f); emptyHome("large-1.3-empty-home") }
    @Test fun largeFont15EmptyHome() { large(1.5f); emptyHome("large-1.5-empty-home") }
    @Test fun largeFont15EmptyHomeTailnet() { large(1.5f); emptyHome("large-1.5-empty-home-tailnet", TailnetSamples.needsLogin) }
    @Test fun largeFont13Controls() { large(1.3f); controls(ControlsPage.Controls, "large-1.3-controls") }
    @Test fun largeFont15Controls() { large(1.5f); controls(ControlsPage.Controls, "large-1.5-controls") }
    @Test fun largeFont13ArrangeKeys() { large(1.3f); controls(ControlsPage.ArrangeKeys, "large-1.3-arrange-keys") }
    @Test fun largeFont15ArrangeKeys() { large(1.5f); controls(ControlsPage.ArrangeKeys, "large-1.5-arrange-keys") }
    @Test fun largeFont15FnGroup() { large(1.5f); arrangePage("large-1.5-fn-group", 0) }
    @Test fun largeFont13Settings() { large(1.3f); settings("large-1.3-settings") }
    @Test fun largeFont15Settings() { large(1.5f); settings("large-1.5-settings") }
    @Test fun largeFont13TailnetSignIn() { large(1.3f); tailnet("large-1.3-tailnet-needs-login", TailnetSamples.needsLogin) }
    @Test fun largeFont15TailnetSignIn() { large(1.5f); tailnet("large-1.5-tailnet-needs-login", TailnetSamples.needsLogin) }
    @Test fun largeFont15TailnetConnected() { large(1.5f); tailnet("large-1.5-tailnet-connected", TailnetSamples.connected()) }
    // Text size at its largest on top of a phone at 1.3: the app's text at 1.95.
    @Test fun textSize150On13Appearance() { large(1.3f); appearance("large-1.3-text-150-appearance", text = me.leaver.rexpocket.core.TextSize.Largest) }
    @Test fun textSize150On13Picker() {
        large(1.3f)
        appearance("large-1.3-text-150-picker", R.string.appearance_text_size, me.leaver.rexpocket.core.TextSize.Largest)
    }
    @Test fun largeFont15TailnetNotJoined() { large(1.5f); tailnet("large-1.5-tailnet-not-joined", TailnetSamples.starting, joined = false) }
}

/** A Pixel 6 or so, the phone the emulator in device-test is. */
private const val PHONE = "w411dp-h915dp-port-xxhdpi"
private const val PHONE_LANDSCAPE = "w915dp-h411dp-land-xxhdpi"

/** A narrow phone, 360 dp wide, for the large-font pictures. */
private const val NARROW_PHONE = "w360dp-h780dp-port-xxhdpi"

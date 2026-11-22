package me.leaver.rexpocket.ui

import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.flow.emptyFlow
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.GestureSettings
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

    private fun terminal(name: String) {
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
                )
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

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
}

/** A Pixel 6 or so, the phone the emulator in device-test is. */
private const val PHONE = "w411dp-h915dp-port-xxhdpi"
private const val PHONE_LANDSCAPE = "w915dp-h411dp-land-xxhdpi"

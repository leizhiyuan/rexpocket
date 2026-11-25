package me.leaver.rexpocket.ui

import android.app.KeyguardManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Security's Lock RexPocket row on the whole app, with the keyguard shadowed: a phone
 * found a tap on it did nothing. A tap now opens the phone's prompt or says why it cannot, out loud
 * (a toast TalkBack reads) and on the row. JVM only; AppLockScreenTest has the emulator's part.
 */
@RunWith(AndroidJUnit4::class)
class AppLockRowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        desk.close()
    }

    private fun secure(on: Boolean) {
        val keyguard = compose.activity.getSystemService(KeyguardManager::class.java)
        org.robolectric.Shadows.shadowOf(keyguard).setIsDeviceSecure(on)
    }

    private fun openSecurity(): RexViewModel {
        val app = freshApp()
        desk.saveFor(app)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.security_title)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        return m
    }

    /** No screen lock: the row still takes the tap, and says what to do rather than nothing. */
    @Test
    fun aTapOnAPhoneWithoutAScreenLockSaysWhy() {
        secure(false)
        val m = openSecurity()
        org.robolectric.shadows.ShadowToast.reset()
        compose.onNodeWithTag(SECURITY_LOCK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(string(R.string.security_lock_no_screen_lock), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertFalse(m.security.value.lock)
    }

    /** A prompt that could not ask says the phone's reason on the row, and the lock stays off. */
    @Test
    fun aPromptThatCannotAskSaysWhyOnTheRow() {
        var prefs by androidx.compose.runtime.mutableStateOf(me.leaver.rexpocket.core.SecurityPrefs())
        compose.setContent {
            RexTheme {
                SecuritySheet(
                    prefs = prefs,
                    onPrefs = { prefs = it },
                    onDismiss = {},
                    authenticate = { it(AuthResult.Failed("Biometric hardware unavailable")) },
                    problem = { null },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SECURITY_LOCK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        val why = string(R.string.security_lock_failed, "Biometric hardware unavailable")
        assertTrue(seen(why))
        assertEquals(why, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertFalse(prefs.lock)
    }

    /** The prompt's own errors: putting it away is a no; anything else is a failure in the phone's words. */
    @Test
    fun thePromptsErrorsAreANoOrAReason() {
        assertEquals(AuthResult.Cancelled, AppLockGate.resultOf(android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, "Cancelled"))
        assertEquals(AuthResult.Cancelled, AppLockGate.resultOf(android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED, null))
        assertEquals(AuthResult.Failed("No PIN, pattern or password set"), AppLockGate.resultOf(14, "No PIN, pattern or password set"))
        assertEquals(AuthResult.Failed("error 1"), AppLockGate.resultOf(1, ""))
    }
}

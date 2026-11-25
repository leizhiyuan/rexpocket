package me.leaver.rexpocket.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app lock as a phone found it: Lock RexPocket did nothing. The phone's prompt needs
 * USE_BIOMETRIC, which the app did not ask for, so the prompt never opened. Here: the permission,
 * the phone's real prompt on the emulator, and the view model's lock (a cold start, the wait,
 * unlocking). JVM and emulator; AppLockRowTest has the row on a shadowed keyguard.
 */
@RunWith(AndroidJUnit4::class)
class AppLockScreenTest {
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

    /** The framework's BiometricPrompt and BiometricManager refuse an app without USE_BIOMETRIC. */
    @Test
    fun theAppAsksForThePermissionThePhonesPromptNeeds() {
        val context = compose.activity
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().toList().toString(), Manifest.permission.USE_BIOMETRIC in info.requestedPermissions.orEmpty())
    }

    /** A cold start with the lock on is locked; it opens on a yes, and locks again after the wait away, not before. */
    @Test
    fun theLockEngagesOnAColdStartAndAfterTheWaitAndAYesUnlocksIt() {
        val app = freshApp()
        desk.saveFor(app).setSecurity(me.leaver.rexpocket.core.SecurityPrefs(lock = true, lockAfterSeconds = 60))
        val m = launchedModel(compose, app)
        model = m
        assertTrue("a cold start with the lock on is locked", m.locked.value)
        compose.runOnUiThread { m.unlocked() }
        assertFalse(m.locked.value)
        compose.runOnUiThread {
            m.lockAway(1_000)
            m.lockBack(50_000)
        }
        assertFalse("back within the wait", m.locked.value)
        compose.runOnUiThread {
            m.lockAway(100_000)
            m.lockBack(161_000)
        }
        assertTrue("back after the wait", m.locked.value)
        // The prompt's own PIN screen takes the app off screen: that is not leaving.
        compose.runOnUiThread {
            m.lockAsking()
            m.lockAway(200_000)
            m.lockBack(400_000)
            m.unlocked()
        }
        assertFalse(m.locked.value)
    }

    /**
     * The phone's real prompt, as the lock asks it, on the emulator: Android takes the request (a
     * phone without USE_BIOMETRIC is refused with a SecurityException before anything shows). The
     * emulator has no screen lock, so it may answer at once with an error of its own; that is fine.
     */
    @Test
    fun thePhonesPromptIsNotRefused() {
        org.junit.Assume.assumeFalse("no biometric service on the JVM", onRobolectric)
        assumeTrue(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
        val cancel = android.os.CancellationSignal()
        val result = java.util.concurrent.atomic.AtomicReference<AuthResult?>(null)
        compose.runOnUiThread {
            AppLockGate.prompt(compose.activity, "test", "test", cancel) { result.set(it) }
        }
        Thread.sleep(1_500)
        compose.runOnUiThread { cancel.cancel() }
        val got = result.get()
        assertFalse("refused: $got", got is AuthResult.Failed && got.cause is SecurityException)
    }
}

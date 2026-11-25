package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.BarKey
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.HostLink
import me.leaver.rexpocket.core.KeyArrangement
import me.leaver.rexpocket.core.SecurityPrefs
import me.leaver.rexpocket.core.SettingsFormat
import me.leaver.rexpocket.core.TapAction
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts, settings and backup on the app's own view model and store: the backup's new parts come
 * back as they went, Terminal controls and Notifications reset to how the app comes, Export hosts
 * says what it left out and why, a rexpocket://add link opens Add host without saving anything,
 * and the app lock stands in for the app until the phone confirms the person. Runs on the JVM
 * (Robolectric) and on the emulator. See docs/design/settings-backup.md and app-lock.md.
 */
@RunWith(AndroidJUnit4::class)
class HostsAndSecurityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null
    private lateinit var store: HostStore

    private val agents = SavedHost(
        id = "agents-id", label = "agents", host = "agents.example.com", port = 22, plaintext = true, sshUser = "me", sshPassword = "pw",
        fallbacks = listOf("ssh://me@100.92.1.2:22", "ssh://me@agents.local:2222", "ssh://me@10.0.0.5:22"), tryOthers = false,
    )

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun app(): RexViewModel {
        val app = freshApp()
        store = desk.saveFor(app)
        store.upsert(agents)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.waitForIdle()
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        desk.close()
    }

    @Test
    fun theKeyBarGesturesSecurityAndEachHostsAddressesComeBackFromABackup() {
        val m = app()
        val arrangement = KeyArrangement().remove(BarKey.Slash).add(BarKey.Shift).moveTo(BarKey.Tab, 0).addGroup("Git")
        val gestures = GestureSettings(tap = TapAction.Toggle, doubleTap = DoubleTapAction.Nothing, doubleTapMs = 250)
        val security = SecurityPrefs(lock = false, lockAfterSeconds = 900, blockScreenshots = true)
        compose.runOnUiThread {
            m.setKeyBar(true)
            m.setKeyBarCtrlC(CtrlCKey.Hold)
            m.setKeyArrangement(arrangement)
            m.setGestures(gestures)
            m.setSecurity(security)
        }
        val text = SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text

        // All of it changed back to how the app comes, and the host's other addresses reordered.
        compose.runOnUiThread {
            m.setKeyBar(false)
            m.setKeyBarCtrlC(CtrlCKey.Tap)
            m.setKeyArrangement(KeyArrangement())
            m.setGestures(GestureSettings())
            m.setSecurity(SecurityPrefs())
            m.saveHost(agents.copy(fallbacks = agents.fallbacks.reversed(), tryOthers = true))
        }
        val password = store.find("agents-id")!!.sshPassword
        compose.runOnUiThread { m.settingsBackup.readText(text) }
        compose.awaitThat { m.settingsBackup.step.value is BackupStep.Preview }
        assertTrue(seen(string(R.string.arrange_title)))
        assertTrue(seen(string(R.string.controls_group_gestures)))
        assertTrue(seen(string(R.string.security_block_screenshots)))
        compose.runOnUiThread { m.settingsBackup.apply() }
        compose.awaitThat { m.settingsBackup.step.value == null }

        assertTrue(m.keyBar.value)
        assertEquals(CtrlCKey.Hold, m.keyBarCtrlC.value)
        assertEquals(arrangement, m.keyArrangement.value)
        assertEquals(arrangement, store.keyArrangement())
        assertEquals(gestures, store.gestures())
        assertEquals(security, m.security.value)
        assertEquals(security, store.security())
        val back = store.find("agents-id")!!
        assertEquals(agents.fallbacks, back.fallbacks)
        assertFalse(back.tryOthers)
        // No secret went in, and the host keeps its own password (as this phone's keystore keeps it).
        assertFalse(text.contains("\"pw\""))
        assertEquals(password, back.sshPassword)
    }

    @Test
    fun aBackupsLockStaysOffOnAPhoneWithoutAScreenLock() {
        val m = app()
        compose.runOnUiThread { m.setSecurity(SecurityPrefs(lock = true)) }
        val text = SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text
        compose.runOnUiThread { m.setSecurity(SecurityPrefs()) }
        compose.runOnUiThread { m.settingsBackup.readText(text) }
        compose.awaitThat { m.settingsBackup.step.value is BackupStep.Preview }
        compose.runOnUiThread { m.settingsBackup.apply() }
        compose.awaitThat { m.settingsBackup.step.value == null }
        assertEquals(AppLockGate.deviceSecure(compose.activity), m.security.value.lock)
        assertFalse(m.locked.value)
    }

    @Test
    fun exportHostsSaysItHoldsSshHostsAndNamesWhoItLeftOut() {
        val m = app()
        compose.runOnUiThread {
            m.saveHost(agents.copy(id = "twin-id", host = "twin.example.com", fallbacks = emptyList()))
            m.hostImport.open()
        }
        compose.waitForIdle()
        assertTrue(seen(string(R.string.export_ssh_only)))
        compose.runOnUiThread { m.hostImport.export() }
        compose.awaitThat { seen(string(R.string.export_written, 1)) }
        // desk is on a Rex port; the second agents has the first one's name.
        assertTrue(seen(string(R.string.export_left_not_ssh, "desk")))
        assertTrue(seen(string(R.string.export_left_same_name, "agents")))
        compose.onNode(hasText(string(R.string.export_open_backup)) and hasClickAction()).performScrollTo().performClick()
        compose.awaitThat { m.settingsBackup.step.value is BackupStep.Menu }
        assertNull(m.hostImport.step.value)
    }

    /** The view model's side; the dialog it opens has text fields, so HostDialogLinkTest shows it on a device. */
    @Test
    fun aHostLinkAsksTheHomeToAddTheHostAndSavesNothing() {
        val app = freshApp()
        store = desk.saveFor(app)
        store.upsert(agents)
        val m = launchedModel(compose, app)
        model = m
        compose.runOnUiThread { m.openHostLink(HostLink.write("ssh://me@vm.example.com:2222", "build vm")) }
        assertEquals(HomeRequest.AddHost("build vm", "ssh://me@vm.example.com:2222"), m.homeRequest.value)
        assertEquals(Route.Home, m.route.value)
        assertEquals(listOf("desk", "agents"), store.list().map { it.label })
        compose.runOnUiThread { m.homeRequest.value = null }
        // A link with a password in it opens nothing.
        compose.runOnUiThread { m.openHostLink("rexpocket://add?addr=ssh%3A%2F%2Fme%3Apw%40vm.example.com") }
        compose.waitForIdle()
        assertNull(m.homeRequest.value)
        assertEquals(listOf("desk", "agents"), store.list().map { it.label })
    }

    @Test
    fun terminalControlsResetToHowTheAppComesAfterAConfirm() {
        lateinit var m: RexViewModel
        compose.runOnUiThread { m = RexViewModel(freshApp()) }
        model = m
        var page by mutableStateOf<ControlsPage?>(ControlsPage.Controls)
        compose.runOnUiThread {
            m.setGestures(GestureSettings(tap = TapAction.Nothing, longPressMs = 900))
            m.setKeyBar(true)
            m.setKeyBarCtrlC(CtrlCKey.Hold)
            m.setKeyArrangement(KeyArrangement().add(BarKey.Shift))
        }
        compose.setContent { ControlsOnModel(m, page, onPage = { page = it }, bar = false) }
        compose.waitForIdle()
        compose.onNodeWithTag(CONTROLS_RESET_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        // Nothing changes before the confirm.
        assertTrue(m.keyBar.value)
        compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
        compose.waitForIdle()
        assertEquals(GestureSettings(), m.gestures.value)
        assertFalse(m.keyBar.value)
        assertEquals(CtrlCKey.Tap, m.keyBarCtrlC.value)
        // The keys are Arrange keys' to reset.
        assertTrue(m.keyArrangement.value.has(BarKey.Shift))
    }

    @Test
    fun notificationsResetToHowTheAppComesAfterAConfirm() {
        var prefs by mutableStateOf(AlertPrefs(waiting = false, bells = true, progress = true))
        compose.setContent {
            RexTheme { NotificationsSheet(onDismiss = {}, alerts = false, onAlerts = {}, prefs = prefs, onPrefs = { prefs = it }) }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(NOTIFICATIONS_RESET_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RESET_CONFIRM_TAG).performClick()
        compose.waitForIdle()
        assertEquals(AlertPrefs(), prefs)
    }

    @Test
    fun theLockStandsInForTheAppUntilThePhoneSaysYes() {
        var locked by mutableStateOf(true)
        var asks = 0
        var answer = false
        val fake = Authenticate { onResult ->
            asks++
            onResult(if (answer) AuthResult.Confirmed else AuthResult.Cancelled)
        }
        compose.setContent {
            RexTheme {
                LockGate(locked = locked, authenticate = fake, onAsking = {}, onResult = { result -> if (result == AuthResult.Confirmed) locked = false }) {
                    androidx.compose.material3.Text("the app")
                }
            }
        }
        compose.waitForIdle()
        // The prompt opened by itself, once; a no leaves the lock up and the app out.
        assertEquals(1, asks)
        assertTrue(compose.onAllNodes(hasTestTag(LOCK_SCREEN_TAG)).fetchSemanticsNodes().isNotEmpty())
        assertFalse(seen("the app"))
        answer = true
        compose.onNodeWithTag(UNLOCK_TAG).performClick()
        compose.waitForIdle()
        assertEquals(2, asks)
        assertTrue(seen("the app"))
        assertTrue(compose.onAllNodes(hasTestTag(LOCK_SCREEN_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun securityBlocksScreenshotsAndOnlyTurnsTheLockOnOnceThePhoneConfirms() {
        var prefs by mutableStateOf(SecurityPrefs())
        var answer = false
        compose.setContent {
            RexTheme {
                SecuritySheet(prefs = prefs, onPrefs = { prefs = it }, onDismiss = {}, authenticate = { it(if (answer) AuthResult.Confirmed else AuthResult.Cancelled) }, problem = { null })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(SECURITY_SCREENSHOTS_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(prefs.blockScreenshots)
        compose.onNodeWithTag(SECURITY_LOCK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse("not confirmed, not on", prefs.lock)
        assertTrue(seen(string(R.string.security_lock_not_confirmed)))
        answer = true
        compose.onNodeWithTag(SECURITY_LOCK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(prefs.lock)
        // Off needs no prompt.
        answer = false
        compose.onNodeWithTag(SECURITY_LOCK_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(prefs.lock)
    }
}

package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.AppTheme
import me.leaver.rexpocket.core.Appearance
import me.leaver.rexpocket.core.ExportedHost
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.HostListFormat
import me.leaver.rexpocket.core.HostSecret
import me.leaver.rexpocket.core.ImportAddress
import me.leaver.rexpocket.core.QuickReplies
import me.leaver.rexpocket.core.SettingsFormat
import me.leaver.rexpocket.core.SettingsSnapshot
import me.leaver.rexpocket.core.TerminalColors
import me.leaver.rexpocket.core.TextSize
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Settings › Backup on the app's real view model and store: a backup read back puts every setting
 * back and removes no host, a bad file imports nothing and says where, and sealed SSH keys come in
 * only with their passphrase. See docs/design/settings-backup.md.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackupScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null
    private lateinit var store: HostStore

    private val fpA = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })

    private val agents = SavedHost(
        id = "agents-id", label = "agents", host = "agents.example.com", port = 22, plaintext = true,
        sshUser = "me", sshPassword = "pw", sshHostKey = fpA,
    )

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(): RexViewModel {
        val app = freshApp()
        store = desk.saveFor(app)
        store.upsert(agents)
        val m = launchedModel(compose, app)
        model = m
        compose.setContent { RexPocketApp(m) }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
        desk.close()
    }

    /** As another app's share of the text, through the activity's own order: a backup first, then a host list. */
    private fun share(m: RexViewModel, text: String) = compose.runOnUiThread {
        val request = ShareRequest(text, emptyList())
        val toHosts = { if (!m.hostImport.offerShare(request, "text/plain", m::offerShare)) m.offerShare(request) }
        if (!m.settingsBackup.offerShare(request, "text/plain", toHosts)) toHosts()
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aBackupReadBackPutsEverySettingBackAndRemovesNoHost() {
        val m = start()
        val dark = Appearance(AppTheme.Dark, TerminalColors.Light, TextSize.Larger)
        compose.runOnUiThread {
            m.setAppearance(dark)
            m.setAlertPrefs(AlertPrefs(bells = true, done = false))
            m.setHomeFilter(HomeFilter.Running)
            m.setHostBackground("agents-id", HostBackground(stay = false, notify = false))
            m.setQuickReplies(QuickReplies(listOf("ship it", "/compact")))
        }
        val text = SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text
        assertFalse("no credential without a passphrase", text.contains("secrets") || text.contains("\"pw\""))
        assertEquals(listOf("desk", "agents"), JSONObject(text).getJSONObject("hosts").getJSONArray("hosts").let { a -> List(a.length()) { a.getJSONObject(it).getString("name") } })

        // Everything put back as it was, and a host added that the file does not have.
        val extra = SavedHost(id = "extra-id", label = "extra", host = "10.0.0.9", port = 7297, plaintext = true)
        compose.runOnUiThread {
            m.setAppearance(Appearance())
            m.setAlertPrefs(AlertPrefs())
            m.setHomeFilter(HomeFilter.All)
            m.setHostBackground("agents-id", HostBackground())
            m.setQuickReplies(QuickReplies())
            m.saveHost(extra)
        }
        share(m, text)
        compose.awaitThat { seen(string(R.string.backup_preview_title)) }
        assertTrue(seen(string(R.string.import_counts, 0, 0, 2)))
        assertTrue(seen(string(R.string.backup_setting_host_background, "agents")))
        assertTrue(seen(string(R.string.appearance_app)))
        assertTrue(seen(string(R.string.backup_setting_filter)))
        assertTrue(seen(string(R.string.quick_replies_title)))
        // Nothing changes before the confirm.
        assertEquals(Appearance(), store.appearance())

        tap(string(R.string.backup_apply))
        compose.awaitThat { m.settingsBackup.step.value == null }
        assertEquals(dark, m.appearance.value)
        assertEquals(dark, store.appearance())
        assertEquals(AlertPrefs(bells = true, done = false), store.alertPrefs())
        assertEquals(HomeFilter.Running, m.homeFilter.value)
        assertEquals(listOf("ship it", "/compact"), m.quickReplies.value.items)
        assertEquals(HostBackground(stay = false, notify = false), m.background.value.of("agents-id"))
        // No host removed; the one the file does not name keeps its place.
        assertEquals(listOf("desk", "agents", "extra"), store.list().map { it.label })
        assertEquals(fpA, store.find("agents-id")!!.sshHostKey)
        assertNull(m.hostImport.step.value)
    }

    @Test
    fun aBadBackupImportsNothingAndSaysWhere() {
        val m = start()
        val root = JSONObject(SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text)
        root.getJSONObject("appearance").put("app", "purple")
        root.getJSONObject("hosts").put("note", "a host list inside")
        share(m, root.toString())
        compose.awaitThat { seen(string(R.string.backup_refused), substring = true) }
        assertTrue(seen(string(R.string.backup_problem_value, "appearance.app"), substring = true))
        assertTrue(m.settingsBackup.step.value is BackupStep.Menu)
        assertEquals(Appearance(), store.appearance())
        assertEquals(listOf("desk", "agents"), store.list().map { it.label })
        // A settings file never opens Import hosts, though it holds a host list.
        assertNull(m.hostImport.step.value)
    }

    @Test
    fun aPlainHostListStillGoesToImportHosts() {
        val m = start()
        val list = HostListFormat.write(listOf(ExportedHost("fresh", ImportAddress("me", "fresh.example.com", 22, true), emptyList(), null, null))).text
        share(m, list)
        compose.awaitThat { m.hostImport.step.value is HostImportStep.Preview }
        assertNull(m.settingsBackup.step.value)
    }

    @Test
    fun sealedKeysComeInOnlyWithTheirPassphrase() {
        val m = start()
        val fresh = ExportedHost("fresh", ImportAddress("me", "fresh.example.com", 22, true), emptyList(), null, null)
        val text = SettingsFormat.write(
            SettingsSnapshot(hosts = listOf(fresh), secrets = listOf(HostSecret("fresh", "pw2", null, null))),
            passphrase = "correct horse battery",
        ).text
        share(m, text)
        compose.awaitThat { seen(string(R.string.backup_locked, 1)) }
        assertTrue(seen(string(R.string.import_new_key, "rexpocket:fresh")))

        compose.runOnUiThread { m.settingsBackup.unlock("correct horse") }
        compose.awaitThat { (m.settingsBackup.step.value as? BackupStep.Preview)?.secrets.let { it is BackupSecrets.Locked && it.wrong } }
        assertTrue(seen(string(R.string.backup_wrong_passphrase)))

        compose.runOnUiThread { m.settingsBackup.unlock("correct horse battery") }
        compose.awaitThat { (m.settingsBackup.step.value as? BackupStep.Preview)?.secrets is BackupSecrets.Opened }
        compose.awaitThat { seen(string(R.string.backup_host_signs_in)) }
        assertFalse(seen(string(R.string.import_new_key, "rexpocket:fresh")))

        tap(string(R.string.backup_apply))
        compose.awaitThat { m.settingsBackup.step.value == null }
        val saved = store.list().single { it.label == "fresh" }
        // The backup's password, not a fresh key: no keys page. (A JVM without a keystore keeps no password.)
        assertEquals("", saved.sshPrivateKey)
        assertTrue(saved.sshPassword == "pw2" || saved.sshPassword.isEmpty())
        assertNull(m.hostImport.step.value)
    }
}

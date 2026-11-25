package me.leaver.rexpocket.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.TileStore
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.CtrlCKey
import me.leaver.rexpocket.core.DoubleTapAction
import me.leaver.rexpocket.core.GestureSettings
import me.leaver.rexpocket.core.LaunchTemplate
import me.leaver.rexpocket.core.LongPressAction
import me.leaver.rexpocket.core.SecurityPrefs
import me.leaver.rexpocket.core.SettingsFormat
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetUse
import me.leaver.rexpocket.core.TapAction
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.data.TailnetPolicyStore
import me.leaver.rexpocket.data.TailnetProfileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole backup, as a person uses it to move to a new phone: everything set, exported, the app's
 * data wiped, and the file imported through the preview and its one confirm. Each part the app
 * gained since the first backups has to come back: a host's other addresses, Try other addresses
 * and tailnet account; the text size, key bar and gestures; tiles; tailnet accounts and their
 * choices; templates, muted tabs and security.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun seen(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
    }

    private val box = SavedHost(
        id = "box-id", label = "box", host = "box.example.com", port = 7297, plaintext = true,
        fallbacks = listOf("ws://10.0.0.5:7297"), tryOthers = false,
    )
    private val template = LaunchTemplate("t1", "Logs", directory = "~/logs", command = "tail -f app.log")
    private val gestures = GestureSettings(tap = TapAction.Toggle, doubleTap = DoubleTapAction.Keyboard, longPress = LongPressAction.FullScreen, waitForDoubleTap = true)
    private val workPolicy = TailnetPolicy(TailnetUse.WhenNeeded, lingerMs = 60_000L, keep = BackgroundKeep.Off, notify = false)

    @Test
    fun everyPartComesBackOnAWipedPhone() {
        val app = compose.activity.application
        val m1 = tailnetModel(compose, FakeTailnetNode().apply { state.value = TailnetSamples.connected() }, listOf(TailnetSamples.macHost, box))
        model = m1
        val shown = androidx.compose.runtime.mutableStateOf(m1)
        compose.setContent { androidx.compose.runtime.key(shown.value) { RexPocketApp(shown.value) } }
        compose.runOnUiThread { m1.addTailnetAccount("Work") }
        val work = TailnetProfileStore(app).read().named(TailnetBackend.Tailscale, "Work")!!.id
        compose.runOnUiThread {
            m1.saveHost(TailnetSamples.macHost.copy(tailnetProfile = work))
            m1.setTailnetPolicy(work, workPolicy)
            m1.setFont(1.3f)
            m1.setKeyBar(true)
            m1.setKeyBarCtrlC(CtrlCKey.Hold)
            m1.setGestures(gestures)
            m1.setSecurity(SecurityPrefs(lockAfterSeconds = 300, blockScreenshots = true))
            m1.setLaunchTemplates(box.id, listOf(template))
            m1.muteTab(box.id, "@7", true)
            TileStore.assign(app, 2, Connectable.host(box.id))
        }
        compose.waitForIdle()
        val text = SettingsFormat.write(m1.settingsBackup.snapshot(withSecrets = false)).text
        compose.runOnUiThread { m1.disconnectAll() }

        // A new phone: none of the app's data is left.
        for (name in listOf("rexpocket", "launch_templates", "home_marks")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val m = tailnetModel(compose, FakeTailnetNode().apply { state.value = TailnetSamples.connected() }, emptyList())
        model = m
        compose.runOnUiThread { shown.value = m }
        compose.waitForIdle()
        assertTrue(TailnetProfileStore(app).read().named(TailnetBackend.Tailscale, "Work") == null)

        compose.runOnUiThread { m.settingsBackup.offerShare(ShareRequest(text, emptyList()), "text/plain") {} }
        compose.awaitThat { seen(string(R.string.backup_preview_title)) }
        assertTrue(seen(string(R.string.import_counts, 2, 0, 0)))
        assertTrue(seen(string(R.string.backup_setting_tailnet, "Tailscale · Work")))
        assertTrue(seen(string(R.string.backup_setting_tile, "2")))
        assertTrue(seen(string(R.string.template_manage_title, "box")))
        compose.onNodeWithText(string(R.string.backup_apply)).performScrollTo().performClick()
        compose.awaitThat { m.settingsBackup.step.value == null }

        val hosts = m.hosts.value.associateBy { it.label }
        val newBox = hosts.getValue("box")
        val mac = hosts.getValue("mac")
        assertEquals(listOf("ws://10.0.0.5:7297"), newBox.fallbacks)
        assertEquals(false, newBox.tryOthers)
        val newWork = TailnetProfileStore(app).read().named(TailnetBackend.Tailscale, "Work")
        assertTrue("the Work account came back", newWork != null)
        assertEquals(workPolicy, TailnetPolicyStore(app).read().of(newWork!!.id))
        assertEquals("the mac host is on the Work account again", newWork.id, mac.tailnetProfile)
        assertEquals(1.3f, m.fontScale.value, 0.001f)
        assertEquals(true, m.keyBar.value)
        assertEquals(CtrlCKey.Hold, m.keyBarCtrlC.value)
        assertEquals(gestures, m.gestures.value)
        assertEquals(SecurityPrefs(lockAfterSeconds = 300, blockScreenshots = true), m.security.value)
        assertEquals(listOf(Triple("Logs", "~/logs", "tail -f app.log")), m.launchTemplates.value[newBox.id].orEmpty().map { Triple(it.name, it.directory, it.command) })
        assertEquals(setOf("@7"), m.background.value.of(newBox.id).muted)
        assertEquals(Connectable.host(newBox.id), TileStore(app).of(2))
    }

    /**
     * A backup made before tabs could be muted has no `mutedTabs`: importing it on a phone that
     * muted a tab since changes nothing, and the tab stays muted.
     */
    @Test
    fun anOldBackupLeavesTheMutedTabsAlone() {
        val app = compose.activity.application
        val m = tailnetModel(compose, FakeTailnetNode(), listOf(box), joined = false)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.runOnUiThread {
            m.setHostBackground(box.id, me.leaver.rexpocket.core.HostBackground(stay = false))
            m.muteTab(box.id, "@7", true)
        }
        val root = org.json.JSONObject(SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text)
        val entries = root.getJSONObject("background").getJSONArray("hosts")
        for (i in 0 until entries.length()) entries.getJSONObject(i).remove("mutedTabs")
        compose.runOnUiThread { m.settingsBackup.offerShare(ShareRequest(root.toString(), emptyList()), "text/plain") {} }
        compose.awaitThat { seen(string(R.string.backup_preview_title)) }
        assertTrue("the preview shows no change", seen(string(R.string.backup_settings_count, 0)))
        compose.runOnUiThread { m.settingsBackup.apply() }
        compose.awaitThat { m.settingsBackup.step.value == null }
        assertEquals(setOf("@7"), m.background.value.of(box.id).muted)
        assertEquals(false, m.background.value.of(box.id).stay)
        assertTrue(app != null)
    }

    /**
     * Two hosts of one name: the backup keeps the first, and with it the first's own choices,
     * templates and tile - never the second's under the first's name.
     */
    @Test
    fun twoHostsOfOneNameBackUpTheFirstsOwnChoices() {
        val app = compose.activity.application
        val other = box.copy(id = "box-2", host = "box2.example.com", fallbacks = emptyList(), tryOthers = true)
        val m1 = tailnetModel(compose, FakeTailnetNode(), listOf(box, other), joined = false)
        model = m1
        val shown = androidx.compose.runtime.mutableStateOf(m1)
        compose.setContent { androidx.compose.runtime.key(shown.value) { RexPocketApp(shown.value) } }
        compose.runOnUiThread {
            m1.setHostBackground(box.id, me.leaver.rexpocket.core.HostBackground(stay = false))
            m1.setHostBackground(other.id, me.leaver.rexpocket.core.HostBackground(notify = false))
            m1.setLaunchTemplates(box.id, listOf(template))
            m1.setLaunchTemplates(other.id, listOf(LaunchTemplate("t2", "Other", command = "htop")))
            TileStore.assign(app, 1, Connectable.host(other.id))
        }
        val text = SettingsFormat.write(m1.settingsBackup.snapshot(withSecrets = false)).text
        compose.runOnUiThread { m1.disconnectAll() }
        for (name in listOf("rexpocket", "launch_templates", "home_marks")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val m = tailnetModel(compose, FakeTailnetNode(), emptyList(), joined = false)
        model = m
        compose.runOnUiThread { shown.value = m }
        compose.runOnUiThread { m.settingsBackup.offerShare(ShareRequest(text, emptyList()), "text/plain") {} }
        compose.awaitThat { seen(string(R.string.backup_preview_title)) }
        compose.runOnUiThread { m.settingsBackup.apply() }
        compose.awaitThat { m.settingsBackup.step.value == null }
        val restored = m.hosts.value.single()
        assertEquals("box.example.com", restored.host)
        assertEquals(me.leaver.rexpocket.core.HostBackground(stay = false), m.background.value.of(restored.id))
        assertEquals(listOf("Logs"), m.launchTemplates.value[restored.id].orEmpty().map { it.name })
        assertEquals("the second box's tile is not the first's", null, TileStore(app).of(1))
    }
}

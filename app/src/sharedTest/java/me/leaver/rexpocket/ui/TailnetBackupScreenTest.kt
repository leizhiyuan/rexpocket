package me.leaver.rexpocket.ui

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
import me.leaver.rexpocket.core.SettingsFormat
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.core.TailnetUse
import me.leaver.rexpocket.data.TailnetPolicyStore
import me.leaver.rexpocket.data.TailnetProfileStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings backup carries the tiles and each tailnet account's choices: written out, put back
 * onto a phone that lost them through the preview and its one confirm, an account the phone lacks
 * added by name with no sign-in. See docs/design/settings-backup.md.
 */
@RunWith(AndroidJUnit4::class)
class TailnetBackupScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = FakeTailnetNode()
    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnectAll() } }
    }

    private fun seen(text: String): Boolean = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun tilesAndTailnetChoicesRoundTripThroughTheBackup() {
        node.state.value = TailnetSamples.connected()
        val m = tailnetModel(compose, node, listOf(TailnetSamples.macHost), open = null)
        model = m
        compose.setContent { RexPocketApp(m) }
        val app = compose.activity.application
        val chosen = TailnetPolicy(TailnetUse.WhenNeeded, lingerMs = 15 * 60_000L, keep = BackgroundKeep.Always, notify = false)
        compose.runOnUiThread {
            m.setTailnetPolicy("tailscale", chosen)
            TileStore.assign(app, 1, Connectable.tailnet("tailscale"))
            TileStore.assign(app, 3, Connectable.host(TailnetSamples.macHost.id))
        }
        val root = JSONObject(SettingsFormat.write(m.settingsBackup.snapshot(withSecrets = false)).text)
        // Another account's choices, as a phone with a Work account would have written them.
        root.getJSONObject("tailnets").getJSONArray("accounts").put(
            JSONObject().put("backend", "tailscale").put("name", "Work").put("first", false).put("use", "open").put("keep", "off"),
        )
        // The phone forgets them.
        compose.runOnUiThread {
            m.setTailnetPolicy("tailscale", TailnetPolicy())
            TileStore.assign(app, 1, null)
            TileStore.assign(app, 3, null)
        }
        compose.runOnUiThread { m.settingsBackup.offerShare(ShareRequest(root.toString(), emptyList()), "text/plain") {} }
        compose.awaitThat { seen(string(R.string.backup_preview_title)) }
        assertTrue(seen(string(R.string.backup_setting_tailnet, "Tailscale")))
        assertTrue(seen(string(R.string.backup_setting_tailnet, "Tailscale · Work")))
        assertTrue(seen(string(R.string.backup_setting_tile, "3")))
        // Nothing changes before the confirm.
        assertNull(TileStore(app).of(1))
        compose.onNodeWithText(string(R.string.backup_apply)).performScrollTo().performClick()
        compose.awaitThat { m.settingsBackup.step.value == null }
        assertEquals(Connectable.tailnet("tailscale"), TileStore(app).of(1))
        assertEquals(Connectable.host(TailnetSamples.macHost.id), TileStore(app).of(3))
        assertEquals(chosen, TailnetPolicyStore(app).read().of("tailscale"))
        val work = TailnetProfileStore(app).read().named(TailnetBackend.Tailscale, "Work")
        assertTrue("Work was added", work != null)
        assertEquals(TailnetPolicy(keep = BackgroundKeep.Off), TailnetPolicyStore(app).read().of(work!!.id))
        // Added, not run: the account in use is still the first.
        assertEquals("tailscale", m.tailnetView.value.account)
    }
}

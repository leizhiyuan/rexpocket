package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Import hosts on the app's real view model and store: a host list shared to the app opens on its
 * preview, one confirm imports it, a pinned key that would change waits for its own confirmation,
 * a bad list imports nothing, and the connected Rex's host list goes the same way. After an import
 * the new hosts' public keys are on one screen. See docs/design/host-import.md.
 */
@RunWith(AndroidJUnit4::class)
class HostImportScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private var model: RexViewModel? = null
    private lateinit var store: HostStore

    private val fpA = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val fpB = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })

    /** An SSH host the phone had already, its key pinned. */
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

    private fun entry(name: String, dns: String?, uri: String?, fingerprint: String?) = JSONObject().put("name", name)
        .put("dnsUri", dns ?: JSONObject.NULL).put("uri", uri ?: JSONObject.NULL)
        .put("hostKeyFingerprint", fingerprint ?: JSONObject.NULL).put("keyComment", "provision:owner-phone")

    private fun list(vararg entries: JSONObject) =
        JSONObject().put("format", "rexpocket-hosts/1").put("hosts", JSONArray(entries.toList())).toString()

    /** As another app's share of the text does it, through the view model's own path. */
    private fun share(m: RexViewModel, text: String) = compose.runOnUiThread {
        val request = ShareRequest(text, emptyList())
        if (!m.hostImport.offerShare(request, "text/plain", m::offerShare)) m.offerShare(request)
    }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aSharedHostListIsPreviewedThenImportedWithEachNewHostsKeyPinned() {
        val m = start()
        share(
            m,
            list(
                entry("agents", "ssh://me@agents.example.com:22", null, fpB),
                entry("fresh-1", "ssh://me@fresh-1.example.com:22", "ssh://me@100.92.1.2:22", fpA),
                entry("fresh-2", "ssh://me@fresh-2.example.com:2222", null, null),
            ),
        )
        compose.awaitThat { seen(string(R.string.import_preview_title)) }
        assertTrue(seen(string(R.string.import_counts, 2, 1, 0)))
        assertTrue(seen(string(R.string.import_key_conflict)))
        assertTrue(seen(string(R.string.import_key_confirm, "agents")))
        assertTrue(seen(string(R.string.import_key_tofu)))
        // Nothing changes before the confirm. (A JVM without a keystore keeps no password: compare with what the store holds.)
        val before = store.listWithSecrets()
        assertEquals(listOf("desk", "agents"), store.list().map { it.label })

        // The key change is not confirmed: agents stays as it was.
        tap(string(R.string.import_apply))
        compose.awaitThat { m.hostImport.step.value is HostImportStep.Keys }
        compose.awaitThat { seen(string(R.string.keys_title)) }
        val hosts = store.listWithSecrets()
        assertEquals(listOf("desk", "agents", "fresh-1", "fresh-2"), hosts.map { it.label })
        assertEquals(before[1], hosts[1])
        assertEquals(fpA, hosts[1].sshHostKey)
        assertEquals(desk.host, hosts[0])
        val fresh1 = hosts[2]
        assertEquals(fpA, fresh1.sshHostKey)
        assertEquals(listOf("ssh://me@100.92.1.2:22"), fresh1.fallbacks)
        assertEquals("me", fresh1.sshUser)
        assertEquals("", hosts[3].sshHostKey)
        assertEquals(2222, hosts[3].port)

        val keys = (m.hostImport.step.value as HostImportStep.Keys).entries
        assertEquals(listOf("fresh-1", "fresh-2"), keys.map { it.name })
        assertNotEquals(keys[0].publicKey, keys[1].publicKey)
        assertTrue(keys.all { it.publicKey.startsWith("ssh-ed25519 ") && it.publicKey.endsWith(" provision:owner-phone") })
        assertTrue(seen(keys[0].publicKey))
        assertTrue(seen(string(R.string.keys_copy_all)))
        assertTrue(seen(string(R.string.keys_share_json)))
        // Each new host has its own private key, when this phone's keystore can seal one.
        if (hosts[2].sshPrivateKey.isNotEmpty()) {
            assertNotEquals(hosts[2].sshPrivateKey, hosts[3].sshPrivateKey)
            assertTrue(hosts[2].sshPrivateKey.contains("OPENSSH PRIVATE KEY"))
        } else {
            assertTrue(seen(string(R.string.keys_unsaved, "fresh-1, fresh-2")))
        }
        tap(string(R.string.keys_done))
        compose.awaitThat { m.hostImport.step.value == null }
    }

    @Test
    fun aKeyChangeConfirmedForItsHostReplacesThePin() {
        val m = start()
        share(m, list(entry("agents", "ssh://me@agents.example.com:22", null, fpB)))
        compose.awaitThat { seen(string(R.string.import_key_confirm, "agents")) }
        assertTrue(seen(string(R.string.import_nothing)))
        tap(string(R.string.import_key_confirm, "agents"))
        compose.awaitThat { seen(string(R.string.import_apply)) }
        val password = store.withSecrets(store.find("agents-id")!!).sshPassword
        tap(string(R.string.import_apply))
        // A host with a password gets no new key: nothing to show, and the sheet goes. (A JVM without
        // a keystore kept no password, so there the host has nothing to sign in with and gets a key.)
        compose.awaitThat { m.hostImport.step.value.let { if (password.isEmpty()) it is HostImportStep.Keys else it == null } }
        val saved = store.withSecrets(store.find("agents-id")!!)
        assertEquals(fpB, saved.sshHostKey)
        assertEquals(password, saved.sshPassword)
    }

    @Test
    fun aListWithOneBadEntryImportsNothingAndSaysWhich() {
        val m = start()
        share(m, list(entry("ok-1", "ssh://me@ok.example.com", null, null), entry("bad", "ssh://me:secret@bad.example.com", null, null)))
        compose.awaitThat { seen(string(R.string.import_refused), substring = true) }
        assertTrue(seen(string(R.string.import_entry_named, 2, "bad", string(R.string.import_problem_password, "dnsUri")), substring = true))
        assertTrue(m.hostImport.step.value is HostImportStep.Source)
        assertEquals(listOf("desk", "agents"), store.list().map { it.label })
    }

    @Test
    fun theConnectedRexsHostsGoThroughTheSamePreview() {
        val m = start()
        desk.rex.putHost("studio", "ssh://me@192.0.2.10:2222", "unix:///tmp/rex.sock")
        desk.rex.putHost("sockets", "unix:///tmp/rex.sock")
        compose.runOnUiThread { m.connect(desk.host) }
        compose.awaitThat { m.lives.value[desk.host.id]?.state?.phase == me.leaver.rexpocket.core.Phase.Live }
        compose.runOnUiThread { m.hostImport.open() }
        compose.awaitThat { seen(string(R.string.import_from_rex, "desk")) }
        tap(string(R.string.import_from_rex, "desk"))
        compose.awaitThat { seen(string(R.string.import_preview_title)) }
        assertTrue(seen(string(R.string.import_skipped, "sockets")))
        assertTrue(seen(string(R.string.import_key_tofu)))
        tap(string(R.string.import_apply))
        compose.awaitThat { m.hostImport.step.value is HostImportStep.Keys }
        val studio = store.list().single { it.label == "studio" }
        assertEquals("192.0.2.10", studio.host)
        assertEquals(2222, studio.port)
        assertEquals("me", studio.sshUser)
        assertEquals("", studio.sshHostKey)
        assertNull(store.list().firstOrNull { it.label == "sockets" })
        assertEquals(listOf("studio"), (m.hostImport.step.value as HostImportStep.Keys).entries.map { it.name })
    }
}

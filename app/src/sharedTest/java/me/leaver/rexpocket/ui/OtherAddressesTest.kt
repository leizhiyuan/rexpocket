package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.CheckRoute
import me.leaver.rexpocket.core.ScriptedRex
import me.leaver.rexpocket.core.SshKeys
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

/**
 * The host dialog's Other addresses (the list, its order, Try other addresses, an address that is
 * never dialed), Test connection on each address, and Generate key and Show public key. The parts
 * are driven on their own: a text field in a dialog never idles on Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class OtherAddressesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rex = ScriptedRex()

    @After
    fun stop() {
        scope.cancel()
        rex.close()
    }

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun seen(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private val host = SavedHost(id = "x", label = "mac", host = "mac.local", port = 7297, plaintext = true)

    private class Kept(var list: List<String>, var tryOthers: Boolean)

    private fun editor(start: SavedHost): Kept {
        val kept = Kept(start.fallbacks, start.tryOthers)
        compose.setContent {
            RexTheme {
                var list by remember { mutableStateOf(start.fallbacks) }
                var on by remember { mutableStateOf(start.tryOthers) }
                var adding by remember { mutableStateOf("") }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OtherAddressesEditor(
                        fallbacks = list,
                        tryOthers = on,
                        main = start.mainRoute(),
                        sshUser = start.sshUser,
                        onChange = { list = it; kept.list = it },
                        onTryOthers = { on = it; kept.tryOthers = it },
                        adding = adding,
                        onAdding = { adding = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        return kept
    }

    @Test
    fun addressesAreAddedMovedAndRemovedInTheOrderShown() {
        val kept = editor(host.copy(fallbacks = listOf("ws://192.168.1.20:7297")))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(OTHER_ADDRESSES_TAG))).performScrollTo().performTextInput("100.92.1.2:7297")
        compose.onNodeWithTag(ADD_ADDRESS_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("ws://192.168.1.20:7297", "ws://100.92.1.2:7297"), kept.list)
        assertTrue(seen("2. ws://100.92.1.2:7297"))
        // Move up on the second (a 48 dp button), then the row's own TalkBack action.
        compose.onAllNodesWithContentDescription(string(R.string.other_address_up))[1].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("ws://100.92.1.2:7297", "ws://192.168.1.20:7297"), kept.list)
        assertTrue(seen("1. ws://100.92.1.2:7297"))
        compose.onAllNodesWithContentDescription(string(R.string.other_address_remove)).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("ws://192.168.1.20:7297"), kept.list)
        assertFalse(seen("ws://100.92.1.2:7297", substring = true))
    }

    @Test
    fun tryOtherAddressesIsASwitchThatIsOnUntilTurnedOff() {
        val kept = editor(host.copy(fallbacks = listOf("ws://192.168.1.20:7297")))
        compose.onNodeWithTag(TRY_OTHERS_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(kept.tryOthers)
    }

    @Test
    fun anAddressANeverDialedSaysWhy() {
        val ssh = host.copy(host = "mac.local", port = 22, sshUser = "me", sshPassword = "pw", fallbacks = listOf("ws://192.168.1.20:7297", "ssh://192.168.1.20"))
        editor(ssh)
        assertTrue(seen(string(R.string.other_address_other_kind)))
        assertTrue(seen(string(R.string.other_addresses_hint_ssh)))
    }

    @Test
    fun testConnectionChecksEveryAddressInTurn() {
        val tester = HostTester(scope) { CheckRoute() }
        val closed = ServerSocket(0).use { it.localPort }
        val target = host.copy(host = "127.0.0.1", port = closed, fallbacks = listOf("ws://127.0.0.1:${rex.endpoint.port}"))
        compose.setContent {
            RexTheme {
                val others by tester.others.collectAsState()
                OtherChecks(others, mainKey = null)
            }
        }
        tester.start(target.checkTarget(), target.otherCheckTargets())
        compose.awaitThat { tester.others.value.singleOrNull()?.second?.finished == true }
        assertTrue(tester.report.value?.passed == false)
        compose.awaitThat { seen("ws://127.0.0.1:${rex.endpoint.port}: " + string(R.string.check_other_works)) }
    }

    @Test
    fun generateKeyShowsThePublicHalfInTheImportForm() {
        var key = ""
        compose.setContent {
            RexTheme {
                var current by remember { mutableStateOf("") }
                Column { SshKeyActions(hostName = "mac", privateKey = current, passphrase = "", onGenerated = { current = it; key = it }) }
            }
        }
        compose.onNodeWithTag(GENERATE_KEY_TAG).performClick()
        compose.waitForIdle()
        assertTrue(key.contains("OPENSSH PRIVATE KEY"))
        val line = SshKeys.publicKeyOf(key)!!
        assertTrue(seen("# mac\n$line"))
        assertFalse("the private key is never shown", seen("PRIVATE KEY", substring = true))
        compose.onNodeWithText(string(R.string.keys_done)).performClick()
        compose.waitForIdle()

        // Generate again: it asks before the key is replaced, and Cancel keeps it.
        compose.onNodeWithTag(GENERATE_KEY_TAG).performClick()
        compose.waitForIdle()
        assertTrue(seen(string(R.string.ssh_key_replace_title)))
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.waitForIdle()
        val first = key
        assertEquals(first, key)
        compose.onNodeWithTag(GENERATE_KEY_TAG).performClick()
        compose.onNodeWithText(string(R.string.ssh_key_replace)).performClick()
        compose.waitForIdle()
        assertTrue(first != key)

        // Show public key: the same line as the key's, read back.
        compose.onNodeWithText(string(R.string.keys_done)).performClick()
        compose.onNodeWithTag(SHOW_PUBLIC_KEY_TAG).performClick()
        compose.waitForIdle()
        assertTrue(seen("# mac\n" + SshKeys.publicKeyOf(key)))
    }
}

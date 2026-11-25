package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.data.SavedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The host dialog's gaps that were found in use: a name another host has is warned about, an
 * other address typed and not added is added by Save (or holds Save back while it is not an
 * address), a pinned host key can be forgotten, and a host from a link says to check it. The
 * dialog's fields never idle on Robolectric, so this runs on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class HostDialogQolTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val saved = mutableListOf<SavedHost>()
    private val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })

    @Before
    fun onADevice() = assumeFalse("a text field in a dialog never idles on Robolectric", onRobolectric)

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun field(hint: Int) = compose.onNode(hasSetTextAction() and hasContentDescription(string(hint)))

    private fun type(hint: Int, text: String) {
        field(hint).performScrollTo().performClick().performTextInput(text)
        compose.waitForIdle()
    }

    private fun save() {
        compose.onNodeWithText(string(R.string.action_save)).performClick()
        compose.waitForIdle()
    }

    private fun dialog(initial: SavedHost? = null, otherLabels: List<String> = emptyList(), prefillLabel: String? = null, prefillAddress: String? = null, fromLink: Boolean = false) {
        compose.setContent {
            RexTheme {
                HostDialog(
                    initial = initial, tailnetEnabled = false, prefillLabel = prefillLabel, prefillAddress = prefillAddress,
                    onDismiss = {}, onDelete = null, onSave = { saved += it }, onJoinTailnet = {},
                    fromLink = fromLink, otherLabels = otherLabels,
                )
            }
        }
        compose.waitForIdle()
    }

    /** Password and Private key are one choice: the one in use is said as selected. */
    @Test
    fun theSignInChoiceIsSaidAsSelected() {
        dialog(prefillAddress = "ssh://me@desk.local:22")
        val selected = androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true)
        fun choice(id: Int) = compose.onNode(androidx.compose.ui.test.hasText(string(id)) and androidx.compose.ui.test.hasClickAction())
        choice(R.string.ssh_auth_password).assert(selected)
        choice(R.string.ssh_auth_key).assert(!selected).performClick()
        compose.waitForIdle()
        choice(R.string.ssh_auth_key).assert(selected)
        choice(R.string.ssh_auth_password).assert(!selected)
    }

    @Test
    fun aNameAnotherHostHasIsWarnedAboutButNotRefused() {
        dialog(otherLabels = listOf("Desk"))
        type(R.string.field_address, "desk.local:7297")
        type(R.string.field_name, "desk")
        compose.onNodeWithTag(HOST_NAME_TAKEN_TAG).assertExists()
        save()
        assertEquals("desk", saved.single().label)
    }

    @Test
    fun anOtherAddressTypedAndNotAddedIsAddedBySave() {
        dialog()
        type(R.string.field_address, "desk.local:7297")
        type(R.string.other_address_field, "10.0.0.2:7297")
        save()
        val host = saved.single()
        assertEquals(1, host.fallbacks.size)
        assertTrue(host.fallbacks.single(), host.fallbacks.single().contains("10.0.0.2"))
    }

    @Test
    fun anOtherAddressThatIsNotOneHoldsSaveBack() {
        dialog()
        type(R.string.field_address, "desk.local:7297")
        type(R.string.other_address_field, "ssh://")
        compose.onNodeWithTag(PENDING_ADDRESS_TAG).assertExists()
        save()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPinnedHostKeyCanBeForgottenAndSaveWritesNone() {
        val host = SavedHost(id = "x", label = "vm", host = "vm.example.com", port = 22, plaintext = true, sshUser = "me", sshPassword = "pw", sshHostKey = fp)
        dialog(initial = host)
        compose.onNodeWithTag(FORGET_HOST_KEY_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        // Nothing is forgotten before the confirm.
        assertTrue(compose.onAllNodes(hasText(string(R.string.ssh_host_key_trusted, fp))).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag(FORGET_HOST_KEY_CONFIRM_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.ssh_host_key_forgotten)).assertExists()
        save()
        assertEquals("", saved.single().sshHostKey)
        assertEquals("pw", saved.single().sshPassword)
    }

    @Test
    fun aHostFromALinkSaysToCheckItAndSavesOnlyOnSave() {
        dialog(prefillLabel = "build vm", prefillAddress = "desk.local:7297", fromLink = true)
        compose.onNodeWithTag(HOST_LINK_NOTE_TAG).assertExists()
        assertTrue(saved.isEmpty())
        save()
        assertEquals("build vm", saved.single().label)
        assertEquals("desk.local", saved.single().host)
    }
}

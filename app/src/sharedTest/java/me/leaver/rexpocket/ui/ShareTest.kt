package me.leaver.rexpocket.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Sharing into the app: the share waits on its sheet, which names the pane it would go to - the last
 * one the phone showed - until Paste or Send is tapped; then the text is pasted (with no Enter) or
 * the files uploaded through the pill, and the pane is shown. The whole app on its real view model,
 * started by the share and connecting to ScriptedRex while the sheet is up. On the JVM and the
 * emulator.
 */
@RunWith(AndroidJUnit4::class)
class ShareTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun sheetRow(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)))

    /** The second tab's pane of the second session, which the desk made. */
    private fun deskPane(session: String): String = kotlinx.coroutines.runBlocking {
        me.leaver.rexpocket.core.Desk(desk.rex.endpoint).use { d ->
            d.connect()
            d.attach(session)
            d.view(session).windows.last().blocks.first().id
        }
    }

    /**
     * The app as a share starts it: nothing running yet, [request] handed in before the screen is
     * up, the last host saved and, with [remembered], the pane the phone showed last.
     */
    private fun startWith(request: ShareRequest, remembered: Pair<String, String>?): RexViewModel {
        val app = freshApp()
        val store: HostStore = desk.saveFor(app, lastSession = remembered?.first)
        if (remembered != null) store.setLastPane(desk.host.id, remembered.first, remembered.second, desk.ids)
        val m = launchedModel(compose, app)
        model = m
        compose.runOnUiThread { m.offerShare(request) }
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, desk.ids[1])
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    @Test
    fun sharedTextIsPastedIntoTheLastPaneOnlyAfterTheTap() {
        val session = desk.ids[1]
        val pane = deskPane(session)
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "make deploy")
        val request = readShare(compose.activity, intent)!!
        assertEquals("make deploy", request.text)
        val m = startWith(request, session to pane)

        // The sheet names the pane, and nothing went to it on its own.
        val label = m.pocket.value.views.getValue(session).label
        compose.onNodeWithText(string(R.string.share_to, "desk · $label · ${string(R.string.tab_number, 2)}")).assertExists()
        compose.assertStaysHome(m, 500)
        assertTrue("pasted before the tap: ${desk.rex.pastes}", desk.rex.pastes.isEmpty())

        sheetRow(string(R.string.share_paste)).assertIsEnabled().performClick()
        compose.awaitThat { desk.rex.pastes.isNotEmpty() && m.route.value == Route.Term }
        assertEquals(listOf(pane to "make deploy"), desk.rex.pastes.toList())
        assertEquals(pane, m.pocket.value.view?.focusedBlock()?.id)
        assertTrue("typed into the pane as well", desk.rex.calls.none { it == "com.superlogical.terminal.write $pane" })
    }

    @Test
    fun withNoRememberedPaneThePickerChoosesWhereFilesGo() {
        val file = File(compose.activity.cacheDir, "notes.txt").apply { writeText("from the phone\n") }
        val m = startWith(ShareRequest(null, listOf(SharedFile(Uri.fromFile(file), "notes.txt", file.length()))), remembered = null)
        compose.onNodeWithText(string(R.string.share_pick)).assertExists()
        val session = desk.ids[0]
        val pane = m.pocket.value.views.getValue(session).windows.first().blocks.first().id
        // The first session's first tab: the picker lists the sessions in the order the home does.
        compose.onAllNodes(hasText(string(R.string.tab_number, 1)) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)))[0]
            .performClick()
        compose.waitForIdle()
        assertTrue(desk.rex.uploads.isEmpty())
        sheetRow(string(R.string.share_send)).assertIsEnabled().performClick()
        compose.awaitThat { desk.rex.uploads.isNotEmpty() }
        val sent = desk.rex.uploads.single()
        assertEquals(pane, sent.block)
        assertEquals("notes.txt", sent.name)
        assertArrayEquals(file.readBytes(), sent.bytes)
        compose.awaitThat { m.route.value == Route.Term }
    }

    @Test
    fun aFileOverRexsLimitCannotBeSent() {
        val session = desk.ids[1]
        val big = SharedFile(Uri.parse("content://elsewhere/big.iso"), "big.iso", FileUpload.MAX_BYTES + 1)
        val m = startWith(ShareRequest(null, listOf(big)), session to deskPane(session))
        compose.onNodeWithText(string(R.string.share_too_large)).assertExists()
        sheetRow(string(R.string.share_send)).assertIsNotEnabled()
        compose.runOnUiThread { m.sendShare() }
        compose.assertStaysHome(m, 300)
        assertTrue(desk.rex.calls.none { it.startsWith(FileUpload.UPLOAD_BEGIN) })
    }
}

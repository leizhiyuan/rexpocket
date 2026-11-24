package me.leaver.rexpocket.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.core.Desk
import me.leaver.rexpocket.core.Endpoint
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

/**
 * The app's own flows against a real Rex: RexPocketApp on its real view model and store, connected
 * over the network to a Rex listening without TLS on REX_TEST_HOST:REX_TEST_PORT (127.0.0.1:7297 by
 * default), with a second client at the desk. Skipped when no Rex listens; the `rex-real` workflow
 * runs one. On the JVM there is no libghostty, so panes have no picture: what is checked is what Rex
 * holds after each tap, and what the screen says.
 */
@RunWith(AndroidJUnit4::class)
class RealRexAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val endpoint = Endpoint(
        System.getenv("REX_TEST_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
        System.getenv("REX_TEST_PORT")?.toIntOrNull() ?: 7297,
        plaintext = true,
    )
    private lateinit var desk: Desk
    private lateinit var sid: String
    private lateinit var tabs: List<Pair<String, String>>
    private var model: RexViewModel? = null

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    @Before
    fun rex() {
        val up = try {
            Socket().use { it.connect(InetSocketAddress(endpoint.host, endpoint.port), 800); true }
        } catch (_: Exception) {
            false
        }
        assumeTrue("rex plaintext listener on ${endpoint.host}:${endpoint.port} (REX_TEST_HOST/REX_TEST_PORT)", up)
        desk = Desk(endpoint)
        runBlocking {
            desk.connect()
            val made = desk.newSession(tabs = 2)
            sid = made.first
            tabs = made.second
        }
        // A shell reads what is typed only once it has started.
        Thread.sleep(1_500)
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        if (::desk.isInitialized) desk.close()
    }

    private fun launch(onTerminal: Boolean): RexViewModel {
        val app = freshApp()
        val host = SavedHost(id = "real-rex", label = "real rex", host = endpoint.host, port = endpoint.port, plaintext = true)
        val store = HostStore(app)
        store.upsert(host)
        store.setLastHost(host.id)
        store.setLastSession(host.id, sid)
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().putLong("update.checkedAt", System.currentTimeMillis()).commit()
        val m = launchedModel(compose, app, killedOnTerminal = onTerminal)
        model = m
        compose.setContent { RexPocketApp(m) }
        compose.awaitLive(m, sid)
        return m
    }

    private fun owner(block: String) = runBlocking { desk.ownerOf(sid, block) }

    private fun deskView() = runBlocking { desk.view(sid) }

    /** The pane on the phone's screen. */
    private fun shown(m: RexViewModel) = m.pocket.value.view!!.focusedBlock()!!.id

    private fun awaitHeld(m: RexViewModel) {
        compose.awaitThat { m.pocket.value.view?.focusedBlock() != null }
        val block = shown(m)
        compose.awaitThat(20_000) { owner(block) == m.pocket.value.clientId }
    }

    private val paletteRow = SemanticsMatcher("a palette row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("palette-row-") == true
    }

    /** Runs [label] from the command palette, opened from the ⋮ menu, as the person does. */
    private fun palette(label: String) {
        compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput(label)
        compose.waitForIdle()
        compose.onAllNodes(paletteRow and hasText(label))[0].performClick()
        compose.waitForIdle()
    }

    private fun printf(body: String) = "printf '\\033]7501;$body\\033\\\\'"

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    /**
     * Activity on a real Rex: one tab's program says it is done, another's says it failed and exits
     * with 3, and a third program fails without saying anything. Rex keeps the first two records
     * (`done` and `error` outlive their program) and makes none for the silent failure - only a
     * `child_exited` event - so the eye shows a Failed and a Done row, and nothing for the third.
     */
    @Test
    fun activityShowsWhatRealProgramsReported() {
        val m = launch(onTerminal = false)
        runBlocking {
            desk.type(sid, tabs[0].second, printf("state=done:app=probe:msg=" + b64("all good")) + "\n")
            desk.type(sid, tabs[1].second, "sh -c \"${printf("state=error:app=probe:msg=" + b64("failed: 3 tests")).replace("\"", "\\\"")}; exit 3\"\n")
        }
        val quiet = runBlocking { desk.newTab(sid) }
        runBlocking { desk.type(sid, quiet.second, "sh -c 'sleep 0.3; exit 3'\n") }
        compose.awaitThat(20_000) {
            m.pocket.value.blockStatus[tabs[0].second]?.state == "done" && m.pocket.value.blockStatus[tabs[1].second]?.state == "error"
        }
        Thread.sleep(1_500)
        assertEquals("Rex made a record of a program that failed without a report", 0, runBlocking { desk.programStatus(sid, quiet.second) }.length())

        compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.state_failed)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.state_done)).assertIsDisplayed()
        compose.onNodeWithText("failed: 3 tests").assertIsDisplayed()
        compose.onNodeWithText("all good").assertIsDisplayed()
        compose.onNodeWithTag(activityRowTag(tabs[0].first)).assertIsDisplayed()
        compose.onNodeWithTag(activityRowTag(tabs[1].first)).assertIsDisplayed()
        compose.onNodeWithTag(activityRowTag(quiet.first)).assertDoesNotExist()
        // Rex stamps each record, so the age is known even for what happened before the phone looked.
        assertEquals(setOf(tabs[0].second, tabs[1].second), m.pocket.value.statusSince.keys.intersect(setOf(tabs[0].second, tabs[1].second)))

        // A row opens the pane that reported.
        compose.onNodeWithTag(activityRowTag(tabs[1].first)).performClick()
        compose.awaitThat { m.route.value == Route.Term && shown(m) == tabs[1].second }
    }

    /**
     * The user's report on a real Rex: an agent works (the root record) while one of its tasks is
     * done (a child record), as Rex.app shows such a tab under Working. The phone lists it in
     * Running and, until the result is seen, in Needs you; marked as seen, it is still running.
     * Status details shows Rex's own fields: the child's parent, `effective_app` and `owner`.
     */
    @Test
    fun anAgentAtWorkWithAChildDoneIsRunning() {
        val m = launch(onTerminal = false)
        val (tab, pane) = tabs[0]
        val reports = printf("state=working:app=claude:msg=" + b64("Refactoring")) + "; " +
            printf("state=done:id=task-1:title=" + b64("Explore") + ":msg=" + b64("Found 3 files"))
        // The shell keeps running a program, so Rex keeps the working record (it drops working
        // records when the program exits or the shell prompts again).
        runBlocking { desk.type(sid, pane, "sh -c \"${reports.replace("\"", "\\\"")}; sleep 120\"\n") }
        compose.awaitThat(20_000) { m.pocket.value.records[pane]?.size == 2 }
        val child = m.pocket.value.records.getValue(pane).first { it.id == "task-1" }
        assertEquals("", child.parent)
        assertEquals("claude", child.effectiveApp)
        compose.waitForIdle()
        compose.onNodeWithTag(filterChipTag(HomeFilter.Running)).assert(hasText(string(R.string.filter_running, 1)))
        compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).assert(hasText(string(R.string.filter_needs_you, 1)))
        compose.runOnUiThread { m.markSeen("real-rex", listOf(pane)) }
        compose.awaitThat { compose.onAllNodes(hasText(string(R.string.filter_needs_you, 0))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(filterChipTag(HomeFilter.Running)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(activityRowTag(tab)).assertIsDisplayed()
        val details = me.leaver.rexpocket.core.tabDetails(m.pocket.value, tab) { block -> m.seenMarks.value[me.leaver.rexpocket.data.HomeMarks.key("real-rex", block)] }!!
        val text = details.text("t", System.currentTimeMillis())
        assertTrue(text, text.contains("record task-1") && text.contains("effective_app: claude") && text.contains("home lists: Working"))
        assertTrue("Rex names the foreground process: $text", Regex("owner: \\d+ \\S+").containsMatchIn(text))
    }

    /** The palette's actions do on a real Rex what they say: split, new tab, rename, release and take over, close. */
    @Test
    fun thePalettesActionsChangeTheRealRex() {
        val m = launch(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        awaitHeld(m)
        val first = shown(m)
        val tab = deskView().windows.first { w -> w.blocks.any { it.id == first } }.id

        palette(string(R.string.menu_split_right))
        compose.awaitThat { deskView().windows.first { it.id == tab }.blocks.size == 2 }
        // The new pane is the one on the phone's screen, and the phone holds it.
        compose.awaitThat { shown(m) != first }
        awaitHeld(m)
        val split = shown(m)

        palette(string(R.string.menu_new_tab))
        compose.awaitThat { deskView().windows.size == 3 }
        // Rex can tell of the tab before the phone has moved onto it.
        compose.awaitThat { shown(m) != split }
        awaitHeld(m)
        val newTab = deskView().windows.first { w -> w.blocks.any { it.id == shown(m) } }.id
        assertNotEquals(tab, newTab)

        // Rename: its dialog holds a focused text field, which never lets Robolectric's Compose call
        // itself idle, so the save is sent the way the dialog's Save sends it (TermScreen's rename).
        compose.runOnUiThread { m.act { renameWindow("renamed-on-the-phone", newTab) } }
        compose.awaitThat { deskView().windows.first { it.id == newTab }.label == "renamed-on-the-phone" }

        val held = shown(m)
        palette(string(R.string.action_release))
        compose.awaitThat { owner(held) != m.pocket.value.clientId }
        palette(string(R.string.action_take_over))
        compose.awaitThat { owner(held) == m.pocket.value.clientId }

        palette(string(R.string.menu_close_tab))
        compose.onNode(hasText(string(R.string.action_close)) and hasClickAction()).performClick()
        compose.awaitThat { deskView().windows.none { it.id == newTab } }
        assertEquals(null, m.pocket.value.notice)

        compose.awaitThat { m.pocket.value.view?.windows?.size == 2 }
        assertEquals(listOf(first, split).sorted(), deskView().windows.first { it.id == tab }.blocks.map { it.id }.sorted())
    }

    /** Copy output and Share output in the palette take the real pane's whole output, as its program wrote it. */
    @Test
    fun thePalettesCopyAndShareTakeTheRealPanesOutput() {
        val m = launch(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        awaitHeld(m)
        val pane = shown(m)
        // Longer than a line of the pane, so unwrap has a line to join; and $((40+2)) so only the output reads end-42-mark.
        val long = "x".repeat(150)
        runBlocking { desk.type(sid, pane, "seq 1 12000; echo $long; echo end-\$((40+2))-mark\n") }
        compose.awaitThat { runBlocking { desk.text(sid, pane) }.contains("\nend-42-mark") }

        palette(string(R.string.palette_copy_output))
        compose.awaitThat { compose.runOnUiThread<String?> { clipboard(compose.activity) }.orEmpty().contains("\nend-42-mark") }
        val copied = compose.runOnUiThread<String?> { clipboard(compose.activity) }!!
        assertTrue("the scrollback came too, past Rex's first part", copied.contains("\n11999\n12000\n"))
        assertTrue("a wrapped line is one line again", copied.contains("\n$long\n"))

        palette(string(R.string.palette_share_output))
        compose.awaitThat { org.robolectric.Shadows.shadowOf(compose.activity).peekNextStartedActivity() != null }
        val chooser = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
        @Suppress("DEPRECATION")
        val sent = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertTrue(sent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty().contains("\nend-42-mark"))
    }

    /** Clear terminal and Reset terminal in the palette empty the real pane on the phone's screen. */
    @Test
    fun thePalettesClearAndResetEmptyTheRealPane() {
        val m = launch(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        awaitHeld(m)
        val pane = shown(m)
        // The command line says $((40+2)); only its output reads end-42-mark.
        for (label in listOf(R.string.palette_clear_terminal, R.string.palette_reset_terminal)) {
            runBlocking { desk.type(sid, pane, "seq 1 30; echo end-\$((40+2))-mark\n") }
            compose.awaitThat { runBlocking { desk.text(sid, pane) }.contains("\nend-42-mark") }
            palette(string(label))
            // Clear and Reset ask first.
            val confirm = if (label == R.string.palette_clear_terminal) R.string.action_clear else R.string.confirm_reset_action
            compose.onNode(hasText(string(confirm)) and hasClickAction()).performClick()
            compose.awaitThat { !runBlocking { desk.text(sid, pane) }.contains("end-42-mark") }
        }
        assertEquals(null, m.pocket.value.notice)
    }

    /** The desk closes the tab on the phone's screen: the screen says so, with no error, and moves on when asked. */
    @Test
    fun aTabClosedAtTheDeskIsSaidCalmly() {
        val m = launch(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        awaitHeld(m)
        val block = shown(m)
        val tab = deskView().windows.first { w -> w.blocks.any { it.id == block } }.id
        runBlocking { desk.closeTab(sid, tab) }
        compose.awaitThat { m.pocket.value.closed != null }
        compose.onNodeWithText(string(R.string.closed_tab_title)).assertIsDisplayed()
        assertEquals(null, m.pocket.value.notice)
        val left = deskView().windows.single()
        compose.onNode(hasText(string(R.string.closed_show, "").trim(), substring = true) and hasClickAction()).performClick()
        compose.awaitThat { m.pocket.value.closed == null && shown(m) == left.blocks.first().id }
        compose.awaitThat(20_000) { owner(left.blocks.first().id) == m.pocket.value.clientId }
        assertEquals(null, m.pocket.value.notice)
    }

    /**
     * The home's sheets on a session the phone never attached: a real Rex takes the rename, the new
     * tab (whose pane then has no owner, so the desk's sizes stay), the close and the end from the
     * bootstrapped connection, and the phone stays on the session it shows.
     */
    @Test
    fun theHomeManagesASessionItNeverAttached() {
        val other = runBlocking { desk.newSession(tabs = 2).first }
        val m = launch(onTerminal = false)
        compose.awaitLive(m, other)
        fun view() = m.pocket.value.views.getValue(other)
        val deskOwners = runBlocking { desk.view(other).windows.flatMap { w -> w.blocks.map { it.id to desk.ownerOf(other, it.id) } } }

        compose.runOnUiThread { m.manage(HomeAction.RenameSession(other, "managed at home")) }
        compose.awaitThat { view().label == "managed at home" }
        val first = view().windows.first().id
        compose.runOnUiThread { m.manage(HomeAction.RenameTab(other, first, "logs")) }
        compose.awaitThat { view().windows.first { it.id == first }.label == "logs" }
        compose.runOnUiThread { m.manage(HomeAction.NewTab(other)) }
        compose.awaitThat { view().windows.size == 3 }
        val made = view().windows.last()
        assertEquals("the new tab's pane has no owner", null, runBlocking { desk.ownerOf(other, made.blocks.first().id) })
        compose.runOnUiThread { m.manage(HomeAction.CloseTab(other, made.id)) }
        compose.awaitThat { view().windows.size == 2 }
        assertEquals("the desk's panes kept their owners", deskOwners, runBlocking { deskOwners.map { (b, _) -> b to desk.ownerOf(other, b) } })
        assertEquals(sid, m.pocket.value.activeSessionId)
        assertEquals(Route.Home, m.route.value)

        compose.runOnUiThread { m.manage(HomeAction.EndSession(other)) }
        compose.awaitThat { m.pocket.value.sessions.none { it.id == other } }
        assertEquals(sid, m.pocket.value.activeSessionId)
        assertEquals(null, m.pocket.value.notice)
    }

    /** A session whose every tab was closed at the desk stays on Rex; the phone offers a new tab in it. */
    @Test
    fun aSessionLeftWithNoTabsOffersANewOne() {
        val m = launch(onTerminal = true)
        compose.awaitThat { m.route.value == Route.Term }
        runBlocking { deskView().windows.forEach { desk.closeTab(sid, it.id) } }
        compose.awaitThat { m.pocket.value.view?.windows?.isEmpty() == true && m.pocket.value.closed == null || m.pocket.value.closed != null }
        if (m.pocket.value.closed != null) {
            // The pane on screen went with its tab: the closed note first, which offers a new tab.
            compose.onNode(hasText(string(R.string.menu_new_tab)) and hasClickAction()).performClick()
        } else {
            compose.onNodeWithTag(EMPTY_SESSION_TAG).assertIsDisplayed()
            compose.onNode(hasText(string(R.string.menu_new_tab)) and hasClickAction()).performClick()
        }
        compose.awaitThat { deskView().windows.size == 1 }
        assertEquals(sid, m.pocket.value.activeSessionId)
        assertEquals(null, m.pocket.value.notice)
    }
}

package me.leaver.rexpocket.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.ui.guide.GUIDE_PREFS
import me.leaver.rexpocket.ui.guide.GUIDE_SHOW_ME_TAG
import me.leaver.rexpocket.ui.guide.Guide
import me.leaver.rexpocket.ui.guide.GuideSpot
import me.leaver.rexpocket.ui.guide.INSTALL_NEW
import me.leaver.rexpocket.ui.guide.SPOTLIGHT_CARD_TAG
import me.leaver.rexpocket.ui.guide.TOUR_STEPS
import me.leaver.rexpocket.ui.guide.guideEntry
import me.leaver.rexpocket.ui.guide.guideEntryTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Help where it meets the rest of the app: Back during the tour, a press on the spotlight's card,
 * Help's sheet through a rotation, Show me into a page Settings also opens, the back arrow of
 * Backup, and the tour on a start that came for something else. The whole app on ScriptedRex, JVM
 * and emulator. See docs/design/help-guide.md.
 */
@RunWith(AndroidJUnit4::class)
class GuideEdgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null
    private val guide = Guide()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(
        newInstall: Boolean = false,
        restoration: StateRestorationTester? = null,
        before: ((RexViewModel) -> Unit)? = null,
    ): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        if (newInstall) app.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).edit().putString("install", INSTALL_NEW).commit()
        val m = launchedModel(compose, app)
        model = m
        if (before != null) compose.runOnUiThread { before(m) }
        if (restoration != null) restoration.setContent { RexPocketApp(m) } else compose.setContent { RexPocketApp(m, guide = guide) }
        // An app opened for something does not connect the last host by itself.
        if (before == null) compose.awaitLive(m, desk.ids[0]) else compose.waitForIdle()
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun sheetRow(text: String) = compose.onNode(hasText(text) and hasClickAction() and inASheet)

    private fun backArrows() = compose.onAllNodes(hasContentDescription(string(R.string.action_back)) and hasClickAction() and inASheet)
        .fetchSemanticsNodes().size

    private fun awaitShown(spot: GuideSpot) {
        try {
            compose.awaitThat(8_000) { guide.shown == spot }
        } catch (e: AssertionError) {
            throw AssertionError("not shown: $spot (shown ${guide.shown}, spotlight ${guide.spotlight?.spot}, help ${guide.help})", e)
        }
    }

    /** Back during the tour ends it, as Skip does; it does not leave the app with the tour still up. */
    @Test
    fun backEndsTheTour() {
        start()
        compose.runOnUiThread { guide.startTour() }
        awaitShown(TOUR_STEPS[0].spot)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNull("the tour is still up after Back", guide.spotlight)
        assertFalse("Back left the app instead of ending the tour", compose.activity.isFinishing)
    }

    /**
     * A press on the card's words, read while deciding, stays on the card: the row under it does
     * not open, and the spotlight stays until Got it or a press elsewhere.
     */
    @Test
    fun aPressOnTheCardDoesNotReachTheRowUnderIt() {
        val m = start()
        compose.runOnUiThread { guide.show(guideEntry("needs_you")!!) }
        awaitShown(GuideSpot.HomeNeedsYou)
        val card = compose.onNodeWithTag(SPOTLIGHT_CARD_TAG).fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText(string(R.string.guide_needs_you_title)) and hasAnyAncestor(hasTestTag(SPOTLIGHT_CARD_TAG)))
        // Something the person could tap lies under the card's words: otherwise this proves nothing.
        val under = compose.onAllNodes(hasClickAction() and !hasAnyAncestor(hasTestTag(SPOTLIGHT_CARD_TAG)) and !inASheet).fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(card) && it.boundsInRoot.top > card.top - 1f }
        assertTrue("nothing tappable under the card", under.isNotEmpty())
        val folds = m.folds.value
        title.performTouchInput { click(Offset(width / 2f, height / 2f)) }
        compose.waitForIdle()
        assertEquals("the press reached the screen under the card", Route.Home, m.route.value)
        assertEquals("the press folded a row under the card", folds, m.folds.value)
        assertTrue("a sheet opened under the card", compose.onAllNodes(hasTestTag(ACTION_SHEET_TAG)).fetchSemanticsNodes().isEmpty())
        assertEquals(GuideSpot.HomeNeedsYou, guide.shown)
    }

    /**
     * TalkBack reaches the card first: it is a traversal group put before the screen it lies over,
     * so a swipe after the announcement lands on its words and then Got it, not on the home's rows.
     */
    @Test
    fun theCardIsFirstForTalkBack() {
        start()
        compose.runOnUiThread { guide.show(guideEntry("needs_you")!!) }
        awaitShown(GuideSpot.HomeNeedsYou)
        val card = compose.onNodeWithTag(SPOTLIGHT_CARD_TAG, useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue("the card is not a traversal group", card.getOrElse(SemanticsProperties.IsTraversalGroup) { false })
        val index = card.getOrElse(SemanticsProperties.TraversalIndex) { 0f }
        assertTrue("the card is not before the screen in traversal order: $index", index < 0f)
    }

    /** Help's sheet, on an entry's page, is there again after a rotation, as the home's other sheets are. */
    @Test
    fun helpSurvivesARotation() {
        val restoration = StateRestorationTester(compose)
        start(restoration = restoration)
        compose.onNode(hasContentDescription(string(R.string.home_menu)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.guide_menu)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(guideEntryTag("app_lock")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(GUIDE_SHOW_ME_TAG).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(GUIDE_SHOW_ME_TAG).assertExists()
    }

    /**
     * Show me into a page Settings also opens: no back arrow to Settings, though Settings opened a
     * page before and it was closed by a swipe, which keeps no record of it.
     */
    @Test
    fun showMeIntoAPageHasNoArrowToSettings() {
        start()
        compose.onNode(hasContentDescription(string(R.string.settings)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.security_title)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Security from Settings has its arrow", 1, backArrows())
        compose.runOnUiThread { guide.show(guideEntry("gesture_settings")!!) }
        awaitShown(GuideSpot.GestureTap)
        assertEquals("Terminal controls from Help has an arrow to Settings", 0, backArrows())
    }

    /** Backup closed by a swipe, then a backup file opened from Files: its page has no arrow to Settings. */
    @Test
    fun backupOpenedAgainFromElsewhereHasNoArrowToSettings() {
        val m = start()
        compose.onNode(hasContentDescription(string(R.string.settings)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.backup_title)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Backup from Settings has its arrow", 1, backArrows())
        // A swipe down: the sheet's dismiss.
        compose.runOnUiThread { m.settingsBackup.close() }
        compose.waitForIdle()
        compose.runOnUiThread { m.settingsBackup.readUri(android.net.Uri.parse("content://me.leaver.rexpocket.none/backup.json")) }
        compose.awaitThat { m.settingsBackup.step.value != null }
        compose.waitForIdle()
        assertEquals("Backup from a file has an arrow to Settings", 0, backArrows())
    }

    /** The widget's tap on the first start with a host: the Activity view it asked for, and no tour over it, then or after. */
    @Test
    fun noTourOnAStartFromTheWidget() {
        start(newInstall = true) { it.openActivity() }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertNull("the tour ran over what the widget opened", guide.spotlight)
    }

    /** A rexpocket://add link on the first start with a host: Add host, and no tour under its dialog. Emulator: a dialog's text fields never let Robolectric idle. */
    @Test
    fun noTourUnderAHostLinksDialog() {
        assumeFalse(onRobolectric)
        start(newInstall = true) { it.openHostLink("rexpocket://add?addr=pi.local&name=pi") }
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertNull("the tour ran under Add host", guide.spotlight)
    }
}

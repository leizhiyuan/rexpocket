package me.leaver.rexpocket.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.ui.guide.GUIDE_PREFS
import me.leaver.rexpocket.ui.guide.GUIDE_SEARCH_TAG
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Help as a person uses it, the whole app on ScriptedRex: found from the home's ⋮, Settings and the
 * palette; searched; Show me opening the place and pointing at the control; the spotlight ended by
 * Got it or by the tap on the control, which still works; a terminal entry from the home asking for
 * a tab and opening one; the tour's Next and Skip, and its one run by itself on a new install only.
 * See docs/design/help-guide.md.
 */
@RunWith(AndroidJUnit4::class)
class GuideScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null
    private val guide = Guide()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun start(onTerminal: Boolean = false, newInstall: Boolean = false): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        if (newInstall) app.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).edit().putString("install", INSTALL_NEW).commit()
        val m = launchedModel(compose, app, killedOnTerminal = onTerminal)
        model = m
        compose.runOnUiThread { m.setKeyBar(true) }
        compose.setContent { RexPocketApp(m, guide = guide) }
        compose.awaitLive(m, desk.ids[0])
        if (onTerminal) compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    private val inASheet = hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG))

    private fun sheetRow(text: String) = compose.onNode(hasText(text) and hasClickAction() and inASheet)

    private fun card(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(SPOTLIGHT_CARD_TAG)))

    private fun awaitShown(spot: GuideSpot) {
        try {
            compose.awaitThat(8_000) { guide.shown == spot }
        } catch (e: AssertionError) {
            throw AssertionError("not shown: $spot (shown ${guide.shown}, spotlight ${guide.spotlight?.spot}, help ${guide.help})", e)
        }
    }

    @Test
    fun helpFromTheHomeMenuFindsTheLockAndShowsIt() {
        start()
        compose.onNode(hasContentDescription(string(R.string.home_menu)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.guide_menu)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(GUIDE_SEARCH_TAG).performTextInput("lock")
        compose.waitForIdle()
        compose.onNodeWithTag(guideEntryTag("app_lock")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(GUIDE_SHOW_ME_TAG).performScrollTo().performClick()
        awaitShown(GuideSpot.AppLock)
        // The card says what and how, and Got it ends it: a button TalkBack reaches.
        card(string(R.string.guide_app_lock_title)).assertIsDisplayed()
        card(string(R.string.guide_got_it)).assertHasClickAction().performClick()
        compose.awaitThat { guide.spotlight == null }
        // Security stays open under it, on the row it pointed at.
        sheetRow(string(R.string.security_lock)).assertExists()
    }

    @Test
    fun settingsHasHelp() {
        start()
        compose.onNode(hasContentDescription(string(R.string.settings)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.guide_menu)).performScrollTo().performClick()
        compose.awaitThat { guide.help != null }
        compose.onNodeWithTag(GUIDE_SEARCH_TAG).assertExists()
        sheetRow(string(R.string.guide_tour_row)).assertExists()
    }

    @Test
    fun aTapOnTheControlEndsTheSpotlightAndStillWorks() {
        val m = start()
        compose.runOnUiThread { guide.show(guideEntry("needs_you")!!) }
        awaitShown(GuideSpot.HomeNeedsYou)
        compose.onNodeWithTag(filterChipTag(HomeFilter.NeedsYou)).performClick()
        compose.awaitThat { guide.spotlight == null }
        compose.awaitThat { m.homeFilter.value == HomeFilter.NeedsYou }
    }

    @Test
    fun aTerminalEntryFromTheHomeOpensATabFirst() {
        val m = start()
        compose.runOnUiThread { guide.openHelp("text_size") }
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.guide_needs_tab))).assertExists()
        sheetRow(string(R.string.guide_open_tab_on, desk.host.label)).performScrollTo().performClick()
        compose.awaitThat { m.route.value == Route.Term }
        awaitShown(GuideSpot.TermTextSize)
        compose.onNodeWithTag(TEXT_SIZE_ROW_TAG).assertExists()
    }

    @Test
    fun thePaletteFindsHelpAndGoesThere() {
        val m = start(onTerminal = true)
        compose.onNode(hasContentDescription(string(R.string.action_more)) and hasClickAction()).performClick()
        compose.waitForIdle()
        sheetRow(string(R.string.palette_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PALETTE_FIELD_TAG).performTextInput("lock the app")
        compose.waitForIdle()
        val row = string(R.string.guide_palette_row, string(R.string.guide_app_lock_title))
        compose.onNode(hasText(row) and hasTestTag(paletteRowTag(0))).performClick()
        // Security is the home's: the terminal makes way for it.
        compose.awaitThat { m.route.value == Route.Home }
        awaitShown(GuideSpot.AppLock)
    }

    @Test
    fun theTourStepsOnWithNextAndEndsWithSkip() {
        start()
        compose.runOnUiThread { guide.startTour() }
        awaitShown(TOUR_STEPS[0].spot)
        card(string(R.string.guide_tour_next)).performClick()
        awaitShown(TOUR_STEPS[1].spot)
        card(string(R.string.guide_tour_skip)).performClick()
        compose.awaitThat { guide.spotlight == null }
        assertTrue("the tour is not stored as done", compose.activity.getSharedPreferences(GUIDE_PREFS, Context.MODE_PRIVATE).getBoolean("tour", false))
    }

    @Test
    fun theTourRunsByItselfOnANewInstallWithAHost() {
        start(newInstall = true)
        compose.mainClock.advanceTimeBy(3_000)
        awaitShown(TOUR_STEPS[0].spot)
        assertEquals(0, guide.spotlight?.step)
    }

    @Test
    fun theTourNeverRunsByItselfForSomeoneWhoHadHosts() {
        start()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        assertNull(guide.spotlight)
    }
}

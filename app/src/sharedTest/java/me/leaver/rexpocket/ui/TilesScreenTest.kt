package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.TILE_PICKER_CLEAR_TAG
import me.leaver.rexpocket.alerts.TILE_PICKER_OPEN_APP_TAG
import me.leaver.rexpocket.alerts.TILE_PICKER_SLOT_TAG
import me.leaver.rexpocket.alerts.TilePicker
import me.leaver.rexpocket.alerts.TileStore
import me.leaver.rexpocket.core.AlertPrefs
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.data.SavedHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick Settings tiles once they are set: a page's On tile N with Change… and Remove from tile,
 * Replace when all three are taken, the picker showing each tile's item and rebinding or clearing
 * any of them, and Settings' list of the three. Also a page's line that the background
 * notification and its buttons come only with background mode, with a tap that turns it on.
 */
@RunWith(AndroidJUnit4::class)
class TilesScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private val mac = SavedHost(id = "mac", label = "mac", host = "mac.local", port = 7297, plaintext = true)
    private val names = mapOf(Connectable.host("mac") to "mac", Connectable.host("linux") to "linux", Connectable.host("pi") to "pi", Connectable.tailnet("tailscale") to "Tailscale")
    private val nameOf: (Connectable) -> String? = { names[it] }

    @Before
    fun clean() {
        freshApp()
    }

    private fun store() = TileStore(compose.activity)

    private fun row(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(hasTestTag(ACTION_SHEET_TAG)))

    private fun page(backgroundMode: Boolean = true, turnedOn: MutableList<Unit> = mutableListOf()) {
        compose.setContent {
            RexTheme {
                HostPage(
                    host = mac,
                    live = null,
                    choices = HostBackground(),
                    defaults = AlertPrefs(),
                    backgroundMode = backgroundMode,
                    onChoices = {},
                    onConnect = {},
                    onDisconnect = {},
                    onRetry = {},
                    onBack = null,
                    onDismiss = {},
                    tile = { TileGroup(Connectable.host(mac.id), "mac", nameOf) },
                    onBackgroundMode = { turnedOn += Unit },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aPageOnATileSaysWhichAndRemovesIt() {
        store().bind(2, Connectable.host("mac"))
        page()
        row(string(R.string.tile_bound, 2)).performScrollTo()
        row(string(R.string.tile_remove)).performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(store().of(2))
        // Back to Add a tile, which puts it on the first free one.
        row(string(R.string.tile_add)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, store().slotOf(Connectable.host("mac")))
        row(string(R.string.tile_bound, 1)).performScrollTo()
    }

    @Test
    fun aTileIsOnlyBoundUntilAndroidSaysItIsInQuickSettings() {
        store().bind(2, Connectable.host("mac"))
        // Dismissed: bound, and the page says how to add it by hand.
        store().setAdded(2, false)
        page()
        row(string(R.string.tile_bound, 2)).performScrollTo()
        compose.onNode(hasText(string(R.string.tile_not_added, 2), substring = true), useUnmergedTree = true).assertExists()
        // Added: on the tile.
        compose.runOnUiThread { store().setAdded(2, true) }
        compose.waitForIdle()
        row(string(R.string.tile_added, 2)).performScrollTo()
    }

    @Test
    fun thePickerWithNothingToBindSaysWhatToDoAndOpensTheApp() {
        var opened = 0
        compose.setContent { RexTheme { TilePicker(1, emptyList(), nameOf, onDone = {}, onOpenApp = { opened++ }) } }
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.tile_picker_empty))).assertExists()
        compose.onNodeWithTag(TILE_PICKER_OPEN_APP_TAG).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun withEveryTileTakenAPageOffersToReplaceOne() {
        store().bind(1, Connectable.host("linux"))
        store().bind(2, Connectable.tailnet("tailscale"))
        store().bind(3, Connectable.host("pi"))
        page()
        row(string(R.string.tile_replace, 2, "Tailscale")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(Connectable.host("mac"), store().of(2))
        assertEquals(Connectable.host("linux"), store().of(1))
        row(string(R.string.tile_bound, 2)).performScrollTo()
    }

    @Test
    fun thePickerShowsEveryTilesItemAndRebindsOrClearsAny() {
        store().bind(1, Connectable.host("linux"))
        store().bind(3, Connectable.host("mac"))
        var done = 0
        compose.setContent { RexTheme { TilePicker(1, names.entries.map { it.key to it.value }, nameOf) { done++ } } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText(string(R.string.tile_slot_item, 1, "linux"))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText(string(R.string.tile_slot_item, 2, string(R.string.tile_free)))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText(string(R.string.tile_slot_item, 3, "mac"))).fetchSemanticsNodes().isNotEmpty())
        // Tile 2, then mac: mac moves there from tile 3.
        compose.onNodeWithTag(TILE_PICKER_SLOT_TAG + 2).performScrollTo().performClick()
        compose.onNode(hasText(string(R.string.tile_moves_from, 3))).performScrollTo()
        compose.onNode(hasText("mac") and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, done)
        assertEquals(Connectable.host("mac"), store().of(2))
        assertNull(store().of(3))
        // Tile 1, then Clear tile.
        compose.onNodeWithTag(TILE_PICKER_SLOT_TAG + 1).performScrollTo().performClick()
        compose.onNodeWithTag(TILE_PICKER_CLEAR_TAG).performClick()
        compose.waitForIdle()
        assertNull(store().of(1))
    }

    @Test
    fun settingsListsTheThreeTilesWithChangeAndClear() {
        store().bind(2, Connectable.host("linux"))
        compose.setContent { RexTheme { ActionSheet(onDismiss = {}, title = "t") { TileSlotsGroup(nameOf) } } }
        compose.waitForIdle()
        row(string(R.string.tile_slot_item, 2, "linux")).performScrollTo()
        row(string(R.string.tile_slot_item, 1, string(R.string.tile_free))).performScrollTo()
        row(string(R.string.tile_clear_n, 2)).performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(store().of(2))
        assertFalse(compose.onAllNodes(hasText(string(R.string.tile_clear_n, 2))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun withBackgroundModeOffAPageSaysTheNotificationNeedsItAndTurnsItOn() {
        val turnedOn = mutableListOf<Unit>()
        page(backgroundMode = false, turnedOn = turnedOn)
        compose.onNode(hasText(string(R.string.bg_watch_only_with_mode), substring = true), useUnmergedTree = true).performScrollTo()
        row(string(R.string.bg_turn_on)).performScrollTo().performClick()
        assertEquals(1, turnedOn.size)
    }

    @Test
    fun withBackgroundModeOnThereIsNoSuchLine() {
        page(backgroundMode = true)
        assertFalse(compose.onAllNodes(hasText(string(R.string.bg_turn_on))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theChecklistSaysThereIsNoNotificationWhileBackgroundModeIsOff() {
        var asked = 0
        compose.setContent {
            RexTheme { NotificationsSheet(onDismiss = {}, alerts = false, onAlerts = { asked++ }, prefs = AlertPrefs(), onPrefs = {}) }
        }
        compose.waitForIdle()
        compose.onNode(hasText(string(R.string.bg_watch_check_off), substring = true), useUnmergedTree = true).performScrollTo()
        row(string(R.string.bg_watch_check)).performScrollTo().performClick()
        assertEquals(1, asked)
    }
}

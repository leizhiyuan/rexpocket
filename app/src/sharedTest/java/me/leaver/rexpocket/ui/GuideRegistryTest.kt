package me.leaver.rexpocket.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.ui.guide.GUIDE_ENTRIES
import me.leaver.rexpocket.ui.guide.Guide
import me.leaver.rexpocket.ui.guide.GuideEntry
import me.leaver.rexpocket.ui.guide.TOUR_STEPS
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Every Help entry's Show me ends with its control in a spotlight, on screen: the whole app on
 * ScriptedRex, from the home for the home's entries and from a terminal on a tab for the terminal's.
 * A control renamed, moved or taken away fails here, not in the hands of someone following the
 * guide. So does a tour step. See docs/design/help-guide.md.
 */
@RunWith(AndroidJUnit4::class)
class GuideRegistryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 2)
    private var model: RexViewModel? = null
    private val guide = Guide()

    private fun start(onTerminal: Boolean): RexViewModel {
        val app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        val m = launchedModel(compose, app, killedOnTerminal = onTerminal)
        model = m
        // The key bar stays with a desk keyboard too: an emulator may say it has one.
        compose.runOnUiThread { m.setKeyBar(true) }
        compose.setContent { RexPocketApp(m, guide = guide) }
        compose.awaitLive(m, desk.ids[0])
        if (onTerminal) {
            compose.awaitThat { m.route.value == Route.Term && m.pocket.value.view?.focusedBlock() != null }
        }
        return m
    }

    @After
    fun stop() {
        model?.let { m -> compose.runOnUiThread { m.disconnect() } }
        desk.close()
    }

    /** Show me for each entry in turn; the ones that did not end on their control, with why. */
    private fun walk(entries: List<GuideEntry>): List<String> {
        val missed = ArrayList<String>()
        for (entry in entries) {
            compose.runOnUiThread { guide.show(entry) }
            try {
                compose.awaitThat(8_000) { guide.shown == entry.spot || guide.help != null }
            } catch (e: AssertionError) {
                missed += "${entry.id}: nothing after 8 s"
            }
            if (guide.shown != entry.spot) missed += "${entry.id}: ${entry.spot} not shown (help: ${guide.help})"
            compose.runOnUiThread {
                guide.dismiss()
                guide.help = null
            }
            compose.waitForIdle()
        }
        return missed
    }

    @Test
    fun everyHomeEntryShowsItsControl() {
        start(onTerminal = false)
        val missed = walk(GUIDE_ENTRIES.filter { !it.place.terminal })
        assertTrue("entries that did not show their control:\n" + missed.joinToString("\n"), missed.isEmpty())
    }

    @Test
    fun everyTerminalEntryShowsItsControl() {
        start(onTerminal = true)
        val missed = walk(GUIDE_ENTRIES.filter { it.place.terminal })
        assertTrue("entries that did not show their control:\n" + missed.joinToString("\n"), missed.isEmpty())
    }

    @Test
    fun everyTourStepShowsItsControl() {
        start(onTerminal = false)
        compose.runOnUiThread { guide.startTour() }
        for (step in TOUR_STEPS) {
            compose.awaitThat(8_000) { guide.shown == step.spot }
            compose.runOnUiThread { guide.next() }
        }
        compose.awaitThat { guide.spotlight == null }
    }
}

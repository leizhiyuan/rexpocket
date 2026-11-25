package me.leaver.rexpocket.alerts

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.activitySections
import me.leaver.rexpocket.core.ActivityGroup
import me.leaver.rexpocket.ui.Connections
import me.leaver.rexpocket.ui.HomeFilter
import me.leaver.rexpocket.ui.RexOnDesk
import me.leaver.rexpocket.ui.RexViewModel
import me.leaver.rexpocket.ui.awaitLive
import me.leaver.rexpocket.ui.filterSections
import me.leaver.rexpocket.ui.freshApp
import me.leaver.rexpocket.ui.launchedModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The widget's counts on a real connection, against the home they open: a blocked tab and a done
 * one read as the Activity view reads them, in its words (Blocked, Done, Working).
 */
@RunWith(AndroidJUnit4::class)
class HomeWidgetCountsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val desk = RexOnDesk(sessions = 1)
    private lateinit var app: Application
    private lateinit var model: RexViewModel

    @After
    fun stop() {
        if (::model.isInitialized) compose.runOnUiThread { model.disconnectAll() }
        desk.close()
    }

    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) throw AssertionError("still waiting")
            Thread.sleep(10)
        }
    }

    @Test
    fun theWidgetCountsAsTheActivityViewDoesInItsWords() {
        app = freshApp()
        desk.saveFor(app, lastSession = desk.ids[0])
        model = launchedModel(compose, app)
        compose.runOnUiThread { model.connect(desk.host) }
        compose.awaitLive(model, desk.ids[0])
        val blocks = model.pocket.value.views.getValue(desk.ids[0]).allBlocks().map { it.id }
        desk.rex.report(blocks[0], "blocked", kind = "question", app = "claude", msg = "Which branch?")
        desk.rex.report(blocks[1], "done", app = "pi")
        await { activitySections(model.pocket.value).sumOf { it.items.size } == 2 }

        val state = model.pocket.value
        val sections = activitySections(state).associate { it.group to it.items.size }
        val host = HomeWidget.snapshotOf(Connections.of(app), live = true).hosts.single()
        val line = HomeWidget.countsLine(app, host)
        // The Activity view the tap opens: one Blocked, one Done, nothing Working.
        assertEquals(1, sections[ActivityGroup.Waiting])
        assertEquals(1, sections[ActivityGroup.Done])
        assertEquals(app.getString(R.string.widget_blocked, 1) + " · " + app.getString(R.string.widget_done, 1) + " · " + app.getString(R.string.widget_working, 0), line)
        // Home's Needs you counts both, and so do the widget's numbers.
        val needsYou = filterSections(state, HomeFilter.NeedsYou, { _, _ -> false }).sumOf { it.items.size }
        assertEquals(needsYou, host.blocked + host.failed + host.done)
    }
}

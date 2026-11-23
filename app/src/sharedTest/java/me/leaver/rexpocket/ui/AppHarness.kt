package me.leaver.rexpocket.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import me.leaver.rexpocket.core.Desk
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.ScriptedRex
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.data.SavedHost
import org.junit.Assert.assertEquals

// The whole app on the fake Rex the scenarios in core run on (ScriptedRex), with a desk that made
// the sessions beforehand: RexPocketApp on the real RexViewModel and HostStore, connecting over a
// real socket. See docs/design/testing.md.

internal typealias AppRule = AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

/** True where libghostty is there to draw a pane: on a device, and on a computer that built it. */
internal fun ghosttyLoads(): Boolean = runCatching { System.loadLibrary("ghostty_jni") }.isSuccess

internal class RexOnDesk(sessions: Int = 2) : AutoCloseable {
    /**
     * Without libghostty (the JVM tests on a computer that did not build it), panes send no picture:
     * the terminal screen still opens on its pane, it just has nothing to draw.
     */
    val rex = ScriptedRex().apply { pictures = ghosttyLoads() }
    private val desk = Desk(rex.endpoint)

    /** Each session the desk made, in order, with its two tabs. */
    val ids: List<String>

    init {
        ids = runBlocking {
            desk.connect()
            List(sessions) { desk.newSession(tabs = 2).first }
        }
    }

    /** Closes every tab of [session] at the desk; Rex keeps the session, with no windows. */
    fun closeEveryTab(session: String) = runBlocking {
        desk.view(session).windows.forEach { desk.closeTab(session, it.id) }
    }

    val host = SavedHost(id = "desk-host", label = "desk", host = "127.0.0.1", port = rex.endpoint.port, plaintext = true)

    /**
     * The app as it is after an earlier run: this host saved and the one opened last, with
     * [lastSession] the session that was on its terminal. No update check, which would ask GitHub.
     */
    fun saveFor(app: Application, lastSession: String? = null): HostStore {
        val store = HostStore(app)
        store.upsert(host)
        store.setLastHost(host.id)
        if (lastSession != null) store.setLastSession(host.id, lastSession)
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit()
            .putLong("update.checkedAt", System.currentTimeMillis()).commit()
        return store
    }

    override fun close() {
        desk.close()
        rex.close()
    }
}

/**
 * A view model as Android makes one: after the process was killed with the terminal on screen when
 * [killedOnTerminal], otherwise on a fresh start.
 */
internal fun launchedModel(compose: AppRule, app: Application, killedOnTerminal: Boolean = false): RexViewModel {
    lateinit var model: RexViewModel
    val saved = if (killedOnTerminal) SavedStateHandle(mapOf(KEY_TERMINAL_OPEN to true)) else SavedStateHandle()
    compose.runOnUiThread { model = RexViewModel(app, saved) }
    return model
}

internal fun AppRule.awaitLive(model: RexViewModel, session: String) {
    try {
        awaitThat {
            val st = model.pocket.value
            st.phase == Phase.Live && st.views.containsKey(session)
        }
    } catch (e: Throwable) {
        val st = model.pocket.value
        throw AssertionError("not live on $session: ${st.phase} ${st.problem} views=${st.views.keys} active=${st.activeSessionId} sessions=${st.sessions.map { it.id }}", e)
    }
    waitForIdle()
}

/**
 * Waits for [condition], letting the main thread run in between. Compose's own waitUntil does not
 * always let Robolectric's main looper run what the network posted to it once a touch went in.
 */
internal fun AppRule.awaitThat(ms: Long = CONNECT_MS, condition: () -> Boolean) {
    val until = System.currentTimeMillis() + ms
    while (true) {
        waitForIdle()
        if (condition()) return
        if (System.currentTimeMillis() > until) throw AssertionError("still waiting after $ms ms")
        Thread.sleep(20)
    }
}

/** Watches [model] for [ms] and fails as soon as the terminal comes up. */
internal fun AppRule.assertStaysHome(model: RexViewModel, ms: Long = 2_000) {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
        waitForIdle()
        assertEquals("the app went to the terminal on its own", Route.Home, model.route.value)
        Thread.sleep(50)
    }
}

internal const val CONNECT_MS = 15_000L

/**
 * Whether the sheet row whose label is [label] ends in a chevron, the mark of a row that opens
 * another page: the chevron's nearest clickable ancestor is the row, and that row holds the label.
 */
internal fun AppRule.rowHasChevron(label: String): Boolean {
    val chevrons = onAllNodes(
        androidx.compose.ui.test.SemanticsMatcher.expectValue(Drawable, me.leaver.rexpocket.R.drawable.ic_chevron_right),
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
    fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
        node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }.orEmpty().map { it.text } +
            node.children.flatMap(::texts)
    return chevrons.any { chevron ->
        val row = generateSequence(chevron.parent) { it.parent }
            .firstOrNull { androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config } ?: return@any false
        label in texts(row)
    }
}

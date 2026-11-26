package me.leaver.rexpocket.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.SecurityPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The hosts start connecting as the activity is created, not after the first frame, and with the
 * app lock on not after the person unlocked: the activity is only created here, so nothing is
 * composed or drawn. Starting once: the first composition, a rotation and the process coming back
 * on the same view model connect nothing a second time. See docs/design/app-lock.md: connections
 * carry on while locked, and nothing is shown before the unlock.
 */
@RunWith(AndroidJUnit4::class)
class StartConnectsTest {
    private val desk = RexOnDesk(sessions = 1)
    private var activity: ActivityController<MainActivity>? = null

    @After
    fun stop() {
        activity?.let { a -> runCatching { model(a).disconnectAll() } }
        shadowOf(Looper.getMainLooper()).idle()
        desk.close()
    }

    private fun model(a: ActivityController<MainActivity>) = ViewModelProvider(a.get())[RexViewModel::class.java]

    private fun awaitLive(m: RexViewModel) {
        val until = System.currentTimeMillis() + CONNECT_MS
        while (m.pocket.value.phase != Phase.Live) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) throw AssertionError("not live: ${m.pocket.value.phase} ${m.pocket.value.problem}")
            Thread.sleep(20)
        }
    }

    private fun bootstraps() = desk.rex.calls.count { it.startsWith("session.bootstrap") }

    @Test
    fun aLockedColdStartConnectsBeforeAnythingIsDrawn() {
        val app = freshApp()
        desk.saveFor(app).setSecurity(SecurityPrefs(lock = true, lockAfterSeconds = 60))
        val a = Robolectric.buildActivity(MainActivity::class.java).create()
        activity = a
        val m = model(a)
        assertTrue("a cold start with the lock on is locked", m.locked.value)
        awaitLive(m)
        assertTrue("still locked", m.locked.value)
        assertEquals(1, bootstraps())

        // The first composition asks again, as RexPocketApp does; then a rotation.
        m.restore()
        a.recreate()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the same view model", m, model(a))
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("connected once", 1, bootstraps())
    }
}

package me.leaver.rexpocket.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.data.HostStore
import me.leaver.rexpocket.ui.Connections
import me.leaver.rexpocket.ui.RexOnDesk
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Background mode on a device: the whole app (MainActivity) connects to a ScriptedRex with
 * background mode on, its task is removed as a swipe from recents removes it, and the watch, the
 * connection and its alerts stay; then the connections are dropped as a new process would have
 * none, and a boot starts the watch, which connects the stored host again and alerts.
 * The real process death (START_STICKY) and a real BOOT_COMPLETED are checked from the shell in
 * device-test, after [BackgroundShellPrep] (a real reboot). See docs/design/multi-host.md, "Background".
 */
@RunWith(AndroidJUnit4::class)
class BackgroundServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var desk: RexOnDesk
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val connections get() = Connections.of(app)

    @Before
    fun setUp() {
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        desk = RexOnDesk(sessions = 1)
        val store = desk.saveFor(app, lastSession = desk.ids[0])
        store.setConnectedHosts(listOf(desk.host.id))
    }

    @After
    fun tearDown() {
        onMain { Alerts.enabled.value = false }
        onMain { connections.disconnectAll(forget = false) }
        app.stopService(Intent(app, WatchService::class.java))
        desk.close()
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun until(what: String, ms: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("still waiting after $ms ms: $what")
            Thread.sleep(50)
        }
    }

    private fun live(): Boolean {
        var phase: Phase? = null
        onMain { phase = connections.lives.value[desk.host.id]?.state?.phase }
        return phase == Phase.Live
    }

    private fun firstBlock(): String {
        var block: String? = null
        onMain { block = connections.lives.value[desk.host.id]?.state?.views?.get(desk.ids[0])?.allBlocks()?.firstOrNull()?.id }
        return block ?: throw AssertionError("no pane yet")
    }

    private fun alertUp(block: String) = manager.activeNotifications.any { it.tag == Alerts.tagOf(desk.host.id, block) && it.id == Alerts.PANE_ID }

    @Test
    fun removingTheTaskKeepsTheConnectionAndItsAlerts() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        until("connected from the app") { live() }
        onMain { Alerts.enabled.value = true }
        until("the watch started") { WatchService.running }
        // A swipe from recents: the task goes, and the activity with it.
        scenario.onActivity { it.finishAndRemoveTask() }
        until("the activity gone") { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        Thread.sleep(2_000)
        assertTrue("the watch stopped with the task", WatchService.running)
        assertTrue("the connection went with the task", live())
        until("the pane listed") { runCatching { firstBlock() }.isSuccess }
        val block = firstBlock()
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Still there?")
        until("the alert") { alertUp(block) }
        runCatching { scenario.close() }
    }

    @Test
    fun aBootStartsTheWatchWhichConnectsTheStoredHostAndAlerts() {
        HostStore(app).setAlerts(true)
        // As a new process: no connection yet, background mode on in the store.
        onMain {
            connections.disconnectAll(forget = false)
            Alerts.enabled.value = true
        }
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        until("the watch started") { WatchService.running }
        until("the stored host connected") { live() }
        until("the pane listed") { runCatching { firstBlock() }.isSuccess }
        val block = firstBlock()
        desk.rex.report(block, "done", app = "cargo", msg = "built")
        until("the alert") { alertUp(block) }
    }
}

/**
 * Leaves the app as background mode on with one host stored and connected at exit, for the shell
 * steps in device-test that kill the process and boot the phone: the host is not reachable (port
 * 9 on the phone itself), so it keeps trying, which is all those steps need to see.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundShellPrep {
    @Test
    fun storeBackgroundModeOn() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().clear().commit()
        val store = HostStore(app)
        val host = me.leaver.rexpocket.data.SavedHost(id = "shell-host", label = "shell", host = "127.0.0.1", port = 9, plaintext = true)
        store.upsert(host)
        store.setConnectedHosts(listOf(host.id))
        store.setAlerts(true)
        app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit().putLong("update.checkedAt", System.currentTimeMillis()).commit()
    }
}

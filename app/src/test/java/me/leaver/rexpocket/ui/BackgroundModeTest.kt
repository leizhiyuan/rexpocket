package me.leaver.rexpocket.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.alerts.BootReceiver
import me.leaver.rexpocket.alerts.WatchService
import me.leaver.rexpocket.core.HostBackground
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.data.BackgroundStore
import me.leaver.rexpocket.data.HostStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Background mode with no screen: the watch Android starts again after killing the process
 * (START_STICKY, a null intent) or after a boot or an update builds the connections from what was
 * stored, connects the hosts kept in the background, and alerts; a swipe from recents keeps it;
 * its notification names the hosts and disconnects one. See docs/design/multi-host.md, "Background".
 */
@RunWith(AndroidJUnit4::class)
class BackgroundModeTest {
    private val desk = RexOnDesk(sessions = 1)
    private val second by lazy { RexOnDesk(sessions = 1, hostId = "second-host", hostLabel = "second") }
    private var secondUsed = false
    private lateinit var app: Application
    private var service: ServiceController<WatchService>? = null
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    /** The app as a run with background mode on left it: [hosts] saved and connected at exit. */
    private fun storedState(vararg hosts: me.leaver.rexpocket.data.SavedHost, alerts: Boolean = true) {
        app = freshApp()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = desk.saveFor(app)
        hosts.forEach { store.upsert(it) }
        store.setConnectedHosts(hosts.map { it.id })
        store.setAlerts(alerts)
    }

    /** Android starting the watch again in a new process: onCreate, then onStartCommand(null). */
    private fun restartedByAndroid(): Int {
        val controller = Robolectric.buildService(WatchService::class.java).create()
        service = controller
        return controller.get().onStartCommand(null, 0, 1)
    }

    private val connections get() = Connections.of(ApplicationProvider.getApplicationContext())

    @After
    fun stop() {
        runCatching { connections.disconnectAll(forget = false) }
        service?.destroy()
        Alerts.enabled.value = false
        desk.close()
        if (secondUsed) second.close()
    }

    private fun await(ms: Long = 15_000, what: String = "", condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > until) throw AssertionError("still waiting after $ms ms: $what")
            Thread.sleep(10)
        }
    }

    private fun live(hostId: String) = connections.lives.value[hostId]?.state?.phase == Phase.Live

    @Test
    fun aRestartedWatchConnectsTheStoredHostsAndAlerts() {
        storedState(desk.host)
        assertEquals(android.app.Service.START_STICKY, restartedByAndroid())
        await(what = "the stored host connected again") { live(desk.host.id) && desk.ids[0] in connections.lives.value.getValue(desk.host.id).state.views }
        // In the foreground, with its notification, and nothing on screen: news is a notification.
        val watch = shadowOf(service!!.get()).lastForegroundNotification
        assertNotNull(watch)
        val block = connections.lives.value.getValue(desk.host.id).state.views.getValue(desk.ids[0]).allBlocks().first().id
        desk.rex.report(block, "blocked", kind = "question", app = "claude", msg = "Restarted?")
        await(what = "the alert") { manager.activeNotifications.any { it.tag == Alerts.tagOf(desk.host.id, block) && it.id == Alerts.PANE_ID } }
        // The watch keeps the home screen widget's counts: the host, with one tab that needs the person.
        await(what = "the widget's counts") {
            val snap = me.leaver.rexpocket.alerts.WidgetSnapshot.parse(app.getSharedPreferences("rexpocket", 0).getString("widget.snapshot", null))
            snap?.live == true && snap.hosts.singleOrNull()?.let { it.name == "desk" && it.needs == 1 } == true
        }
    }

    @Test
    fun aRestartWithBackgroundModeOffStopsAndConnectsNothing() {
        storedState(desk.host, alerts = false)
        assertEquals(android.app.Service.START_NOT_STICKY, restartedByAndroid())
        assertTrue(shadowOf(service!!.get()).isStoppedBySelf)
        assertTrue(connections.links.value.isEmpty())
    }

    @Test
    fun aHostNotKeptInTheBackgroundIsNotConnectedAndAnAlwaysConnectedOneIs() {
        secondUsed = true
        storedState(desk.host)
        HostStore(app).upsert(second.host)
        // The desk host was connected but is not kept; the second was disconnected but is always connected.
        BackgroundStore(app).setHosts(
            me.leaver.rexpocket.core.BackgroundHosts()
                .with(desk.host.id, HostBackground(stay = false))
                .with(second.host.id, HostBackground(always = true)),
        )
        restartedByAndroid()
        await(what = "the always-connected host") { live(second.host.id) }
        assertEquals(listOf(second.host.id), connections.links.value.keys.toList())
    }

    @Test
    fun swipingTheTaskAwayKeepsTheWatchWhileBackgroundModeIsOn() {
        storedState(desk.host)
        restartedByAndroid()
        await(what = "connected") { live(desk.host.id) }
        service!!.get().onTaskRemoved(Intent())
        assertFalse(shadowOf(service!!.get()).isStoppedBySelf)
        assertTrue(live(desk.host.id))
        // Off, the swipe stops it.
        Alerts.enabled.value = false
        service!!.get().onTaskRemoved(Intent())
        assertTrue(shadowOf(service!!.get()).isStoppedBySelf)
    }

    @Test
    fun theWatchNotificationNamesTheHostsAndDisconnectsOne() {
        storedState(desk.host)
        restartedByAndroid()
        await(what = "connected") { live(desk.host.id) }
        var watch: Notification? = null
        await(what = "the watch notification with the host") {
            watch = manager.activeNotifications.firstOrNull { it.id == WatchService.WATCH_ID }?.notification
            watch?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.contains("desk") == true
        }
        val disconnect = watch!!.actions.single { it.title.toString() == app.getString(R.string.bg_disconnect_host, "desk") }
        assertNotNull(watch!!.actions.firstOrNull { it.title.toString() == app.getString(R.string.alerts_stop) })
        // The action starts the watch with the host's id, as Android delivers it.
        service!!.get().onStartCommand(shadowOf(disconnect.actionIntent).savedIntent, 0, 2)
        await(what = "disconnected") { connections.links.value.isEmpty() }
        assertEquals(emptyList<String>(), HostStore(app).connectedHosts())
    }

    @Test
    fun stopWithNoScreenTurnsBackgroundModeOffAndCloses() {
        storedState(desk.host)
        restartedByAndroid()
        await(what = "connected") { live(desk.host.id) }
        service!!.get().onStartCommand(Intent(app, WatchService::class.java).setAction("me.leaver.rexpocket.alerts.STOP"), 0, 2)
        assertFalse(HostStore(app).alerts())
        await(what = "closed") { connections.links.value.isEmpty() }
        // Still connected at exit, so the next start connects it again.
        assertEquals(listOf(desk.host.id), HostStore(app).connectedHosts())
    }

    /**
     * Stop leaves a quiet note that background mode is off, with Turn on: the way back is where
     * the Stop was. Turn on starts the watch again, which connects the hosts kept in the
     * background, and the note goes.
     */
    @Test
    fun stopLeavesANoteWhoseTurnOnBringsBackgroundModeBack() {
        storedState(desk.host)
        restartedByAndroid()
        await(what = "connected") { live(desk.host.id) }
        service!!.get().onStartCommand(Intent(app, WatchService::class.java).setAction("me.leaver.rexpocket.alerts.STOP"), 0, 2)
        val note = manager.activeNotifications.single { it.id == Alerts.STOPPED_ID }.notification
        assertEquals(app.getString(R.string.bg_off_title), note.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val turnOn = note.actions.single { it.title.toString() == app.getString(R.string.bg_turn_on_short) }
        assertTrue("Turn on starts the watch as a foreground service", shadowOf(turnOn.actionIntent).isForegroundService)
        await(what = "closed") { connections.links.value.isEmpty() }
        service!!.get().onStartCommand(shadowOf(turnOn.actionIntent).savedIntent, 0, 3)
        assertTrue(Alerts.enabled.value)
        await(what = "the kept host back") { live(desk.host.id) }
        assertTrue(manager.activeNotifications.none { it.id == Alerts.STOPPED_ID })
    }

    /** Android stopping background mode is kept for the page, which shows it until the watch runs again. */
    @Test
    fun androidsStopIsRecordedUntilTheWatchRunsAgain() {
        storedState(desk.host)
        Alerts.recordAndroidStop(app, at = 1_000L)
        assertEquals(1_000L, Alerts.androidStop(app))
        restartedByAndroid()
        await(what = "connected") { live(desk.host.id) }
        assertNull(Alerts.androidStop(app))
    }

    /**
     * A host that goes while the watch runs stays in its notification as off, with Connect, as a
     * tailnet that is off does; one item leaves room for Manage, which opens the page that has them all.
     */
    @Test
    fun aHostThatWentStaysAsOffWithConnect() {
        secondUsed = true
        storedState(desk.host, second.host)
        restartedByAndroid()
        await(what = "both connected") { live(desk.host.id) && live(second.host.id) }
        fun watch() = manager.activeNotifications.firstOrNull { it.id == WatchService.WATCH_ID }?.notification
        fun titles() = watch()?.actions.orEmpty().map { it.title.toString() }
        await(what = "two Disconnects") { titles().contains(app.getString(R.string.bg_disconnect_host, "second")) }
        connections.disconnect(second.host.id)
        await(what = "second kept as off with Connect: ${titles()}") { titles().contains(app.getString(R.string.bg_connect_host, "second")) }
        assertTrue(titles().contains(app.getString(R.string.bg_disconnect_host, "desk")))
        val connect = watch()!!.actions.single { it.title.toString() == app.getString(R.string.bg_connect_host, "second") }
        service!!.get().onStartCommand(shadowOf(connect.actionIntent).savedIntent, 0, 2)
        await(what = "second connected again") { live(second.host.id) }
    }

    @Test
    fun bootAndUpdateStartTheWatchOnlyWithBackgroundModeOn() {
        storedState(desk.host)
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("me.leaver.rexpocket.alerts.RESTORE", shadow.nextStartedService?.action)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(WatchService::class.java.name, shadow.nextStartedService?.component?.className)
        HostStore(app).setAlerts(false)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadow.nextStartedService)
    }
}

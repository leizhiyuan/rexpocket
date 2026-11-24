package me.leaver.rexpocket.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.alerts.Alerts
import me.leaver.rexpocket.alerts.RexTile2
import me.leaver.rexpocket.alerts.TileStore
import me.leaver.rexpocket.alerts.WatchService
import me.leaver.rexpocket.core.BackgroundKeep
import me.leaver.rexpocket.core.Connectable
import me.leaver.rexpocket.core.Phase
import me.leaver.rexpocket.core.TailnetBackend
import me.leaver.rexpocket.core.TailnetPolicies
import me.leaver.rexpocket.core.TailnetPolicy
import me.leaver.rexpocket.data.TailnetPolicyStore
import me.leaver.rexpocket.data.TailnetSettings
import me.leaver.rexpocket.net.TailnetEntry
import me.leaver.rexpocket.net.TailnetNodes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import java.net.InetSocketAddress

/**
 * A tailnet in the background, with no screen (the watch restarted by Android, a boot): kept
 * Always, it holds the watch with no host; following its hosts, it comes up with them and not
 * without; let go, it keeps its hosts from connecting too. See docs/design/tailnet-control.md.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundTailnetTest {
    private val desk = RexOnDesk(sessions = 1)
    private val node = FakeTailnetNode()
    private val socks = FakeSocks { _, _ -> InetSocketAddress("127.0.0.1", desk.rex.endpoint.port) }
    private lateinit var app: Application
    private lateinit var connections: Connections
    private var service: ServiceController<WatchService>? = null

    /** The desk's Rex as a host on the tailnet: the node's proxy leads to it. */
    private val tailnetHost by lazy { desk.host.copy(id = "tail-host", host = "mac.tail1234.ts.net", viaTailscale = true) }

    private fun stored(policy: TailnetPolicy, connected: Boolean) {
        app = freshApp()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val store = desk.saveFor(app)
        store.delete(desk.host.id)
        store.upsert(tailnetHost)
        store.setConnectedHosts(if (connected) listOf(tailnetHost.id) else emptyList())
        store.setAlerts(true)
        store.setTailnet(TailnetSettings(TailnetBackend.Tailscale, enabled = true, hostname = "pixel-8"))
        TailnetPolicyStore(app).write(TailnetPolicies().with("tailscale", policy))
        node.socksPort = socks.port
        node.state.value = TailnetSamples.connected()
        // The process's connections on the stand-in node, as the watch then finds them.
        connections = Connections.of(app, TailnetNodes(listOf(TailnetEntry(TailnetBackend.Tailscale, node))))
    }

    private fun restartedByAndroid(): Int {
        val controller = Robolectric.buildService(WatchService::class.java).create()
        service = controller
        return controller.get().onStartCommand(null, 0, 1)
    }

    private fun await(ms: Long = 15_000, what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            if (System.currentTimeMillis() > until) throw AssertionError("still waiting after $ms ms: $what")
            Thread.sleep(10)
        }
    }

    @After
    fun stop() {
        runCatching { connections.closeWithScreen() }
        service?.destroy()
        Alerts.enabled.value = false
        socks.close()
        desk.close()
    }

    @Test
    fun aTailnetKeptAlwaysHoldsTheWatchWithNoHost() {
        stored(TailnetPolicy(keep = BackgroundKeep.Always), connected = false)
        assertEquals(Service.START_STICKY, restartedByAndroid())
        await(what = "the node started") { node.starts > 0 && connections.control.run("tailscale").running }
        assertTrue(connections.links.value.isEmpty())
        assertNotNull(shadowOf(service!!.get()).lastForegroundNotification)
    }

    @Test
    fun aTailnetFollowingItsHostsDoesNotComeUpWithoutOne() {
        stored(TailnetPolicy(), connected = false)
        assertEquals(Service.START_NOT_STICKY, restartedByAndroid())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, node.starts)
    }

    @Test
    fun aTailnetFollowingItsHostsComesUpWithThem() {
        stored(TailnetPolicy(), connected = true)
        assertEquals(Service.START_STICKY, restartedByAndroid())
        await(what = "the host on the tailnet connected") { connections.lives.value[tailnetHost.id]?.state?.phase == Phase.Live }
        assertTrue(connections.control.run("tailscale").running)
    }

    @Test
    fun aTailnetLetGoInTheBackgroundKeepsItsHostsFromConnecting() {
        stored(TailnetPolicy(keep = BackgroundKeep.Off), connected = true)
        assertEquals(Service.START_NOT_STICKY, restartedByAndroid())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(connections.links.value.isEmpty())
        assertEquals(0, node.starts)
    }

    @Test
    fun aTailnetDisconnectedByHandStaysOffInTheBackground() {
        stored(TailnetPolicy(keep = BackgroundKeep.Always), connected = true)
        TailnetPolicyStore(app).write(TailnetPolicies().with("tailscale", TailnetPolicy(keep = BackgroundKeep.Always)).switched("tailscale", on = false))
        connections.control.policies.value = TailnetPolicyStore(app).read()
        assertEquals(Service.START_NOT_STICKY, restartedByAndroid())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(connections.links.value.isEmpty())
        assertEquals(0, node.starts)
    }

    @Test
    fun theWatchNotificationShowsTheTailnetAloneWithItsActionAndTheActionDisconnectsIt() {
        stored(TailnetPolicy(keep = BackgroundKeep.Always), connected = false)
        restartedByAndroid()
        await(what = "the node started") { connections.control.run("tailscale").running }
        val manager = app.getSystemService(NotificationManager::class.java)
        var watch: Notification? = null
        await(what = "the watch names the tailnet") {
            watch = manager.activeNotifications.firstOrNull { it.id == WatchService.WATCH_ID }?.notification
            watch?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == app.getString(R.string.bg_watch_tailnet_title, "Tailscale")
        }
        // Stop, then the tailnet's own action: one each while every host and tailnet fits.
        val titles = watch!!.actions.map { it.title.toString() }
        assertEquals(listOf(app.getString(R.string.alerts_stop), app.getString(R.string.tailnet_disconnect_item, "Tailscale"), app.getString(R.string.bg_manage)), titles)
        val stops = node.stops
        service!!.get().onStartCommand(shadowOf(watch!!.actions[1].actionIntent).savedIntent, 0, 2)
        await(what = "the tailnet disconnected by hand") { connections.control.policies.value.isOff("tailscale") && node.stops > stops }
    }

    @Test
    fun aTileBoundToTheTailnetConnectsItAndDisconnectsIt() {
        stored(TailnetPolicy(keep = BackgroundKeep.Always), connected = false)
        TailnetPolicyStore(app).write(TailnetPolicies().switched("tailscale", on = false))
        connections.control.policies.value = TailnetPolicyStore(app).read()
        TileStore(app).bind(2, Connectable.tailnet("tailscale"))
        Alerts.enabled.value = true
        val tile = Robolectric.buildService(RexTile2::class.java).create().get()
        tile.onClick()
        await(what = "the tile connected the tailnet") { node.starts > 0 && !connections.control.policies.value.isOff("tailscale") }
        // With no host on it, a second tap disconnects at once.
        tile.onClick()
        await(what = "the tile disconnected it") { connections.control.policies.value.isOff("tailscale") }
        assertEquals(2, TileStore(app).slotOf(Connectable.tailnet("tailscale")))
        // The page's Add a tile finds it on its slot, and a host goes on the next free one.
        assertEquals(2, TileStore(app).slotFor(Connectable.tailnet("tailscale")))
        assertEquals(1, TileStore(app).slotFor(Connectable.host("tail-host")))
    }
}

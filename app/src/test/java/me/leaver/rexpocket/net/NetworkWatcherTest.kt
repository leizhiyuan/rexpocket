package me.leaver.rexpocket.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * A network that comes is a reason to dial at once. Only the network the phone already had when
 * the watch started is not: its callback is the system telling the watch what is there.
 */
@RunWith(RobolectricTestRunner::class)
class NetworkWatcherTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private fun callbacks() = shadowOf(connectivity).networkCallbacks.toList()

    /**
     * The app opened in a tunnel, on a plane, from a notification with no signal: every host fails
     * and backs off. When the network comes the hosts dial at once, instead of waiting out a backoff
     * of up to a minute while the person looks at "Can't reach".
     */
    @Test
    fun aNetworkThatComesAfterAStartWithNoneIsAChange() {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        var changes = 0
        val watcher = NetworkWatcher(context) { changes++ }
        watcher.start()
        callbacks().forEach { it.onAvailable(ShadowNetwork.newInstance(7)) }
        assertEquals("a network came after none", 1, changes)
        watcher.stop()
    }

    @Test
    fun theNetworkThereAtTheStartIsNoChange() {
        val wifi = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, NetworkInfo.State.CONNECTED)
        shadowOf(connectivity).setActiveNetworkInfo(wifi)
        val active = connectivity.activeNetwork!!
        var changes = 0
        val watcher = NetworkWatcher(context) { changes++ }
        watcher.start()
        callbacks().forEach { it.onAvailable(active) }
        assertEquals("the network that was there", 0, changes)
        // Mobile data after Wi-Fi is a change, and Wi-Fi again after a gap with none.
        val mobile = ShadowNetwork.newInstance(4242)
        callbacks().forEach { it.onAvailable(mobile) }
        assertEquals(1, changes)
        callbacks().forEach { it.onLost(mobile) }
        callbacks().forEach { it.onAvailable(active) }
        assertEquals(2, changes)
        watcher.stop()
    }
}

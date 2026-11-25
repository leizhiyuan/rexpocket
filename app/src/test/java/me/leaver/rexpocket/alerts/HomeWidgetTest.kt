package me.leaver.rexpocket.alerts

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The widget's stored counts and its footer, and the background tile's words. */
@RunWith(AndroidJUnit4::class)
class HomeWidgetTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun aSnapshotReadsBackAsWritten() {
        val snap = WidgetSnapshot(1_700_000_000_000L, live = true, listOf(WidgetHost("mac", 2, 1, 1, 3), WidgetHost("pi", 0, 0, 0, 0)))
        assertEquals(snap, WidgetSnapshot.parse(snap.toJson()))
        assertNull(WidgetSnapshot.parse(null))
        assertNull(WidgetSnapshot.parse("not json"))
    }

    /** Kept fresh by the watch, it says when; with background mode off, that the counts may be stale. */
    @Test
    fun theFooterSaysWhetherTheCountsMayBeStale() {
        val at = System.currentTimeMillis()
        val fresh = HomeWidget.footer(app, WidgetSnapshot(at, live = true, emptyList()))
        val stale = HomeWidget.footer(app, WidgetSnapshot(at, live = false, emptyList()))
        assertTrue(fresh, fresh.startsWith(app.getString(R.string.widget_updated, "").trim()))
        assertTrue(stale, stale.startsWith(app.getString(R.string.widget_stale, "").substringBefore("·").trim()))
    }

    @Test
    fun theBackgroundTileSaysOnOffOrWhatItNeeds() {
        assertEquals(app.getString(R.string.tile_background_allow), BackgroundTile.subtitle(app, on = false, hosts = 0))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(app.getString(R.string.alerts_off), BackgroundTile.subtitle(app, on = false, hosts = 0))
        assertEquals(app.getString(R.string.alerts_on), BackgroundTile.subtitle(app, on = true, hosts = 0))
        assertEquals(app.getString(R.string.alerts_on) + " · 2 hosts", BackgroundTile.subtitle(app, on = true, hosts = 2))
    }
}

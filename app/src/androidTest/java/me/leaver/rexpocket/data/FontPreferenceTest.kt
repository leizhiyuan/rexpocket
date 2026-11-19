package me.leaver.rexpocket.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontPreferenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)
    private var previousFont: String? = null
    private var previousLegacy: Boolean? = null

    @Before fun save() {
        previousFont = prefs.getString("terminal_font", null)
        previousLegacy = if (prefs.contains("downloaded_font")) prefs.getBoolean("downloaded_font", false) else null
        prefs.edit().remove("terminal_font").remove("downloaded_font").commit()
    }

    @After fun restore() {
        val edit = prefs.edit().remove("terminal_font").remove("downloaded_font")
        previousFont?.let { edit.putString("terminal_font", it) }
        previousLegacy?.let { edit.putBoolean("downloaded_font", it) }
        edit.commit()
    }

    @Test fun defaultsToSystemAndMigratesLegacySelection() {
        val store = HostStore(context)
        assertEquals(SYSTEM_FONT, store.terminalFont())
        prefs.edit().putBoolean("downloaded_font", true).commit()
        assertEquals("jetbrains-mono", store.terminalFont())
        store.setTerminalFont("hack-nerd")
        assertEquals("hack-nerd", HostStore(context).terminalFont())
        store.setTerminalFont(SYSTEM_FONT)
        assertEquals(SYSTEM_FONT, HostStore(context).terminalFont())
    }
}

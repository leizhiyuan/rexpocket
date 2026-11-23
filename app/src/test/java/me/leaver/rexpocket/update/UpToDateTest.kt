package me.leaver.rexpocket.update

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.ui.relativeTime
import me.leaver.rexpocket.ui.upToDateText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Updates row says when the phone last found nothing newer, from what the updater kept. */
@RunWith(AndroidJUnit4::class)
class UpToDateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val hour = 3_600_000L

    private fun prefs(checkedAt: Long, upToDateAt: Long) {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("rexpocket", Context.MODE_PRIVATE).edit()
            .clear().putLong("update.checkedAt", checkedAt).putLong("update.upToDateAt", upToDateAt).commit()
    }

    @Test
    fun aLaunchAfterACheckThatFoundNothingSaysSo() {
        val at = System.currentTimeMillis() - 2 * hour
        prefs(checkedAt = at, upToDateAt = at)
        assertEquals(UpdateState.UpToDate(at), Updater(ApplicationProvider.getApplicationContext()).state.value)
    }

    @Test
    fun aLaterCheckThatFoundABuildSaysNothing() {
        val at = System.currentTimeMillis() - 5 * hour
        prefs(checkedAt = at + hour, upToDateAt = at)
        assertEquals(UpdateState.Idle, Updater(ApplicationProvider.getApplicationContext()).state.value)
    }

    @Test
    fun theRowSaysWhenItChecked() {
        val now = System.currentTimeMillis()
        compose.setContent {
            Text(upToDateText(now - 2 * hour, now))
            Text(upToDateText(now - 10_000, now))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.update_current_checked, relativeTime(now - 2 * hour, now))).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.update_current_just_now)).assertIsDisplayed()
    }
}

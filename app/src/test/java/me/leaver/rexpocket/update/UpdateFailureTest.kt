package me.leaver.rexpocket.update

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.leaver.rexpocket.R
import me.leaver.rexpocket.ui.updateDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failed check or download says why in a few words, and that a tap tries again. */
@RunWith(AndroidJUnit4::class)
class UpdateFailureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theCauseIsToldFromWhatFailed() {
        assertEquals(UpdateFailure.Offline, updateFailure(UnknownHostException("api.github.com")))
        assertEquals(UpdateFailure.Offline, updateFailure(SocketTimeoutException("timeout")))
        assertEquals(UpdateFailure.RateLimited, updateFailure(HttpStatusException(403, "releases")))
        assertEquals(UpdateFailure.RateLimited, updateFailure(HttpStatusException(429, "releases")))
        assertEquals(UpdateFailure.Other, updateFailure(HttpStatusException(500, "releases")))
        assertEquals(UpdateFailure.Checksum, updateFailure(ChecksumException("checksum does not match")))
        assertEquals(UpdateFailure.Other, updateFailure(IOException("cannot keep the download")))
    }

    @Test
    fun theSettingsRowSaysTheCauseNotTheDetail() {
        compose.setContent { Text(updateDetail(UpdateState.Failed("releases answered HTTP 403", UpdateFailure.RateLimited)).orEmpty()) }
        compose.onNodeWithText(compose.activity.getString(R.string.update_failed_rate_limited)).assertIsDisplayed()
    }
}

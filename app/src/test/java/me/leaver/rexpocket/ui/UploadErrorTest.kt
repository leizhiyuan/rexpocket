package me.leaver.rexpocket.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.UploadPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Why a file did not go is said in the phone's language: staging fails with a typed reason, and the
 * pill under the pane words it from the app's strings.
 */
@RunWith(AndroidJUnit4::class)
class UploadErrorTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun pill(error: UploadError) {
        compose.setContent {
            TermOnModel(termModel(), liveState(), upload = UploadStatus("notes.txt", UploadPhase.Failed, error = error))
        }
        compose.waitForIdle()
    }

    @Test
    fun anEmptyFileIsRefusedWithAReasonThePillWords() {
        val empty = File(compose.activity.cacheDir, "empty.txt").apply { writeText("") }
        val reason = runBlocking {
            try {
                stageUpload(compose.activity, Uri.fromFile(empty))
                fail("an empty file was staged")
                null
            } catch (e: UploadStageException) {
                e.error
            }
        }
        assertEquals(UploadError.Empty, reason)
        pill(reason!!)
        compose.onNodeWithText(compose.activity.getString(R.string.upload_error_empty)).assertIsDisplayed()
    }

    @Test
    fun whatRexSaidIsShownAsItSaidIt() {
        pill(UploadError.Said("The pane is no longer available"))
        compose.onNodeWithText("The pane is no longer available").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun inTraditionalChinese() {
        pill(UploadError.TooLarge)
        compose.onNodeWithText("請選擇小於 100 MiB 的檔案").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun inSimplifiedChinese() {
        pill(UploadError.TooLarge)
        compose.onNodeWithText("请选择小于 100 MiB 的文件").assertIsDisplayed()
    }
}

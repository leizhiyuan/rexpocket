package me.leaver.rexpocket.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the pane tells the phone keyboard about itself. Everything typed into a terminal goes through
 * one field, the passwords at a sudo or ssh prompt included, so the keyboard must learn none of it.
 */
@RunWith(RobolectricTestRunner::class)
class TerminalImeAttrsTest {
    private fun attrs(): EditorInfo = EditorInfo().also {
        TerminalImeView(ApplicationProvider.getApplicationContext()).onCreateInputConnection(it)
    }

    @Test
    fun `the keyboard is told not to learn what is typed`() {
        assertTrue(
            "IME_FLAG_NO_PERSONALIZED_LEARNING must be set",
            attrs().imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
        )
    }

    @Test
    fun `it is still a plain text field with no suggestions and no action`() {
        val info = attrs()
        assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType and InputType.TYPE_MASK_CLASS)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(EditorInfo.IME_ACTION_NONE, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
    }
}

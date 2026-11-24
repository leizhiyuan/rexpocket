package me.leaver.rexpocket.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a share intent becomes: text to paste, or files to upload. Only other apps' content URIs
 * are taken, so a crafted share cannot send this app's own files (a `file:` path into its data, or
 * its own provider) to a pane.
 */
@RunWith(AndroidJUnit4::class)
class ShareIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun send(vararg uris: Uri, text: String? = null): Intent =
        if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
        } else {
            Intent(Intent.ACTION_SEND).setType("*/*").apply { uris.firstOrNull()?.let { putExtra(Intent.EXTRA_STREAM, it) } }
        }.apply { if (text != null) putExtra(Intent.EXTRA_TEXT, text) }

    @Test
    fun textIsPasted() {
        val got = readShare(context, send(text = "ls -la"))!!
        assertEquals("ls -la", got.text)
        assertEquals(emptyList<SharedFile>(), got.files)
    }

    @Test
    fun filesWinOverTheirCaption() {
        val photo = Uri.parse("content://media.example/images/42/photo.jpg")
        val got = readShare(context, send(photo, text = "look"))!!
        assertNull(got.text)
        assertEquals(listOf(photo), got.files.map { it.uri })
        assertEquals("photo.jpg", got.files.single().name)
    }

    @Test
    fun severalFilesComeTogether() {
        val a = Uri.parse("content://files.example/a.txt")
        val b = Uri.parse("content://files.example/b.txt")
        assertEquals(listOf(a, b), readShare(context, send(a, b, a))!!.files.map { it.uri })
    }

    @Test
    fun thisAppsOwnFilesAreNeverTaken() {
        val ownData = Uri.fromFile(context.filesDir.resolve("../shared_prefs/rexpocket.xml"))
        val ownProvider = Uri.parse("content://${context.packageName}.fileprovider/diagnostics/report.txt")
        assertNull(readShare(context, send(ownData)))
        assertNull(readShare(context, send(ownProvider)))
        assertNull(readShare(context, Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x")))
    }
}

package me.leaver.rexpocket.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FontFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val contents = mapOf("regular.ttf" to "regular font", "bold.ttf" to "bold font")
    private val assets = contents.map { (name, body) ->
        FontAsset(name, MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) })
    }
    private fun store() = FontFiles(temp.root, assets)
    private fun fetch(asset: FontAsset, file: File) { file.writeText(contents.getValue(asset.name)) }

    @Test fun completeFamilyIsVerifiedAndReusedOffline() {
        val files = store()
        val progress = mutableListOf<Float>()
        val directory = files.install(progress = { progress += it }, fetch = ::fetch)
        assertTrue(files.available())
        assertEquals(listOf(0.5f, 1f), progress)
        assertEquals(directory, store().install(fetch = { _, _ -> error("Must not download saved fonts") }))
    }

    @Test fun checksumFailureDoesNotInstallAnyFont() {
        val files = store()
        assertThrows(IllegalStateException::class.java) {
            files.install(fetch = { _, file -> file.writeText("corrupt") })
        }
        assertFalse(files.available())
        assertFalse(files.directory.exists())
        assertFalse(File(temp.root, "jetbrains-mono-v1.partial").exists())
    }

    @Test fun interruptedDownloadCanBeRetried() {
        val files = store()
        assertThrows(IOException::class.java) {
            files.install(fetch = { asset, file ->
                if (asset.name == "bold.ttf") throw IOException("Offline")
                fetch(asset, file)
            })
        }
        assertFalse(files.available())
        files.install(fetch = ::fetch)
        assertTrue(files.available())
    }

    @Test fun corruptedSavedFileIsDetectedAndRepaired() {
        val files = store()
        files.install(fetch = ::fetch)
        File(files.directory, "bold.ttf").writeText("corrupted later")
        assertFalse(files.available())
        files.install(fetch = ::fetch)
        assertTrue(files.available())
    }

    @Test fun unfinishedPreviousProcessIsDiscarded() {
        val partial = File(temp.root, "jetbrains-mono-v1.partial").apply { mkdirs() }
        File(partial, "unverified.ttf").writeText("partial")
        val files = store()
        assertFalse(files.available())
        files.install(fetch = ::fetch)
        assertTrue(files.available())
        assertFalse(partial.exists())
        assertFalse(File(files.directory, "unverified.ttf").exists())
    }

    @Test fun missingFamilyMemberIsUnavailable() {
        val files = store()
        files.install(fetch = ::fetch)
        File(files.directory, "regular.ttf").delete()
        assertFalse(files.available())
    }
}

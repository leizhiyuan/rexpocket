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
    private val font get() = FontCatalog.fonts.first().copy(assets = assets)
    private fun store() = FontFiles(temp.root, font)
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

    @Test fun differentFamiliesHaveIndependentCaches() {
        val first = store()
        val second = FontFiles(temp.root, font.copy(id = "hack-nerd"))
        first.install(fetch = ::fetch)
        assertFalse(second.available())
        second.install(fetch = ::fetch)
        assertNotEquals(first.directory, second.directory)
        File(second.directory, "regular.ttf").delete()
        assertTrue(first.available())
        assertFalse(second.available())
    }

    @Test fun nerdFontLargerThanOneMegabyteIsAccepted() {
        val body = ByteArray(3_000_000) { 42 }
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val files = FontFiles(temp.root, font.copy(assets = listOf(FontAsset("regular.ttf", hash))))
        files.install(fetch = { _, file -> file.writeBytes(body) })
        assertTrue(files.available())
    }

    @Test fun oversizedFileIsRejectedWithoutReplacingPreviousFamily() {
        val files = store()
        files.install(fetch = ::fetch)
        val oversized = File(temp.root, "oversized.ttf")
        java.io.RandomAccessFile(oversized, "rw").use { it.setLength(16_777_217L) }
        val replacement = FontFiles(temp.root, font.copy(assets = listOf(FontAsset("regular.ttf", "0".repeat(64)))))
        assertThrows(IllegalStateException::class.java) {
            replacement.install(fetch = { _, file -> oversized.copyTo(file) })
        }
        assertTrue(files.available())
    }
}

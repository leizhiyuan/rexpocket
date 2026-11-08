package me.leaver.rexpocket.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class FontAsset(val name: String, val sha256: String)

/** Installs a complete verified family; incomplete downloads never become the active font. */
internal class FontFiles(private val root: File, private val assets: List<FontAsset> = JETBRAINS) {
    val directory = File(root, "jetbrains-mono-v1")

    fun available(): Boolean = assets.all { matches(File(directory, it.name), it.sha256) }

    fun install(
        progress: (Float) -> Unit = {},
        fetch: (FontAsset, File) -> Unit = ::download,
    ): File {
        if (available()) return directory
        check(root.isDirectory || root.mkdirs())
        val staging = File(root, "jetbrains-mono-v1.partial")
        staging.deleteRecursively()
        check(staging.mkdirs())
        try {
            assets.forEachIndexed { index, asset ->
                val target = File(staging, asset.name)
                fetch(asset, target)
                check(matches(target, asset.sha256)) { "Font checksum mismatch" }
                progress((index + 1f) / assets.size)
            }
            directory.deleteRecursively()
            check(staging.renameTo(directory)) { "Could not save fonts" }
            return directory
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun matches(file: File, expected: String): Boolean = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected
    }.getOrDefault(false)

    private fun download(asset: FontAsset, target: File) {
        val connection = URL("$SOURCE/${asset.name}").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            check(connection.responseCode == 200) { "Font download failed" }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_BYTES) { "Font download too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 1_048_576L
        private const val SOURCE = "https://raw.githubusercontent.com/JetBrains/JetBrainsMono/19371302b95d218af43299bce79ddbddd0bc364d/fonts/ttf"
        private val JETBRAINS = listOf(
            FontAsset("JetBrainsMono-Regular.ttf", "e6fd0d7e91550b3ed2b735d4312474362c4716edc4fc0577a0f61ed782d5aed1"),
            FontAsset("JetBrainsMono-Bold.ttf", "d22c4f3821d725eb01210d278d95dfcfcaadc34699a06658d47c8a5cc5830ada"),
            FontAsset("JetBrainsMono-Italic.ttf", "6dc8e1322d4b2013b64e277bf66abc6748207c46a590471d4296876c2c235d0c"),
            FontAsset("JetBrainsMono-BoldItalic.ttf", "cec9b489af5c98a94cb8d8d8708ef1640cca0f296b207de1f50b2dd95e967bdf"),
        )
    }
}

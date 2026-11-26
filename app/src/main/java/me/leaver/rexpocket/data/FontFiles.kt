package me.leaver.rexpocket.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal data class FontAsset(
    val name: String,
    val sha256: String,
    val folder: String = "",
    val weight: Int = 400,
    val italic: Boolean = false,
)

/** Installs a complete verified family; incomplete downloads never become the active font. */
internal class FontFiles(private val root: File, private val font: TerminalFont) {
    private val assets = font.assets
    val directory = File(root, font.cacheKey)

    fun available(): Boolean = assets.all { matches(File(directory, it.name), it.sha256) }

    fun install(
        progress: (Float) -> Unit = {},
        fetch: (FontAsset, File) -> Unit = ::download,
    ): File {
        if (available()) return directory
        check(root.isDirectory || root.mkdirs())
        val staging = File(root, "${font.cacheKey}.partial")
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
        val path = listOf(font.source, asset.folder, asset.name).filter { it.isNotEmpty() }.joinToString("/")
        val connection = URL(path).openConnection() as HttpURLConnection
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
        private const val MAX_BYTES = 16_777_216L
    }
}

/**
 * The font check at start: whether [selected]'s family is installed and whole is told to
 * [onSelected] first, so the terminal gets its face without waiting for the rest; then every other
 * family of [ids] is checked, for the font dialog. Each family is read (hashed) once. Gives back the
 * installed ones.
 */
internal fun installedFonts(
    selected: String,
    ids: Collection<String>,
    available: (String) -> Boolean,
    onSelected: (Boolean) -> Unit,
): Set<String> {
    val chosen = selected in ids && available(selected)
    onSelected(chosen)
    return ids.filter { it != selected && available(it) }.toSet() + listOfNotNull(selected.takeIf { chosen })
}

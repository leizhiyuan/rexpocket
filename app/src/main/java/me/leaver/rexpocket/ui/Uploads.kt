package me.leaver.rexpocket.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.core.FileUpload
import me.leaver.rexpocket.core.UploadPhase
import java.io.File
import java.util.UUID

/** What the pill under the pane says about the file on its way to the desktop. */
data class UploadStatus(
    val name: String,
    val phase: UploadPhase,
    val sent: Long = 0,
    val total: Long = 0,
    val error: UploadError? = null,
    /** True when the staged copy is still there, so a failed attempt can be sent again. */
    val retryable: Boolean = false,
) {
    val progress: Float
        get() = if (total > 0) (sent.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

    /** True while cancelling would stop real work, rather than dismiss a finished one. */
    val running: Boolean
        get() = phase == UploadPhase.Preparing || phase == UploadPhase.Sending || phase == UploadPhase.Finishing
}

internal data class StagedFile(val file: File, val name: String)

/** Why a file did not go, as the pill words it ([uploadErrorText]). */
sealed interface UploadError {
    /** The picker's file went away before it could be read. */
    data object Gone : UploadError

    /** Over Rex's 100 MiB. */
    data object TooLarge : UploadError

    data object Empty : UploadError

    /** The file could not be read for another reason. */
    data object Unreadable : UploadError

    /** Sending failed, and nothing more specific is known. */
    data object Failed : UploadError

    /** What Rex or the upload said: already a sentence. */
    data class Said(val message: String) : UploadError
}

/** Staging stopped for [error]. */
internal class UploadStageException(val error: UploadError) : Exception(error.toString())

/**
 * Copies a picked [uri] into the cache. Rex needs a file whose byte count will not change under it,
 * and a content URI is not a file: the picker may hand over a stream that ends only once read.
 */
internal suspend fun stageUpload(context: Context, uri: Uri): StagedFile = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "uploads").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - UPLOAD_CACHE_MS
    directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(directory, UUID.randomUUID().toString())
    try {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw UploadStageException(UploadError.Gone)
        source.use { input ->
            file.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > FileUpload.MAX_BYTES) throw UploadStageException(UploadError.TooLarge)
                    sink.write(buffer, 0, count)
                }
            }
        }
        if (file.length() <= 0) throw UploadStageException(UploadError.Empty)
        StagedFile(file, uploadName(context, uri))
    } catch (e: Throwable) {
        file.delete()
        throw e
    }
}

/** The name the desktop should see: the final path component, without control characters. */
internal fun uploadName(context: Context, uri: Uri): String {
    val queried = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
        }
    }.getOrNull()
    val raw = queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
    val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
    return base.filter { !it.isISOControl() }.trim().take(200).ifBlank { "file" }
}

/** A picked copy outlives one upload attempt, but not a day of them. */
private const val UPLOAD_CACHE_MS = 24L * 60 * 60 * 1000

package me.leaver.rexpocket.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.core.AppRelease
import me.leaver.rexpocket.core.AppVersion
import me.leaver.rexpocket.core.UpdateApk
import me.leaver.rexpocket.core.newestFor
import me.leaver.rexpocket.diagnostics.Diagnostics
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** What the app knows about a newer build, from the check through to the downloaded APK. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** A newer release is published; nothing has been downloaded yet. */
    data class Available(val tag: String, val size: Long) : UpdateState

    data class Downloading(val tag: String, val received: Long, val total: Long) : UpdateState {
        val percent: Int get() = if (total <= 0) 0 else (received * 100 / total).toInt().coerceIn(0, 100)
    }

    data class Ready(val tag: String, val file: File) : UpdateState
    data class Failed(val detail: String) : UpdateState
}

/** What became of a tap on Install. */
enum class InstallResult {
    /** The system installer is open on the downloaded APK. */
    Started,

    /** Android does not let this app install packages yet; the setting is open on it. */
    NeedsPermission,

    /** Nothing has been downloaded. */
    Nothing,
}

/**
 * The app's own updater, against its GitHub releases.
 *
 * It runs the version it was built as: a pre-release build takes pre-releases, so an alpha keeps
 * following the alphas, while a finished build only takes finished ones. A check only says what is
 * published - once a day when the app starts, and whenever the settings row is tapped. Nothing is
 * downloaded until it is asked for, and the APK is then checked against the size and the SHA-256 the
 * feed gives before Android's installer is offered it.
 */
class Updater(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("rexpocket", Context.MODE_PRIVATE)
    private val dir = File(app.cacheDir, "updates")
    private val current: AppVersion? = AppVersion.parse(BuildConfig.VERSION_NAME)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The release the last check offered, kept so that downloading it needs no second look. */
    private var offered: AppRelease? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Looks for a newer release. A check that is not [force] is skipped when one already ran today,
     * so starting the app often does not hammer the feed; the settings row always asks.
     */
    suspend fun check(force: Boolean = false) = withContext(Dispatchers.IO) {
        val running = current
        if (running == null) {
            Diagnostics.event(TAG, "running version is not a version: ${BuildConfig.VERSION_NAME}")
            _state.value = UpdateState.Failed("unreadable version")
            return@withContext
        }
        // Something is already offered or on its way: the tap on it is next, not another check.
        when (_state.value) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready -> return@withContext
            else -> Unit
        }
        if (!force && checkedToday()) return@withContext

        _state.value = UpdateState.Checking
        val release = try {
            val feed = get(FEED)
            if (!feed.isSuccessful) throw IOException("releases answered HTTP ${feed.code}")
            AppRelease.parseFeed(feed.body?.string().orEmpty()).newestFor(running)
        } catch (e: IOException) {
            // Offline or GitHub is down: nothing to say while it is only the app starting up.
            Diagnostics.event(TAG, "check failed: ${e.message}")
            _state.value = UpdateState.Failed(e.message ?: "unreachable")
            return@withContext
        }
        remember()
        val apk = release?.apk
        if (release == null || apk == null) {
            Diagnostics.event(TAG, "no build newer than ${running}")
            _state.value = UpdateState.UpToDate
            return@withContext
        }
        Diagnostics.event(TAG, "found ${release.tag} (${apk.size} bytes)")
        offered = release
        _state.value = UpdateState.Available(release.tag, apk.size)
    }

    /** Downloads the release the last check offered; the settings row and the prompt both call it. */
    suspend fun download() = withContext(Dispatchers.IO) {
        val release = offered ?: return@withContext
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Ready) return@withContext
        val apk = release.apk ?: return@withContext
        download(release, apk)
    }

    /** Opens the system installer on the downloaded APK, asking for the permission it needs first. */
    fun install(): InstallResult {
        val ready = _state.value as? UpdateState.Ready ?: return InstallResult.Nothing
        if (!app.packageManager.canRequestPackageInstalls()) {
            val setting = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(setting)
            return InstallResult.NeedsPermission
        }
        return try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", ready.file)
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(install)
            Diagnostics.event(TAG, "installer opened on ${ready.file.name}")
            InstallResult.Started
        } catch (e: RuntimeException) {
            Diagnostics.event(TAG, "installer refused: ${e.message}")
            InstallResult.Nothing
        }
    }

    private suspend fun download(release: AppRelease, apk: UpdateApk) {
        _state.value = UpdateState.Downloading(release.tag, 0, apk.size)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "${apk.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            get(apk.url).use { response ->
                if (!response.isSuccessful) throw IOException("download answered HTTP ${response.code}")
                val body = response.body ?: throw IOException("download has no body")
                val total = body.contentLength().takeIf { it > 0 } ?: apk.size
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            _state.value = UpdateState.Downloading(release.tag, received, total)
                        }
                    }
                }
            }
            if (apk.size > 0 && received != apk.size) throw IOException("got $received of ${apk.size} bytes")
            val wanted = apk.sha256?.removePrefix("sha256:")?.lowercase()
            if (wanted != null && digest.digest().joinToString("") { "%02x".format(it) } != wanted) {
                throw IOException("checksum does not match")
            }
            val file = File(dir, apk.name)
            if (!part.renameTo(file)) throw IOException("cannot keep the download")
            Diagnostics.event(TAG, "downloaded ${release.tag} to ${file.name}")
            _state.value = UpdateState.Ready(release.tag, file)
        } catch (e: IOException) {
            part.delete()
            Diagnostics.event(TAG, "download failed: ${e.message}")
            _state.value = UpdateState.Failed(e.message ?: "download failed")
        }
    }

    private fun get(url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()

    private fun checkedToday(): Boolean =
        System.currentTimeMillis() - prefs.getLong(KEY_CHECKED_AT, 0L) < CHECK_INTERVAL_MS

    private fun remember() {
        prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
    }

    private companion object {
        const val TAG = "Update"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "RexPocket"
        const val FEED = "https://api.github.com/repos/leizhiyuan/rexpocket/releases?per_page=10"
        const val KEY_CHECKED_AT = "update.checkedAt"
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}

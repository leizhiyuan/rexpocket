package me.leaver.rexpocket.net.addon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.leaver.rexpocket.BuildConfig
import me.leaver.rexpocket.core.AppRelease
import me.leaver.rexpocket.core.AppVersion
import me.leaver.rexpocket.diagnostics.Diagnostics
import me.leaver.rexpocket.net.LOG_TAG
import me.leaver.rexpocket.netbird.api.NetbirdAddon
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The release whose add-on this app installs: the one of the app's own version, so the two match;
 * else the newest older one that carries it (a release published without the add-on, or a build
 * between releases). Never one newer than the app: an add-on is not updated past it.
 */
internal fun addonReleaseFor(releases: List<AppRelease>, app: AppVersion?): AppRelease? {
    val carrying = releases.filter { it.apk != null }
    if (app == null) return carrying.maxByOrNull { it.version }
    return carrying.firstOrNull { it.version == app } ?: carrying.filter { it.version < app }.maxByOrNull { it.version }
}

/**
 * Updates the Netbird add-on from the app's releases, as the app's own updater updates the app:
 * the add-on's APK ([NetbirdAddon.APK_NAME]) of the release [addonReleaseFor] picks, checked against
 * the size and SHA-256 the feed gives, then handed to Android's installer. Android installs it only
 * over an add-on signed with the same key. A release without the add-on opens the releases page.
 */
internal object AddonUpdates {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Downloading(val percent: Int) : State

        /** The installer is open on it; the add-on's binding dies once it is in, and is made again. */
        data object Installing : State

        /** Android does not let RexPocket install apps yet: its setting is open. */
        data object NeedsPermission : State

        /** No release carries the add-on for this version: the releases page is open instead. */
        data object NotPublished : State

        data class Failed(val detail: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    /** Looks for the add-on, downloads it and opens the installer; one at a time. */
    fun update(context: Context) {
        val app = context.applicationContext
        when (_state.value) {
            State.Checking, is State.Downloading -> return
            else -> Unit
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission
            val setting = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(setting) }
            return
        }
        _state.value = State.Checking
        scope.launch {
            _state.value = try {
                run(app)
            } catch (e: IOException) {
                Diagnostics.event(LOG_TAG, "Netbird add-on update failed: ${e.javaClass.simpleName}: ${e.message}")
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun run(app: Context): State {
        val feed = get(FEED).use { response ->
            if (!response.isSuccessful) throw IOException("releases answered HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val release = addonReleaseFor(
            runCatching { AppRelease.parseFeed(feed) { it == NetbirdAddon.APK_NAME } }.getOrElse { throw IOException("unreadable releases") },
            AppVersion.parse(BuildConfig.VERSION_NAME),
        )
        val apk = release?.apk
        if (release == null || apk == null) {
            Diagnostics.event(LOG_TAG, "Netbird add-on update: no release carries it")
            runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NETBIRD_ADDON_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return State.NotPublished
        }
        val dir = File(app.cacheDir, "updates/addon").apply { mkdirs() }
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
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            if (total > 0) _state.value = State.Downloading((received * 100 / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            if (apk.size > 0 && received != apk.size) throw IOException("got $received of ${apk.size} bytes")
            val wanted = apk.sha256?.removePrefix("sha256:")?.lowercase()
            if (wanted != null && digest.digest().joinToString("") { "%02x".format(it) } != wanted) {
                throw IOException("checksum does not match")
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        val file = File(dir, apk.name)
        if (!part.renameTo(file)) throw IOException("cannot keep the download")
        Diagnostics.event(LOG_TAG, "Netbird add-on update: ${release.tag} downloaded; installer opened")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(install)
        } catch (e: RuntimeException) {
            throw IOException("the installer did not open")
        }
        return State.Installing
    }

    private fun get(url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).header("User-Agent", "RexPocket").build()).execute()

    /** The releases feed the app's own updater reads. */
    private const val FEED = "https://api.github.com/repos/leizhiyuan/rexpocket/releases?per_page=10"
}

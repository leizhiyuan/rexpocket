package me.leaver.rexpocket.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.leaver.rexpocket.MainActivity
import me.leaver.rexpocket.R
import me.leaver.rexpocket.core.PocketState
import me.leaver.rexpocket.core.SessionView
import me.leaver.rexpocket.core.WindowInfo
import me.leaver.rexpocket.data.SavedHost
import me.leaver.rexpocket.diagnostics.Diagnostics

// Launcher shortcuts: a long press on the app's icon lists the panes opened last, newest first, and
// any of them can be dragged to the home screen, where it stays pinned. A tap opens that tab
// straight away, connecting its host first; a host removed since, or a session ended, says so in a
// toast instead. See docs/design/tailnet-control.md, "Launcher shortcuts".

/** The pane a launcher shortcut opens. */
data class PaneShortcut(val hostId: String, val sessionId: String, val windowId: String, val blockId: String?) {
    /** One shortcut per tab: opening a tab again moves it to the top rather than adding another. */
    val id: String get() = "pane:$hostId:$sessionId:$windowId"
}

object LauncherShortcuts {
    const val ACTION_OPEN_PANE = "me.leaver.rexpocket.OPEN_PANE"
    private const val EXTRA_HOST = "host"
    private const val EXTRA_SESSION = "session"
    private const val EXTRA_WINDOW = "window"
    private const val EXTRA_BLOCK = "block"
    private const val TAG = "RexPocketShortcut"

    fun intent(context: Context, pane: PaneShortcut): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_PANE)
            .putExtra(EXTRA_HOST, pane.hostId).putExtra(EXTRA_SESSION, pane.sessionId)
            .putExtra(EXTRA_WINDOW, pane.windowId).putExtra(EXTRA_BLOCK, pane.blockId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The pane [intent] names, when it is a shortcut's. */
    fun read(intent: Intent?): PaneShortcut? {
        if (intent?.action != ACTION_OPEN_PANE) return null
        val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: return null
        val session = intent.getStringExtra(EXTRA_SESSION)?.takeIf { it.isNotBlank() } ?: return null
        val window = intent.getStringExtra(EXTRA_WINDOW)?.takeIf { it.isNotBlank() } ?: return null
        return PaneShortcut(host, session, window, intent.getStringExtra(EXTRA_BLOCK)?.takeIf { it.isNotBlank() })
    }

    /**
     * Puts [pane] at the top of the launcher's list, named [title] (the tab) and, where there is
     * room, "[title] · session · host". The launcher keeps the few newest.
     */
    fun opened(context: Context, pane: PaneShortcut, title: String, session: String, host: String) {
        val short = title.ifBlank { session }.take(SHORT_MAX)
        val long = listOf(title, session, host).filter { it.isNotBlank() }.distinct().joinToString(" · ").take(LONG_MAX)
        val info = ShortcutInfoCompat.Builder(context, pane.id)
            .setShortLabel(short.ifBlank { host })
            .setLongLabel(long.ifBlank { host })
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_pane))
            .setIntent(intent(context, pane))
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, info) }
            .onFailure { Diagnostics.event(TAG, "push failed: ${it.javaClass.simpleName}") }
    }

    /** Takes [pane] off the launcher's list: its session is gone. A pinned copy stays and says so when tapped. */
    fun gone(context: Context, pane: PaneShortcut) {
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(pane.id)) }
    }

    /** Takes every shortcut of host [hostId] off the launcher's list: the host was removed. */
    fun hostRemoved(context: Context, hostId: String) {
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith("pane:$hostId:") }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
        }
    }

    /** What a shortcut is called by: the tab's own name, else its program's title, else the session's. */
    fun title(view: SessionView, window: WindowInfo, state: PocketState): String {
        if (!GENERATED_WINDOW_LABEL.matches(window.label)) return window.label
        val pane = window.focusedBlock()?.id
        return pane?.let { state.titles[it] } ?: pane?.let { state.programs[it] } ?: view.label
    }

    private const val SHORT_MAX = 25
    private const val LONG_MAX = 60
}
